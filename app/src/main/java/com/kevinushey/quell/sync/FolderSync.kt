package com.kevinushey.quell.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import com.kevinushey.quell.data.DatasetMeta
import com.kevinushey.quell.data.DatasetRepository
import com.kevinushey.quell.data.SettingsStore
import com.kevinushey.quell.data.Sweep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** What the Settings screen shows about the mirror. */
data class SyncStatus(
    val folderName: String? = null,
    val syncing: Boolean = false,
    val lastSyncEpochMs: Long? = null,
    val mirroredCount: Int = 0,
    val lastError: String? = null,
    /** Datasets in the folder's index that this phone lacks; the index is not written while any remain. */
    val foreignCount: Int = 0,
    /** Remote .bin files that failed validation on restore; they cannot be restored and do not block the mirror. */
    val damagedCount: Int = 0,
    /** The local index could not be fully read, so the mirror only adds files until that is sorted out. */
    val frozenByDegradedIndex: Boolean = false,
)

/** What a restore did, for the caller to report. */
data class RestoreResult(val restored: Int, val skipped: List<String>)

/**
 * Mirrors the dataset store into a folder the user picked through the system
 * picker (Google Drive, Dropbox, a local folder: anything with a documents
 * provider). The provider does the uploading, so no account or API key is
 * involved here. Runs while the app is open: that is when data changes.
 */
class FolderSync(private val context: Context, private val repo: DatasetRepository, private val settings: SettingsStore) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stateFile = File(context.filesDir, "sync-state.json")
    private val mutex = Mutex()

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status

    /** Watches the store and the settings; a change schedules a pass a few seconds later. */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            repo.load()
            combine(repo.datasets, repo.sweeps, settings.app) { _, _, app -> app }
                .debounce(3_000)
                .collect { app ->
                    val tree = app.syncTreeUri?.takeIf { app.syncEnabled } ?: run {
                        _status.value = SyncStatus()
                        return@collect
                    }
                    // Nothing may end this loop, or the process: a bad grant is a status line, not a crash.
                    try {
                        syncNow(Uri.parse(tree), app.syncCsv)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _status.value = _status.value.copy(syncing = false, lastError = e.message ?: e.javaClass.simpleName)
                    }
                }
        }
    }

    /** One mirror pass; safe to call from the UI's "Sync now". */
    suspend fun syncNow(tree: Uri, csv: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _status.value = _status.value.copy(syncing = true)
            try {
                // The name lookup touches the grant too; a revoked folder must land in lastError like anything else.
                _status.value = _status.value.copy(folderName = folderName(tree))
                val outcome = mirror(tree, csv)
                _status.value = _status.value.copy(
                    syncing = false,
                    lastSyncEpochMs = System.currentTimeMillis(),
                    mirroredCount = outcome.mirrored,
                    lastError = null,
                    foreignCount = outcome.foreign,
                    damagedCount = outcome.damaged,
                    frozenByDegradedIndex = outcome.degraded,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = _status.value.copy(syncing = false, lastError = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private class MirrorOutcome(val mirrored: Int, val foreign: Int, val damaged: Int, val degraded: Boolean)

    private fun mirror(tree: Uri, csv: Boolean): MirrorOutcome {
        val remote = listChildren(tree)
        val state = loadState()
        val metas = repo.datasets.value
        val indexBytes = DatasetRepository.indexJson.encodeToString(metas).toByteArray(Charsets.UTF_8)
        val sweepsBytes = DatasetRepository.indexJson.encodeToString(repo.sweeps.value).toByteArray(Charsets.UTF_8)
        val indexHash = sha1(indexBytes)
        val sweepsHash = sha1(sweepsBytes)
        val localSizes = metas.associate { it.id to repo.datasetFile(it.id).length() }
        val plan = SyncPlan.plan(
            metas.map { it.id },
            localSizes,
            remote.mapValues { RemoteFile(it.value.size) },
            remoteIndexIds(remote),
            state,
            indexHash,
            indexBytes.size.toLong(),
            sweepsHash,
            sweepsBytes.size.toLong(),
            csv,
            degraded = repo.degraded.value,
        )

        var mirrored = state.mirrored.toMutableSet()
        val csvNames = state.csvNames.toMutableMap()
        for (id in plan.binsToWrite) {
            val local = repo.datasetFile(id)
            if (!local.exists())
                continue
            writeDocument(tree, remote, "$id.bin", "application/octet-stream") { out -> local.inputStream().use { it.copyTo(out) } }
            mirrored.add(id)
        }
        if (csv) {
            for (id in plan.csvToWrite) {
                val meta = metas.firstOrNull { it.id == id } ?: continue
                val series = repo.datasetFile(id).takeIf { it.exists() }?.let { DatasetRepository.readSeries(it) } ?: continue
                val name = csvName(meta)
                writeDocument(tree, remote, name, "text/csv") { out ->
                    out.bufferedWriter().use { w ->
                        w.write("\"Time (s)\",\"Acceleration x (m/s^2)\",\"Acceleration y (m/s^2)\",\"Acceleration z (m/s^2)\"\n")
                        for (i in 0 until series.size)
                            w.write("${series.time[i]},${series.x[i]},${series.y[i]},${series.z[i]}\n")
                    }
                }
                csvNames[id] = name
            }
        }
        // Only ids whose remote files really went are forgotten; a frozen pass keeps them for later.
        val released = HashSet<String>()
        for (id in plan.releasedIds) {
            val names = listOfNotNull("$id.bin".takeIf { it in remote }, state.csvNames[id]?.takeIf { it in remote })
            val ok = names.all { name -> runCatching { DocumentsContract.deleteDocument(context.contentResolver, remote.getValue(name).uri) }.getOrDefault(false) }
            if (ok)
                released.add(id)
        }
        mirrored.removeAll(released)
        for (id in released)
            csvNames.remove(id)
        if (plan.writeIndex)
            writeDocument(tree, remote, "index.json", "application/json") { it.write(indexBytes) }
        if (plan.writeSweeps)
            writeDocument(tree, remote, "sweeps.json", "application/json") { it.write(sweepsBytes) }

        // Every local dataset with a complete remote .bin counts as mirrored, whoever wrote it.
        mirrored = (mirrored + metas.map { it.id }.filter { id -> id in plan.binsToWrite || remote["$id.bin"]?.let { it.size < 0 || it.size == localSizes[id] } == true }).toMutableSet()
        val frozen = plan.foreignIds.isNotEmpty() || plan.frozenByDegradedIndex
        saveState(
            state.copy(
                mirrored = mirrored,
                csvNames = csvNames,
                indexHash = if (plan.writeIndex || !frozen) indexHash else state.indexHash,
                sweepsHash = if (plan.writeSweeps || !frozen) sweepsHash else state.sweepsHash,
                damaged = state.damaged.filter { "$it.bin" in remote }.toSet(),
            )
        )
        return MirrorOutcome(metas.count { it.id in mirrored }, plan.foreignIds.size, state.damaged.count { "$it.bin" in remote }, plan.frozenByDegradedIndex)
    }

    /** Dataset ids the folder's own index lists; empty when there is none or it cannot be read. */
    private fun remoteIndexIds(remote: Map<String, Child>): List<String> {
        val uri = remote["index.json"]?.uri ?: return emptyList()
        val text = runCatching { context.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull() ?: return emptyList()
        return runCatching { DatasetRepository.indexJson.decodeFromString<List<DatasetMeta>>(DatasetRepository.migrateIndex(text)).map { it.id } }
            .getOrDefault(emptyList())
            .filter { ID_SHAPE.matches(it) }
    }

    private companion object {
        /** The UUIDs this app generates; anything else in a folder's index is not ours to place in the sandbox. */
        val ID_SHAPE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }

    /**
     * Brings datasets and sweeps from the folder that this phone does not have.
     * Existing ids are left alone, so a restore after a reinstall or on a
     * second phone never duplicates anything. Returns how many datasets came in.
     */
    suspend fun restore(tree: Uri): RestoreResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val remote = listChildren(tree)
            val indexUri = remote["index.json"]?.uri ?: throw IllegalStateException("No index.json in that folder")
            val indexText = context.contentResolver.openInputStream(indexUri)?.use { String(it.readBytes(), Charsets.UTF_8) }
                ?: throw IllegalStateException("Could not read index.json")
            // Ids become file names inside the sandbox; only the shape this app generates is accepted.
            val metas = DatasetRepository.indexJson.decodeFromString<List<DatasetMeta>>(DatasetRepository.migrateIndex(indexText))
                .filter { ID_SHAPE.matches(it.id) }
            val sweeps = remote["sweeps.json"]?.let { f ->
                context.contentResolver.openInputStream(f.uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
            }?.let { runCatching { DatasetRepository.indexJson.decodeFromString<List<Sweep>>(it) }.getOrNull() } ?: emptyList()

            val have = repo.datasets.value.map { it.id }.toSet()
            val incoming = ArrayList<DatasetMeta>()
            val skipped = ArrayList<String>()
            val damaged = HashSet<String>()
            for (meta in metas) {
                if (meta.id in have)
                    continue
                val binUri = remote["${meta.id}.bin"]?.uri ?: continue
                val target = repo.datasetFile(meta.id)
                val tmp = File(target.path + ".restore")
                try {
                    context.contentResolver.openInputStream(binUri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                        ?: throw IllegalStateException("Could not read ${meta.id}.bin")
                    // Refuse a truncated or foreign file before it becomes a dataset.
                    DatasetRepository.validateSeriesFile(tmp)
                    if (!tmp.renameTo(target))
                        throw IllegalStateException("Could not place ${meta.id}.bin")
                    incoming.add(meta)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One bad file must not abandon the rest of the restore, nor freeze later passes.
                    skipped.add("${meta.name} (${e.message ?: e.javaClass.simpleName})")
                    damaged.add(meta.id)
                } finally {
                    tmp.delete()
                }
            }
            repo.adopt(incoming, sweeps)
            // The restored files are already in the folder, so they count as mirrored.
            val state = loadState()
            saveState(state.copy(mirrored = state.mirrored + incoming.map { it.id }, damaged = state.damaged + damaged))
            RestoreResult(incoming.size, skipped)
        }
    }

    private fun folderName(tree: Uri): String? {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst())
                return c.getString(0)
        }
        return null
    }

    private class Child(val uri: Uri, val size: Long)

    /** Child name to document uri and size (-1 when the provider does not say). */
    private fun listChildren(tree: Uri): MutableMap<String, Child> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val out = LinkedHashMap<String, Child>()
        val cursor = context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE),
            null, null, null,
        ) ?: throw IllegalStateException("The folder is not reachable; pick it again in Settings")
        cursor.use { c ->
            while (c.moveToNext()) {
                val size = if (c.isNull(2)) -1L else c.getLong(2)
                out[c.getString(1)] = Child(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), size)
            }
        }
        return out
    }

    /**
     * Overwrites an existing child of that name or creates it. A document
     * created here is removed again if its body fails, so a half-written file
     * never passes for a mirror; a copy cut short by process death is caught
     * by the size check on the next pass.
     */
    private fun writeDocument(tree: Uri, remote: MutableMap<String, Child>, name: String, mime: String, body: (java.io.OutputStream) -> Unit) {
        val existing = remote[name]?.uri
        val uri = existing ?: run {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            DocumentsContract.createDocument(context.contentResolver, parent, mime, name)
                ?: throw IllegalStateException("Could not create $name in the folder")
        }
        try {
            // "wt" truncates: a shorter index must not leave the old tail behind.
            val stream = context.contentResolver.openOutputStream(uri, if (existing != null) "wt" else "w")
                ?: throw IllegalStateException("Could not open $name for writing")
            stream.use(body)
        } catch (e: Exception) {
            if (existing == null)
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            throw e
        }
        remote[name] = Child(uri, -1L)
    }

    private fun csvName(meta: DatasetMeta): String {
        val safe = meta.name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { "dataset" }
        return "$safe-${meta.id.take(8)}.csv"
    }

    private fun loadState(): SyncState {
        if (!stateFile.exists())
            return SyncState()
        return runCatching { json.decodeFromString<SyncState>(String(AtomicFile(stateFile).readFully(), Charsets.UTF_8)) }.getOrDefault(SyncState())
    }

    private fun saveState(state: SyncState) {
        val atomic = AtomicFile(stateFile)
        val out = atomic.startWrite()
        try {
            out.write(json.encodeToString(state).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
}
