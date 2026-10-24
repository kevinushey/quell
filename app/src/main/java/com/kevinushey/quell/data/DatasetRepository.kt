package com.kevinushey.quell.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.kevinushey.quell.dsp.Resampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

/**
 * Imported datasets live in app-private storage: a JSON index plus one
 * compact binary sample file per dataset, so relaunching the app does not
 * require re-parsing CSVs.
 */
class DatasetRepository(private val context: Context) {

    private val json = indexJson
    private val dir: File = File(context.filesDir, "datasets").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val sweepsFile = File(dir, "sweeps.json")
    private val mutex = Mutex()
    private val cache = ByteBudgetCache<String, TimeSeries>(ByteBudgetCache.defaultBudget()) { it.size.toLong() * 4 * 8 }
    private var loaded = false

    private val _datasets = MutableStateFlow<List<DatasetMeta>>(emptyList())
    val datasets: StateFlow<List<DatasetMeta>> = _datasets

    private val _sweeps = MutableStateFlow<List<Sweep>>(emptyList())
    val sweeps: StateFlow<List<Sweep>> = _sweeps

    /** Problems that lost or endangered data, for the UI to surface. */
    private val _problems = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val problems: SharedFlow<String> = _problems

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked() }
    }

    /**
     * Reads the index once. A corrupt index is set aside under a new name
     * instead of being silently replaced, so nothing is lost for good.
     */
    private fun loadLocked() {
        if (loaded)
            return
        loaded = true
        loadSweepsLocked()
        val atomic = AtomicFile(indexFile)
        if (!indexFile.exists() && !File(dir, "index.json.bak").exists())
            return

        val text = runCatching { String(atomic.readFully(), Charsets.UTF_8) }.getOrNull()
        val decoded = text?.let { runCatching { json.decodeFromString<List<DatasetMeta>>(migrateIndex(it)) }.getOrNull() }
        if (decoded == null) {
            val kept = File(dir, "index.broken-${System.currentTimeMillis()}.json")
            if (indexFile.exists())
                indexFile.renameTo(kept)
            _problems.tryEmit("The dataset index could not be read. A copy was kept as ${kept.name}; the datasets are still on disk.")
            return
        }
        _datasets.value = decoded.filter { File(dir, "${it.id}.bin").exists() }
    }

    private fun loadSweepsLocked() {
        val atomic = AtomicFile(sweepsFile)
        if (!sweepsFile.exists() && !File(dir, "sweeps.json.bak").exists())
            return
        val text = runCatching { String(atomic.readFully(), Charsets.UTF_8) }.getOrNull()
        val decoded = text?.let { runCatching { json.decodeFromString<List<Sweep>>(it) }.getOrNull() }
        if (decoded == null) {
            val kept = File(dir, "sweeps.broken-${System.currentTimeMillis()}.json")
            if (sweepsFile.exists())
                sweepsFile.renameTo(kept)
            _problems.tryEmit("The sweep list could not be read. A copy was kept as ${kept.name}; the runs themselves are intact.")
            return
        }
        _sweeps.value = decoded
    }

    suspend fun importUri(uri: Uri): DatasetMeta = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val parsed = context.contentResolver.openInputStream(uri)?.use { stream ->
            AccelerometerParser.parse(BufferedInputStream(stream, 1 shl 16), name)
        } ?: throw ImportException("Could not open $name")
        add(defaultName(name), name, parsed)
    }

    /** `configure` stamps extra metadata (sweep membership) before the entry is first persisted. */
    suspend fun add(
        name: String,
        sourceName: String,
        parsed: ParsedRun,
        configure: (DatasetMeta) -> DatasetMeta = { it },
    ): DatasetMeta = withContext(Dispatchers.IO) {
        val series = parsed.series
        val id = UUID.randomUUID().toString()
        val file = File(dir, "$id.bin")
        try {
            writeSeries(file, series)
        } catch (e: Exception) {
            // A partial file would only eat the space a retry needs.
            file.delete()
            throw e
        }

        mutex.withLock {
            loadLocked()
            val meta = configure(
                DatasetMeta(
                    id = id,
                    name = uniqueName(name),
                    sourceName = sourceName,
                    importedAtEpochMs = System.currentTimeMillis(),
                    sampleCount = series.size,
                    sampleRate = Resampler.estimateSampleRate(series.time),
                    durationSec = series.duration,
                    colorIndex = nextColorIndex(),
                    deviceModel = parsed.deviceModel,
                    sensorName = parsed.sensorName,
                    recordedAt = parsed.recordedAt,
                    extraFields = parsed.extraFields,
                    kind = parsed.kind,
                    locationMarks = parsed.locationMarks,
                )
            )
            // Persist first: a failed write must leave neither a phantom entry nor an orphan file.
            val next = _datasets.value + meta
            try {
                persistIndex(next)
            } catch (e: Exception) {
                file.delete()
                throw e
            }
            cache.put(id, series)
            _datasets.value = next
            meta
        }
    }

    /**
     * Samples with time in [from, to]. Served from the cache when the run is
     * loaded; otherwise only that slice is read from disk, so zooming into a
     * long run never pulls the whole file through the cache.
     */
    suspend fun seriesRange(id: String, from: Double, to: Double): TimeSeries? = withContext(Dispatchers.IO) {
        mutex.withLock {
            cache[id]?.let { return@withLock it.sliceTime(from, to) }
            val file = File(dir, "$id.bin")
            if (!file.exists())
                return@withLock null
            try {
                readSeriesRange(file, from, to)
            } catch (e: Exception) {
                _problems.tryEmit("Could not read the samples of a dataset: ${e.message ?: e.javaClass.simpleName}")
                null
            }
        }
    }

    /** Where a dataset's samples live; the sync mirror copies these files as they are. */
    fun datasetFile(id: String): File = File(dir, "$id.bin")

    /**
     * Registers datasets whose .bin files are already in place (restored from
     * a mirror) and merges sweeps by id. Ids already present are skipped.
     */
    suspend fun adopt(metas: List<DatasetMeta>, sweeps: List<Sweep>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            val have = _datasets.value.map { it.id }.toSet()
            val incoming = metas.filter { it.id !in have && File(dir, "${it.id}.bin").exists() }
            val haveSweeps = _sweeps.value.map { it.id }.toSet()
            val newSweeps = sweeps.filter { it.id !in haveSweeps }
            if (incoming.isEmpty() && newSweeps.isEmpty())
                return@withLock
            val nextDatasets = _datasets.value + incoming
            val nextSweeps = _sweeps.value + newSweeps
            persistIndex(nextDatasets)
            _datasets.value = nextDatasets
            persistSweeps(nextSweeps)
            _sweeps.value = nextSweeps
        }
    }

    /** Drops cached samples so a large save or import has the heap to themselves; they reload on demand. */
    suspend fun releaseSamples() = mutex.withLock { cache.clear() }

    /** Reads under the mutex so a concurrent delete() cannot remove the file mid-read. */
    suspend fun series(id: String): TimeSeries? = withContext(Dispatchers.IO) {
        mutex.withLock {
            cache[id]?.let { return@withLock it }
            val file = File(dir, "$id.bin")
            if (!file.exists())
                return@withLock null
            try {
                readSeries(file).also { cache.put(id, it) }
            } catch (e: Exception) {
                _problems.tryEmit("Could not read the samples of a dataset: ${e.message ?: e.javaClass.simpleName}")
                null
            }
        }
    }

    /** Applies `transform` to the latest metadata under the lock, so queued edits compose instead of clobbering. */
    suspend fun update(id: String, transform: (DatasetMeta) -> DatasetMeta) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            // Persist first, so a failed write leaves the screens agreeing with the disk.
            val next = _datasets.value.map { if (it.id == id) transform(it) else it }
            persistIndex(next)
            _datasets.value = next
        }
    }

    suspend fun updateAll(transform: (DatasetMeta) -> DatasetMeta) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            val next = _datasets.value.map(transform)
            persistIndex(next)
            _datasets.value = next
        }
    }

    suspend fun move(id: String, delta: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            val list = _datasets.value.toMutableList()
            val from = list.indexOfFirst { it.id == id }
            val to = from + delta
            if (from < 0 || to < 0 || to >= list.size)
                return@withLock
            list.add(to, list.removeAt(from))
            persistIndex(list)
            _datasets.value = list
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            File(dir, "$id.bin").delete()
            cache.remove(id)
            _datasets.value = _datasets.value.filterNot { it.id == id }
            persistIndex()
        }
    }

    suspend fun addSweep(sweep: Sweep) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            val next = _sweeps.value + sweep
            persistSweeps(next)
            _sweeps.value = next
        }
    }

    suspend fun updateSweep(id: String, transform: (Sweep) -> Sweep) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            val next = _sweeps.value.map { if (it.id == id) transform(it) else it }
            persistSweeps(next)
            _sweeps.value = next
        }
    }

    /** Removes the sweep and releases its members; the runs themselves stay. */
    suspend fun deleteSweep(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            val released = _datasets.value.map { if (it.sweepId == id) it.copy(sweepId = null, sweepValue = null) else it }
            val remaining = _sweeps.value.filterNot { it.id == id }
            persistIndex(released)
            _datasets.value = released
            persistSweeps(remaining)
            _sweeps.value = remaining
        }
    }

    /** Write-to-temp-then-rename, so a crash mid-write leaves the previous index intact. */
    private fun persistIndex(list: List<DatasetMeta> = _datasets.value) {
        writeAtomic(indexFile, json.encodeToString(list))
    }

    private fun persistSweeps(list: List<Sweep>) {
        writeAtomic(sweepsFile, json.encodeToString(list))
    }

    private fun writeAtomic(file: File, text: String) {
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    private fun nextColorIndex(): Int {
        val used = _datasets.value.map { it.colorIndex }.toSet()
        return (0 until 64).firstOrNull { it !in used } ?: _datasets.value.size
    }

    private fun uniqueName(base: String): String {
        val names = _datasets.value.map { it.name }.toSet()
        if (base !in names)
            return base
        var i = 2
        while ("$base ($i)" in names)
            i++
        return "$base ($i)"
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0)
                    c.getString(idx)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "import"
    }

    companion object {
        // Defaults are written out so a future change of default cannot silently re-tag old entries.
        internal val indexJson = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

        /**
         * Early indexes omitted default fields, and the default kind was DRIVE
         * at the time. Those entries must stay drive runs now that GENERAL is
         * the default.
         */
        internal fun migrateIndex(text: String): String {
            val root = indexJson.parseToJsonElement(text) as? JsonArray ?: return text
            val migrated = JsonArray(root.map { el ->
                val obj = el as? JsonObject ?: return@map el
                if ("kind" in obj)
                    obj
                else
                    JsonObject(obj + ("kind" to JsonPrimitive(ExperimentKind.DRIVE.name)))
            })
            return indexJson.encodeToString(JsonArray.serializer(), migrated)
        }

        private val PHYPHOX_PREFIXES = listOf(
            "Acceleration with g ",
            "Acceleration (without g) ",
            "Acceleration without g ",
            "Linear Acceleration ",
        )

        /** "Acceleration with g 2026-10-20 14-14-14 Baseline.zip" -> "2026-10-20 14-14-14 Baseline". */
        fun defaultName(fileName: String): String {
            var name = fileName.substringBeforeLast('.')
            for (p in PHYPHOX_PREFIXES) {
                if (name.startsWith(p, ignoreCase = true)) {
                    name = name.substring(p.length)
                    break
                }
            }
            return name.trim().ifEmpty { fileName }
        }

        private const val MAGIC = 0x41434331 // "ACC1"

        fun writeSeries(file: File, s: TimeSeries) {
            DataOutputStream(file.outputStream().buffered(1 shl 16)).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(s.size)
                for (arr in arrayOf(s.time, s.x, s.y, s.z))
                    for (v in arr)
                        out.writeDouble(v)
            }
        }

        private const val HEADER_BYTES = 8L

        /** Reads only the samples with time in [from, to], seeking within the four fixed-width arrays. */
        fun readSeriesRange(file: File, from: Double, to: Double): TimeSeries {
            java.io.RandomAccessFile(file, "r").use { raf ->
                if (raf.readInt() != MAGIC)
                    throw IllegalStateException("Bad dataset file")
                val n = raf.readInt()
                fun timeAt(i: Int): Double {
                    raf.seek(HEADER_BYTES + i.toLong() * 8)
                    return raf.readDouble()
                }
                fun lowerBound(target: Double): Int {
                    var lo = 0
                    var hi = n
                    while (lo < hi) {
                        val mid = (lo + hi) ushr 1
                        if (timeAt(mid) < target)
                            lo = mid + 1
                        else
                            hi = mid
                    }
                    return lo
                }
                // Bracketing samples match TimeSeries.sliceTime; see the note there.
                val a = (lowerBound(from) - 1).coerceAtLeast(0)
                val b = (lowerBound(to) + 1).coerceAtMost(n)
                val count = (b - a).coerceAtLeast(0)
                val arrays = Array(4) { DoubleArray(count) }
                val bytes = java.nio.ByteBuffer.allocate(count * 8)
                for (k in 0 until 4) {
                    raf.seek(HEADER_BYTES + (k.toLong() * n + a) * 8)
                    bytes.clear()
                    raf.readFully(bytes.array(), 0, count * 8)
                    bytes.asDoubleBuffer().get(arrays[k])
                }
                return TimeSeries(arrays[0], arrays[1], arrays[2], arrays[3])
            }
        }

        /** Checks the header and that the file holds exactly the samples it announces. */
        fun validateSeriesFile(file: File) {
            java.io.RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < HEADER_BYTES || raf.readInt() != MAGIC)
                    throw IllegalStateException("${file.name} is not a dataset file")
                val n = raf.readInt()
                if (n < 0 || raf.length() != HEADER_BYTES + n.toLong() * 4 * 8)
                    throw IllegalStateException("${file.name} is truncated")
            }
        }

        fun readSeries(file: File): TimeSeries {
            DataInputStream(file.inputStream().buffered(1 shl 16)).use { input ->
                val magic = input.readInt()
                if (magic != MAGIC)
                    throw IllegalStateException("Bad dataset file")
                val n = input.readInt()
                val arrays = Array(4) { DoubleArray(n) }
                for (arr in arrays)
                    for (i in 0 until n)
                        arr[i] = input.readDouble()
                return TimeSeries(arrays[0], arrays[1], arrays[2], arrays[3])
            }
        }
    }
}
