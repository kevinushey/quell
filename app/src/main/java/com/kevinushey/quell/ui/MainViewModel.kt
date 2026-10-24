package com.kevinushey.quell.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kevinushey.quell.QuellApp
import com.kevinushey.quell.data.AnalysisSettings
import com.kevinushey.quell.data.AppSettings
import com.kevinushey.quell.data.DatasetMeta
import com.kevinushey.quell.data.ImportException
import com.kevinushey.quell.data.ParsedRun
import com.kevinushey.quell.data.TimeSeries
import com.kevinushey.quell.dsp.Spectrum
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

    private val resampledCache = HashMap<ResampleKey, Resampled>()
    private val psdCache = LinkedHashMap<PsdKey, Spectrum>()
    private val cacheMutex = Mutex()

    val spectra: StateFlow<SpectraState> = combine(datasets, _analysis) { ds, s -> ds to s }
        .mapLatest { (ds, s) ->
            val visible = ds.filter { it.visible }
            val results = ArrayList<SpectrumResult>(visible.size)
            for (meta in visible) {
                val r = resampled(meta) ?: continue
                val raw = cachedPsd(meta, r, s)
                results.add(AnalysisEngine.finish(meta, raw, r.sampleRate, s))
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
    }

    fun updateAnalysis(transform: (AnalysisSettings) -> AnalysisSettings) {
        val next = transform(_analysis.value)
        _analysis.value = next
        viewModelScope.launch { settingsStore.saveAnalysis(next) }
    }

    fun updateAppSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(_appSettings.value)
        _appSettings.value = next
        viewModelScope.launch { settingsStore.saveApp(next) }
    }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty())
            return
        viewModelScope.launch {
            _busy.value++
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

    fun addRecording(name: String, run: ParsedRun) {
        viewModelScope.launch {
            _busy.value++
            try {
                repo.add(name, "Recorded on device", run)
                _messages.tryEmit("Saved recording \"$name\"")
            } catch (e: Exception) {
                _messages.tryEmit("Could not save recording: ${e.message}")
            } finally {
                _busy.value--
            }
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
        viewModelScope.launch { repo.updateAll { it.copy(visible = visible) } }
    }

    fun soloVisible(id: String) {
        viewModelScope.launch { repo.updateAll { it.copy(visible = it.id == id) } }
    }

    fun move(id: String, delta: Int) {
        viewModelScope.launch { repo.move(id, delta) }
    }

    fun setReference(id: String?) = updateAnalysis { it.copy(referenceId = id) }

    fun delete(id: String) {
        viewModelScope.launch {
            repo.delete(id)
            cacheMutex.withLock {
                resampledCache.keys.removeAll { it.id == id }
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
        val summary = withContext(Dispatchers.Default) { TapAnalysis.analyze(s.slice(meta.trimStart, meta.effectiveTrimEnd), threshold) }
        cacheMutex.withLock {
            tapCache.keys.removeAll { it.id == meta.id && it != key }
            tapCache[key] = summary
        }
        return summary
    }

    /** Uniformly resampled samples inside the dataset's trim window. */
    suspend fun resampled(meta: DatasetMeta): Resampled? {
        val key = ResampleKey(meta.id, meta.trimStart, meta.trimEnd)
        cacheMutex.withLock { resampledCache[key] }?.let { return it }
        val s = repo.series(meta.id) ?: return null
        val r = withContext(Dispatchers.Default) { Resampled.from(s.slice(meta.trimStart, meta.effectiveTrimEnd)) }
        cacheMutex.withLock {
            resampledCache.keys.removeAll { it.id == meta.id && it != key }
            resampledCache[key] = r
        }
        return r
    }

    private suspend fun cachedPsd(meta: DatasetMeta, r: Resampled, s: AnalysisSettings): Spectrum {
        val key = PsdKey(meta.id, s.channel, s.segmentSize, s.window, s.overlap, meta.trimStart, meta.trimEnd)
        cacheMutex.withLock { psdCache[key] }?.let { return it }
        val psd = AnalysisEngine.rawPsd(r, s.channel, s.segmentSize, s.window, s.overlap)
        cacheMutex.withLock {
            psdCache[key] = psd
            // Bound memory: drop the oldest entries past a generous limit.
            while (psdCache.size > 64)
                psdCache.remove(psdCache.keys.first())
        }
        return psd
    }

    private fun updateMeta(id: String, transform: (DatasetMeta) -> DatasetMeta) {
        val meta = datasets.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch { repo.update(transform(meta)) }
    }
}
