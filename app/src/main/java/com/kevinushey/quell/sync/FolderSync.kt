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
)

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
                    syncNow(Uri.parse(tree), app.syncCsv)
                }
        }
    }

    /** One mirror pass; safe to call from the UI's "Sync now". */
    suspend fun syncNow(tree: Uri, csv: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _status.value = _status.value.copy(syncing = true, folderName = folderName(tree))
            try {
                val (count, foreign) = mirror(tree, csv)
                _status.value = _status.value.copy(syncing = false, lastSyncEpochMs = System.currentTimeMillis(), mirroredCount = count, lastError = null, foreignCount = foreign)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = _status.value.copy(syncing = false, lastError = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Returns (datasets mirrored, datasets in the folder this phone lacks). */
    private fun mirror(tree: Uri, csv: Boolean): Pair<Int, Int> {
        val remote = listChildren(tree)
        val state = loadState()
        val metas = repo.datasets.value
        val indexText = DatasetRepository.indexJson.encodeToString(metas)
        val sweepsText = DatasetRepository.indexJson.encodeToString(repo.sweeps.value)
        val indexHash = sha1(indexText)
        val sweepsHash = sha1(sweepsText)
        val localIds = metas.map { it.id }.toSet()
        val foreign = remoteIndexIds(remote).filter { it !in localIds && "$it.bin" in remote.keys }.toSet()
        val plan = SyncPlan.plan(metas.map { it.id }, remote.keys, state, indexHash, sweepsHash, csv, foreign)

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
        for (name in plan.filesToDelete) {
            remote[name]?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } }
        }
        val gone = state.mirrored.filter { id -> metas.none { it.id == id } }
        mirrored.removeAll(gone.toSet())
        for (id in gone)
            csvNames.remove(id)
        if (plan.writeIndex)
            writeDocument(tree, remote, "index.json", "application/json") { it.write(indexText.toByteArray(Charsets.UTF_8)) }
        if (plan.writeSweeps)
            writeDocument(tree, remote, "sweeps.json", "application/json") { it.write(sweepsText.toByteArray(Charsets.UTF_8)) }

        // Every local dataset with a remote .bin counts as mirrored, whoever wrote it.
        mirrored = (mirrored + metas.map { it.id }.filter { "$it.bin" in remote.keys || it in plan.binsToWrite }).toMutableSet()
        saveState(SyncState(mirrored, csvNames, if (plan.writeIndex || foreign.isEmpty()) indexHash else state.indexHash, if (plan.writeSweeps || foreign.isEmpty()) sweepsHash else state.sweepsHash))
        return metas.count { it.id in mirrored } to foreign.size
    }

    /** Dataset ids the folder's own index lists; empty when there is none or it cannot be read. */
    private fun remoteIndexIds(remote: Map<String, Uri>): List<String> {
        val uri = remote["index.json"] ?: return emptyList()
        val text = runCatching { context.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull() ?: return emptyList()
        return runCatching { DatasetRepository.indexJson.decodeFromString<List<DatasetMeta>>(DatasetRepository.migrateIndex(text)).map { it.id } }.getOrDefault(emptyList())
    }

    /**
     * Brings datasets and sweeps from the folder that this phone does not have.
     * Existing ids are left alone, so a restore after a reinstall or on a
     * second phone never duplicates anything. Returns how many datasets came in.
     */
    suspend fun restore(tree: Uri): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val remote = listChildren(tree)
            val indexUri = remote["index.json"] ?: throw IllegalStateException("No index.json in that folder")
            val indexText = context.contentResolver.openInputStream(indexUri)?.use { String(it.readBytes(), Charsets.UTF_8) }
                ?: throw IllegalStateException("Could not read index.json")
            val metas = DatasetRepository.indexJson.decodeFromString<List<DatasetMeta>>(DatasetRepository.migrateIndex(indexText))
            val sweeps = remote["sweeps.json"]?.let { uri ->
                context.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
            }?.let { runCatching { DatasetRepository.indexJson.decodeFromString<List<Sweep>>(it) }.getOrNull() } ?: emptyList()

            val have = repo.datasets.value.map { it.id }.toSet()
            val incoming = ArrayList<DatasetMeta>()
            for (meta in metas) {
                if (meta.id in have)
                    continue
                val binUri = remote["${meta.id}.bin"] ?: continue
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
                } finally {
                    tmp.delete()
                }
            }
            repo.adopt(incoming, sweeps)
            // The restored files are already in the folder, so they count as mirrored.
            val state = loadState()
            saveState(state.copy(mirrored = state.mirrored + incoming.map { it.id }))
            incoming.size
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

    /** Child name to document uri. */
    private fun listChildren(tree: Uri): MutableMap<String, Uri> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val out = LinkedHashMap<String, Uri>()
        val cursor = context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        ) ?: throw IllegalStateException("The folder is not reachable; pick it again in Settings")
        cursor.use { c ->
            while (c.moveToNext())
                out[c.getString(1)] = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
        }
        return out
    }

    /** Overwrites an existing child of that name or creates it; the written uri replaces the map entry. */
    private fun writeDocument(tree: Uri, remote: MutableMap<String, Uri>, name: String, mime: String, body: (java.io.OutputStream) -> Unit) {
        val existing = remote[name]
        val uri = existing ?: run {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            DocumentsContract.createDocument(context.contentResolver, parent, mime, name)
                ?: throw IllegalStateException("Could not create $name in the folder")
        }
        // "wt" truncates: a shorter index must not leave the old tail behind.
        val stream = context.contentResolver.openOutputStream(uri, if (existing != null) "wt" else "w")
            ?: throw IllegalStateException("Could not open $name for writing")
        stream.use(body)
        remote[name] = uri
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

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
