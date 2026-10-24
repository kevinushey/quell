package com.kevinushey.quell.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kevinushey.quell.QuellApp
import com.kevinushey.quell.data.AnalysisSettings
import com.kevinushey.quell.data.AppSettings
import com.kevinushey.quell.data.ByteBudgetCache
import com.kevinushey.quell.data.DatasetMeta
import com.kevinushey.quell.data.ImportException
import com.kevinushey.quell.data.ParsedRun
import com.kevinushey.quell.data.TimeSeries
import com.kevinushey.quell.dsp.Spectrum
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

class SpectraState(
    val results: List<SpectrumResult>,
    val settings: AnalysisSettings,
    val computing: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as QuellApp).repository
    private val settingsStore = (app as QuellApp).settings

    val datasets: StateFlow<List<DatasetMeta>> = repo.datasets

    private val _analysis = MutableStateFlow(AnalysisSettings())
    val analysis: StateFlow<AnalysisSettings> = _analysis

    private val _appSettings = MutableStateFlow(AppSettings())
    val appSettings: StateFlow<AppSettings> = _appSettings

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private val _busy = MutableStateFlow(0)
    val busy: StateFlow<Boolean> = _busy.map { it > 0 }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    /** Keyed by dataset id plus trim window, so editing the window recomputes. */
    private data class ResampleKey(val id: String, val trimStart: Double, val trimEnd: Double)

    private val resampledCache = ByteBudgetCache<ResampleKey, Resampled>(ByteBudgetCache.defaultBudget()) { it.size.toLong() * 4 * 8 }
    private val psdCache = LinkedHashMap<PsdKey, Pair<Spectrum, Double>>()
    private val cacheMutex = Mutex()

    val spectra: StateFlow<SpectraState> = combine(datasets, _analysis) { ds, s -> ds to s }
        .mapLatest { (ds, s) ->
            val visible = ds.filter { it.visible }
            val results = ArrayList<SpectrumResult>(visible.size)
            for (meta in visible) {
                val (raw, fs) = cachedPsd(meta, s) ?: continue
                results.add(AnalysisEngine.finish(meta, raw, fs, s))
                yield()
            }
            SpectraState(results, s, computing = false)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpectraState(emptyList(), AnalysisSettings(), computing = true))

    init {
        viewModelScope.launch {
            _analysis.value = settingsStore.analysis.first()
            _appSettings.value = settingsStore.app.first()
            repo.load()
            _ready.value = true
        }
        viewModelScope.launch { repo.problems.collect { _messages.emit(it) } }
    }

    /**
     * Persistence can fail (storage full, index unwritable); report it instead
     * of letting the exception reach the uncaught handler and kill the app.
     */
    private fun launchReporting(what: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit("Could not $what: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun updateAnalysis(transform: (AnalysisSettings) -> AnalysisSettings) {
        val next = transform(_analysis.value)
        _analysis.value = next
        launchReporting("save analysis settings") { settingsStore.saveAnalysis(next) }
    }

    fun updateAppSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(_appSettings.value)
        _appSettings.value = next
        launchReporting("save settings") { settingsStore.saveApp(next) }
    }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty())
            return
        viewModelScope.launch {
            _busy.value++
            releaseMemoryForSave()
            var ok = 0
            var firstError: String? = null
            for (uri in uris) {
                try {
                    repo.importUri(uri)
                    ok++
                } catch (e: ImportException) {
                    firstError = firstError ?: e.message
                } catch (e: Exception) {
                    firstError = firstError ?: "Import failed: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
            _busy.value--
            val summary = buildString {
                if (ok > 0)
                    append("Imported $ok dataset${if (ok == 1) "" else "s"}")
                if (firstError != null) {
                    if (isNotEmpty())
                        append(". ")
                    val failed = uris.size - ok
                    append(if (failed > 1) "$failed failed: $firstError" else firstError)
                }
            }
            _messages.tryEmit(summary)
        }
    }

    /**
     * Caches are rebuilt cheaply from disk; a pending recording is not. Free the
     * heap before the save-time copies and analysis so they cannot OOM.
     */
    private suspend fun releaseMemoryForSave() {
        cacheMutex.withLock { resampledCache.clear() }
        repo.releaseSamples()
    }

    /** Persists a recording; false means it was not saved and the caller must keep it. */
    suspend fun addRecording(name: String, run: ParsedRun): Boolean {
        _busy.value++
        return try {
            releaseMemoryForSave()
            repo.add(name, "Recorded on device", run)
            _messages.tryEmit("Saved recording \"$name\"")
            true
        } catch (e: Exception) {
            _messages.tryEmit("Could not save recording: ${e.message}")
            false
        } finally {
            _busy.value--
        }
    }

    fun setVisible(id: String, visible: Boolean) = updateMeta(id) { it.copy(visible = visible) }
    fun rename(id: String, name: String) = updateMeta(id) { it.copy(name = name.trim().ifEmpty { it.name }) }
    fun setColor(id: String, colorIndex: Int) = updateMeta(id) { it.copy(colorIndex = colorIndex) }
    fun setNotes(id: String, notes: String) = updateMeta(id) { it.copy(notes = notes) }
    fun setKind(id: String, kind: com.kevinushey.quell.data.ExperimentKind) = updateMeta(id) { it.copy(kind = kind) }
    fun setDamperMass(id: String, grams: Double?) = updateMeta(id) { it.copy(damperMassGrams = grams) }
    fun setTrim(id: String, start: Double, end: Double) = updateMeta(id) { it.copy(trimStart = start.coerceAtLeast(0.0), trimEnd = end) }

    fun setAllVisible(visible: Boolean) {
        launchReporting("update datasets") { repo.updateAll { it.copy(visible = visible) } }
    }

    fun soloVisible(id: String) {
        launchReporting("update datasets") { repo.updateAll { it.copy(visible = it.id == id) } }
    }

    fun move(id: String, delta: Int) {
        launchReporting("reorder datasets") { repo.move(id, delta) }
    }

    fun setReference(id: String?) = updateAnalysis { it.copy(referenceId = id) }

    fun delete(id: String) {
        launchReporting("delete dataset") {
            repo.delete(id)
            cacheMutex.withLock {
                resampledCache.removeIf { it.id == id }
                psdCache.keys.removeAll { it.id == id }
                tapCache.keys.removeAll { it.id == id }
            }
            if (_analysis.value.referenceId == id)
                setReference(null)
            _messages.tryEmit("Dataset deleted")
        }
    }

    fun message(text: String) {
        _messages.tryEmit(text)
    }

    suspend fun series(id: String): TimeSeries? = repo.series(id)

    private data class TapKey(val id: String, val trimStart: Double, val trimEnd: Double, val threshold: Double)
    private val tapCache = HashMap<TapKey, TapSummary>()

    /** Ring-down analysis of every impulse in the dataset's trim window. */
    suspend fun tapSummary(meta: DatasetMeta, threshold: Double): TapSummary? {
        val key = TapKey(meta.id, meta.trimStart, meta.trimEnd, threshold)
        cacheMutex.withLock { tapCache[key] }?.let { return it }
        val s = repo.series(meta.id) ?: return null
        val summary = analyzeOrReport(meta) { TapAnalysis.analyze(s.slice(meta.trimStart, meta.effectiveTrimEnd), threshold) } ?: return null
        cacheMutex.withLock {
            tapCache.keys.removeAll { it.id == meta.id && it != key }
            tapCache[key] = summary
        }
        return summary
    }

    /**
     * One bad dataset (say, with impossible timestamps) must not take the whole
     * spectra flow down; report it and let the others render.
     */
    private suspend fun <T> analyzeOrReport(meta: DatasetMeta, block: () -> T): T? =
        withContext(Dispatchers.Default) {
            try {
                block()
            } catch (e: Exception) {
                _messages.tryEmit("Could not analyze \"${meta.name}\": ${e.message ?: e.javaClass.simpleName}")
                null
            }
        }

    /** Uniformly resampled samples inside the dataset's trim window. */
    suspend fun resampled(meta: DatasetMeta): Resampled? {
        val key = ResampleKey(meta.id, meta.trimStart, meta.trimEnd)
        cacheMutex.withLock { resampledCache[key] }?.let { return it }
        val s = repo.series(meta.id) ?: return null
        val r = analyzeOrReport(meta) { Resampled.from(s.slice(meta.trimStart, meta.effectiveTrimEnd)) } ?: return null
        cacheMutex.withLock {
            resampledCache.removeIf { it.id == meta.id && it != key }
            resampledCache.put(key, r)
        }
        return r
    }

    /**
     * Raw PSD plus sample rate. The PSD cache is consulted before touching the
     * samples, so display-only changes never reload or resample anything.
     */
    private suspend fun cachedPsd(meta: DatasetMeta, s: AnalysisSettings): Pair<Spectrum, Double>? {
        val key = PsdKey(meta.id, s.channel, s.segmentSize, s.window, s.overlap, meta.trimStart, meta.trimEnd)
        cacheMutex.withLock { psdCache[key] }?.let { return it }
        val r = resampled(meta) ?: return null
        val psd = AnalysisEngine.rawPsd(r, s.channel, s.segmentSize, s.window, s.overlap) to r.sampleRate
        cacheMutex.withLock {
            psdCache[key] = psd
            // Bound memory: drop the oldest entries past a generous limit.
            while (psdCache.size > 64)
                psdCache.remove(psdCache.keys.first())
        }
        return psd
    }

    private fun updateMeta(id: String, transform: (DatasetMeta) -> DatasetMeta) {
        launchReporting("update dataset") { repo.update(id, transform) }
    }
}
