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
import com.kevinushey.quell.data.Sweep
import com.kevinushey.quell.data.SweepParameter
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
    private val sync = (app as QuellApp).sync

    val syncStatus: StateFlow<com.kevinushey.quell.sync.SyncStatus> = sync.status

    val datasets: StateFlow<List<DatasetMeta>> = repo.datasets
    val sweeps: StateFlow<List<Sweep>> = repo.sweeps

    private val _analysis = MutableStateFlow(AnalysisSettings())
    val analysis: StateFlow<AnalysisSettings> = _analysis

    private val _appSettings = MutableStateFlow(AppSettings())
    val appSettings: StateFlow<AppSettings> = _appSettings

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    /**
     * The device recorder and a stopped run awaiting its save dialog. Held
     * here rather than in the Record screen, so an activity recreation (a
     * fold, a window resize) keeps both.
     */
    private val recorderLazy = lazy { com.kevinushey.quell.record.Recorder(app) }
    val recorder by recorderLazy
    private val _pendingRecording = MutableStateFlow<ParsedRun?>(null)
    val pendingRecording: StateFlow<ParsedRun?> = _pendingRecording

    fun setPendingRecording(run: ParsedRun?) {
        _pendingRecording.value = run
    }

    override fun onCleared() {
        // The listener would otherwise outlive the screen that started it.
        if (recorderLazy.isInitialized())
            recorder.discard()
    }

    private val _busy = MutableStateFlow(0)
    val busy: StateFlow<Boolean> = _busy.map { it > 0 }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    /** Keyed by dataset id plus trim window, so editing the window recomputes. */
    private data class ResampleKey(val id: String, val trimStart: Double, val trimEnd: Double)

    /** Imports and saves run one at a time: the per-file memory budget only holds if they do not overlap. */
    private val importMutex = Mutex()

    private val resampledCache = ByteBudgetCache<ResampleKey, Resampled>(ByteBudgetCache.defaultBudget()) { it.size.toLong() * 4 * 8 }
    /** Windows that could not be resampled; reported once, then silent until the trim changes. */
    private val failedResamples = HashSet<ResampleKey>()
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
            // SettingsStore replaces a damaged file and its flows fall back to defaults, so these cannot fail.
            _analysis.value = settingsStore.analysis.first()
            _appSettings.value = settingsStore.app.first()
            repo.load()
            _ready.value = true
        }
        viewModelScope.launch {
            // Replayed problems arrive synchronously on collect; wait until the snackbar host is subscribed.
            _messages.subscriptionCount.first { it > 0 }
            repo.problems.collect {
                _messages.emit(it)
                repo.problemsShown()
            }
        }
        viewModelScope.launch {
            // A restore that finished while no screen was open is replayed here, like the problems above.
            _messages.subscriptionCount.first { it > 0 }
            sync.events.collect {
                _messages.emit(it)
                sync.eventsShown()
            }
        }
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
            importMutex.withLock { importAll(uris) }
        }
    }

    private suspend fun importAll(uris: List<Uri>) {
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

    /**
     * Caches are rebuilt cheaply from disk; a pending recording is not. Free the
     * heap before the save-time copies and analysis so they cannot OOM.
     */
    private suspend fun releaseMemoryForSave() {
        cacheMutex.withLock { resampledCache.clear() }
        repo.releaseSamples()
    }

    /**
     * Persists a recording; false means it was not saved and the caller must keep it.
     * Inside a sweep the run joins it at `value`, and the sweep remembers the step.
     * `trim`, when given, is the run's starting analysis window (seconds from its first sample).
     */
    suspend fun addRecording(
        name: String,
        run: ParsedRun,
        sweep: Sweep? = null,
        value: Double? = null,
        trim: ClosedFloatingPointRange<Double>? = null,
        notes: String = "",
    ): Boolean = importMutex.withLock {
        _busy.value++
        try {
            releaseMemoryForSave()
            repo.add(name, "Recorded on device", run) { meta ->
                val noted = meta.copy(notes = notes.trim())
                val windowed = if (trim == null) noted else noted.copy(trimStart = trim.start, trimEnd = trim.endInclusive)
                if (sweep == null) windowed else joinSweep(windowed, sweep, value)
            }
            // The run is safe from here on; a failed step bookkeeping must not read as a failed save.
            if (sweep != null && value != null) {
                try {
                    repo.updateSweep(sweep.id) { it.advanced(value) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _messages.tryEmit("Saved recording \"$name\", but the sweep's next step could not be recorded: ${e.message}")
                    return@withLock true
                }
            }
            _messages.tryEmit("Saved recording \"$name\"")
            true
        } catch (e: CancellationException) {
            // Cancelled is not failed: the write may well have completed, and a retry would save it twice.
            throw e
        } catch (e: Exception) {
            _messages.tryEmit("Could not save recording: ${e.message}")
            false
        } finally {
            _busy.value--
        }
    }

    private val _savingRecording = MutableStateFlow(false)
    val savingRecording: StateFlow<Boolean> = _savingRecording
    /** Set once the pending recording is stored, for the Record screen to leave; cleared by [recordingSavedHandled]. */
    private val _recordingSaved = MutableStateFlow(false)
    val recordingSaved: StateFlow<Boolean> = _recordingSaved

    /**
     * Saves the pending recording on the view model's scope: a screen
     * recreated mid-save can neither cut the write short nor offer the
     * same run for saving a second time.
     */
    fun savePendingRecording(name: String, notes: String, sweep: Sweep?, value: Double?, trim: ClosedFloatingPointRange<Double>?) {
        val run = _pendingRecording.value ?: return
        if (_savingRecording.value)
            return
        _savingRecording.value = true
        viewModelScope.launch {
            try {
                if (addRecording(name, run, sweep, value, trim, notes)) {
                    _pendingRecording.value = null
                    _recordingSaved.value = true
                }
            } finally {
                _savingRecording.value = false
            }
        }
    }

    fun recordingSavedHandled() {
        _recordingSaved.value = false
    }

    /** Samples of one dataset inside an absolute time range, read from disk when the run is not cached. */
    suspend fun seriesRange(id: String, from: Double, to: Double): TimeSeries? = repo.seriesRange(id, from, to)

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

    /** Creates the sweep and returns it, so the caller can open it. */
    suspend fun createSweep(name: String, parameter: SweepParameter, kind: com.kevinushey.quell.data.ExperimentKind): Sweep? {
        val sweep = Sweep(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "Sweep" },
            parameter = parameter,
            kind = kind,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        return try {
            repo.addSweep(sweep)
            sweep
        } catch (e: Exception) {
            _messages.tryEmit("Could not create sweep: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    fun updateSweep(id: String, transform: (Sweep) -> Sweep) {
        launchReporting("update sweep") { repo.updateSweep(id, transform) }
    }

    fun deleteSweep(id: String) {
        launchReporting("delete sweep") {
            repo.deleteSweep(id)
            _messages.tryEmit("Sweep deleted; its runs were kept")
        }
    }

    fun addToSweep(sweep: Sweep, metaId: String, value: Double?) = updateMeta(metaId) { joinSweep(it, sweep, value) }

    /** Sets the run's value in this sweep; null clears it (the row then asks for one). */
    fun setSweepValue(sweep: Sweep, metaId: String, value: Double?) = updateMeta(metaId) {
        if (sweep.parameter == SweepParameter.MASS)
            it.copy(sweepId = sweep.id, sweepValue = null, damperMassGrams = value)
        else
            it.copy(sweepId = sweep.id, sweepValue = value)
    }

    fun removeFromSweep(metaId: String) = updateMeta(metaId) { it.copy(sweepId = null, sweepValue = null) }

    /**
     * Membership plus an optional value. Mass sweeps keep the value in
     * damperMassGrams, a property of the run itself; any other parameter's
     * value belongs to the sweep it was set in, so moving between sweeps
     * drops it rather than passing off a stiffness as a position.
     */
    private fun joinSweep(meta: DatasetMeta, sweep: Sweep, value: Double?): DatasetMeta {
        if (sweep.parameter == SweepParameter.MASS)
            return meta.copy(sweepId = sweep.id, sweepValue = null, damperMassGrams = value ?: meta.damperMassGrams)
        val kept = if (meta.sweepId == sweep.id) meta.sweepValue else null
        return meta.copy(sweepId = sweep.id, sweepValue = value ?: kept)
    }

    /** Spectrum of one dataset under the given settings, visible or not; served from the PSD cache when possible. */
    suspend fun spectrumResult(meta: DatasetMeta, s: AnalysisSettings): SpectrumResult? {
        val (raw, fs) = cachedPsd(meta, s) ?: return null
        return withContext(Dispatchers.Default) { AnalysisEngine.finish(meta, raw, fs, s) }
    }

    fun delete(id: String) {
        launchReporting("delete dataset") {
            repo.delete(id)
            cacheMutex.withLock {
                resampledCache.removeIf { it.id == id }
                failedResamples.removeAll { it.id == id }
                psdCache.keys.removeAll { it.id == id }
                tapCache.keys.removeAll { it.id == id }
                failedTaps.removeAll { it.id == id }
            }
            if (_analysis.value.referenceId == id)
                setReference(null)
            for (sw in repo.sweeps.value)
                if (sw.referenceId == id)
                    repo.updateSweep(sw.id) { it.copy(referenceId = null) }
            _messages.tryEmit("Dataset deleted")
        }
    }

    fun message(text: String) {
        _messages.tryEmit(text)
    }

    /** A new mirror folder (persisted permission already taken by the caller), or null to stop mirroring. */
    fun setSyncFolder(tree: Uri?) {
        // The old folder's persisted grant is no longer wanted; the provider may already be gone.
        val previous = _appSettings.value.syncTreeUri
        if (previous != null && previous != tree?.toString()) {
            runCatching {
                getApplication<Application>().contentResolver.releasePersistableUriPermission(
                    Uri.parse(previous),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        updateAppSettings { it.copy(syncTreeUri = tree?.toString(), syncEnabled = tree != null) }
        if (tree != null)
            syncNow()
    }

    /** Runs on the sync's own scope, so leaving the screen or the app does not cut the pass short. */
    fun syncNow() {
        val app = _appSettings.value
        val tree = app.syncTreeUri?.takeIf { app.syncEnabled } ?: return
        sync.requestSync(Uri.parse(tree), app.syncCsv)
    }

    /** Pulls datasets and sweeps this phone lacks from the mirror folder; the outcome arrives as a message. */
    fun restoreFromSync() {
        val tree = _appSettings.value.syncTreeUri ?: return
        sync.requestRestore(Uri.parse(tree))
    }

    suspend fun series(id: String): TimeSeries? = repo.series(id)

    private data class TapKey(val id: String, val trimStart: Double, val trimEnd: Double, val threshold: Double)
    private val tapCache = HashMap<TapKey, TapSummary>()
    /** Tap analyses that failed; reported once, then silent until the window or threshold changes. */
    private val failedTaps = HashSet<TapKey>()

    /** Ring-down analysis of every impulse in the dataset's trim window. */
    suspend fun tapSummary(meta: DatasetMeta, threshold: Double): TapSummary? {
        val key = TapKey(meta.id, meta.trimStart, meta.trimEnd, threshold)
        cacheMutex.withLock {
            tapCache[key]?.let { return it }
            if (key in failedTaps)
                return null
        }
        val s = repo.series(meta.id) ?: return null
        val summary = analyzeOrReport(meta) { TapAnalysis.analyzeWindow(s, meta.trimStart, meta.effectiveTrimEnd, threshold) }
        cacheMutex.withLock {
            tapCache.keys.removeAll { it.id == meta.id && it != key }
            failedTaps.removeAll { it.id == meta.id }
            if (summary == null)
                failedTaps.add(key)
            else
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
        cacheMutex.withLock {
            resampledCache[key]?.let { return it }
            if (key in failedResamples)
                return null
        }
        val s = repo.series(meta.id) ?: return null
        val r = analyzeOrReport(meta) {
            val slice = s.slice(meta.trimStart, meta.effectiveTrimEnd)
            Resampled.from(slice, startTime = slice.time.firstOrNull()?.minus(s.time.first()) ?: 0.0)
        }
        cacheMutex.withLock {
            resampledCache.removeIf { it.id == meta.id && it != key }
            failedResamples.removeAll { it.id == meta.id }
            if (r == null)
                failedResamples.add(key)
            else
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
        // Welch over a long run is seconds of work, and some callers (the sweep screen's produceState) are on the main thread.
        val psd = withContext(Dispatchers.Default) { AnalysisEngine.rawPsd(r, s.channel, s.segmentSize, s.window, s.overlap) } to r.sampleRate
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
