package com.kevinushey.quell.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.kevinushey.quell.dsp.Resampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val mutex = Mutex()
    private val cache = HashMap<String, TimeSeries>()

    private val _datasets = MutableStateFlow<List<DatasetMeta>>(emptyList())
    val datasets: StateFlow<List<DatasetMeta>> = _datasets

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (indexFile.exists()) {
                _datasets.value = runCatching { json.decodeFromString<List<DatasetMeta>>(migrateIndex(indexFile.readText())) }
                    .getOrDefault(emptyList())
                    .filter { File(dir, "${it.id}.bin").exists() }
            }
        }
    }

    suspend fun importUri(uri: Uri): DatasetMeta = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val parsed = context.contentResolver.openInputStream(uri)?.use { stream ->
            AccelerometerParser.parse(BufferedInputStream(stream, 1 shl 16), name)
        } ?: throw ImportException("Could not open $name")
        add(defaultName(name), name, parsed)
    }

    suspend fun add(name: String, sourceName: String, parsed: ParsedRun): DatasetMeta = withContext(Dispatchers.IO) {
        val series = parsed.series
        val id = UUID.randomUUID().toString()
        writeSeries(File(dir, "$id.bin"), series)

        mutex.withLock {
            val meta = DatasetMeta(
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
            )
            cache[id] = series
            _datasets.value = _datasets.value + meta
            persistIndex()
            meta
        }
    }

    suspend fun series(id: String): TimeSeries? = withContext(Dispatchers.IO) {
        mutex.withLock { cache[id] }?.let { return@withContext it }

        val file = File(dir, "$id.bin")
        if (!file.exists())
            return@withContext null
        val series = readSeries(file)

        mutex.withLock { cache.getOrPut(id) { series } }
    }

    suspend fun update(meta: DatasetMeta) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _datasets.value = _datasets.value.map { if (it.id == meta.id) meta else it }
            persistIndex()
        }
    }

    suspend fun updateAll(transform: (DatasetMeta) -> DatasetMeta) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _datasets.value = _datasets.value.map(transform)
            persistIndex()
        }
    }

    suspend fun move(id: String, delta: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _datasets.value.toMutableList()
            val from = list.indexOfFirst { it.id == id }
            val to = from + delta
            if (from < 0 || to < 0 || to >= list.size)
                return@withLock
            list.add(to, list.removeAt(from))
            _datasets.value = list
            persistIndex()
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            File(dir, "$id.bin").delete()
            cache.remove(id)
            _datasets.value = _datasets.value.filterNot { it.id == id }
            persistIndex()
        }
    }

    private fun persistIndex() {
        indexFile.writeText(json.encodeToString(_datasets.value))
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
