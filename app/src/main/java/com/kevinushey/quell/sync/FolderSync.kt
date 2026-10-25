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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    val restoring: Boolean = false,
    val lastSyncEpochMs: Long? = null,
    val mirroredCount: Int = 0,
    val lastError: String? = null,
    /** Datasets the folder lists that are not on this phone; "Restore from folder" brings them in. */
    val notOnThisPhone: Int = 0,
    /** This phone's index could not be fully read; the folder keeps its own entries, so nothing is lost there. */
    val indexDegraded: Boolean = false,
    /** Runs on this phone that were deleted on another phone sharing the folder; kept here, not mirrored. */
    val deletedElsewhere: Int = 0,
)

/** What a restore did, for the caller to report. */
data class RestoreResult(val restored: Int, val skipped: List<String>, val sweeps: Int = 0)

/**
 * Mirrors the dataset store into a folder the user picked through the system
 * picker (Google Drive, Dropbox, a local folder: anything with a documents
 * provider). The provider does the uploading, so no account or API key is
 * involved here. Runs while the app is open: that is when data changes.
 *
 * The folder's index is a union: what this phone has plus what the folder
 * already listed, minus datasets the user deleted here. Only those explicit
 * deletions ever remove a file, so a phone whose own index failed to load
 * cannot thin the folder out.
 */
class FolderSync(private val context: Context, private val repo: DatasetRepository, private val settings: SettingsStore) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stateFile = File(context.filesDir, "sync-state.json")
    private val mutex = Mutex()
    private var scope: CoroutineScope? = null
    private var retry: Job? = null

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status

    /**
     * One-line outcomes of user-requested work (restore), for the snackbar.
     * Replayed, because a restore outlives the screen that asked for it; the
     * consumer calls [eventsShown] once it has passed them on.
     */
    private val _events = MutableSharedFlow<String>(replay = 4, extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun eventsShown() = _events.resetReplayCache()

    /** Watches the store and the settings; a change schedules a pass a few seconds later. */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        this.scope = scope
        scope.launch {
            repo.load()
            // Only the sync fields matter here; an analysis-settings edit must not schedule a pass.
            val syncSettings = settings.app.map { Triple(it.syncTreeUri, it.syncEnabled, it.syncCsv) }.distinctUntilChanged()
            // Deletions change datasets or sweeps too, so deletedIds need not be an input of its own.
            combine(repo.datasets, repo.sweeps, syncSettings) { _, _, s -> s }
                .debounce(3_000)
                .collect { (treeUri, enabled, csv) ->
                    // A pending retry belongs to the settings it was scheduled under.
                    retry?.cancel()
                    val tree = treeUri?.takeIf { enabled } ?: run {
                        // A restore may still be running: "Restore from folder" does not need mirroring on.
                        _status.update { SyncStatus(restoring = it.restoring) }
                        return@collect
                    }
                    // Nothing may end this loop, or the process: a bad grant is a status line, not a crash.
                    try {
                        syncNow(Uri.parse(tree), csv)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _status.update { it.copy(syncing = false, lastError = e.message ?: e.javaClass.simpleName) }
                    }
                }
        }
    }

    /** "Sync now": runs on the long-lived scope, so backing out of the app does not cut it short. */
    fun requestSync(tree: Uri, csv: Boolean) {
        val s = scope ?: return
        s.launch { syncNow(tree, csv) }
    }

    /** "Restore from folder": result arrives on [events]. A restore already under way is not queued twice. */
    fun requestRestore(tree: Uri) {
        val s = scope ?: return
        if (_status.value.restoring)
            return
        _status.update { it.copy(restoring = true) }
        s.launch {
            val message = try {
                val result = restore(tree)
                buildString {
                    val parts = listOfNotNull(
                        result.restored.takeIf { it > 0 }?.let { "$it dataset${if (it == 1) "" else "s"}" },
                        result.sweeps.takeIf { it > 0 }?.let { "$it sweep${if (it == 1) "" else "s"}" },
                    )
                    append(if (parts.isEmpty()) "Nothing new in the folder" else "Restored ${parts.joinToString(" and ")}")
                    if (result.skipped.isNotEmpty())
                        append("; ${result.skipped.size} could not be read: ${result.skipped.joinToString()}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RetryLater) {
                "Could not restore from the folder yet: ${e.reason}. Try again in a few seconds."
            } catch (e: Exception) {
                "Could not restore from the folder: ${e.message ?: e.javaClass.simpleName}"
            }
            _events.emit(message)
        }
    }

    /** One mirror pass; a transient folder failure gets a few more passes shortly after. */
    suspend fun syncNow(tree: Uri, csv: Boolean) {
        if (pass(tree, csv) != null)
            scheduleRetry(tree)
    }

    /** The mirror pass itself: why it should run again soon, or null when it should not. */
    private suspend fun pass(tree: Uri, csv: Boolean): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            _status.update { it.copy(syncing = true) }
            try {
                // The name lookup touches the grant too; a revoked folder must land in lastError like anything else.
                _status.update { it.copy(folderName = folderName(tree)) }
                val outcome = mirror(tree, csv)
                _status.update {
                    it.copy(
                        lastSyncEpochMs = System.currentTimeMillis(),
                        mirroredCount = outcome.mirrored,
                        lastError = null,
                        notOnThisPhone = outcome.notOnThisPhone,
                        indexDegraded = repo.degraded.value,
                        deletedElsewhere = outcome.deletedElsewhere,
                    )
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: RetryLater) {
                _status.update { it.copy(lastError = e.message) }
                e.reason
            } catch (e: Exception) {
                _status.update { it.copy(lastError = e.message ?: e.javaClass.simpleName) }
                null
            } finally {
                _status.update { it.copy(syncing = false) }
            }
        }
    }

    /** A transient folder failure (listing still loading, content not yet downloaded) gets another pass soon. */
    private class RetryLater(val reason: String) : IllegalStateException("$reason; another pass runs shortly.")

    /**
     * A few delayed passes from one job, each checked against the settings as
     * they are when it fires: a folder the user has since left or stopped
     * mirroring is never written to by a stale retry. The passes run here
     * rather than through [syncNow], so a retry never waits on its own job.
     */
    private fun scheduleRetry(tree: Uri) {
        val s = scope ?: return
        if (retry?.isActive == true)
            return
        retry = s.launch {
            var reason: String? = null
            val exhausted = SyncPlan.retryPasses(MAX_RETRIES, RETRY_DELAY_MS) {
                val app = settings.app.first()
                if (!app.syncEnabled || app.syncTreeUri != tree.toString())
                    return@retryPasses false
                reason = pass(tree, app.syncCsv)
                reason != null
            }
            if (exhausted)
                _status.update { it.copy(lastError = "$reason. Use Sync now to try again.") }
        }
    }

    private class MirrorOutcome(val mirrored: Int, val notOnThisPhone: Int, val deletedElsewhere: Int)

    private suspend fun mirror(tree: Uri, csv: Boolean): MirrorOutcome {
        val remote = listChildren(tree)
        val metas = repo.datasets.value
        val localIds = metas.map { it.id }.toSet()
        val expectedCsv = metas.associate { it.id to csvName(it) }
        val state = SyncPlan.adoptCsvNames(loadState(), expectedCsv, remote.keys)

        // The folder's index carries forward entries this phone does not have, unless they were deleted here.
        // A damaged index or sweep list is set aside once it has been seen damaged twice (so a neighbour's
        // write in progress is not mistaken for damage); until then the pass stops before writing anything.
        val index = readRemoteIndex(remote)
        val sweepsRead = readRemoteSweeps(remote)
        // The fingerprints of what would not decode are saved before any abort, so the next pass can act on them.
        val seen = state.copy(badIndexHash = index.badHash, badSweepsHash = sweepsRead.badHash)
        if (seen != state)
            saveState(seen)
        val remoteIndex: List<DatasetMeta> = index.items ?: damagedOrAside(remote, "index.json", state.badIndexHash, index.badHash!!)
        val remoteSweeps: List<Sweep> = sweepsRead.items ?: damagedOrAside(remote, "sweeps.json", state.badSweepsHash, sweepsRead.badHash!!)
        // Tombstones live in the folder too, so a deletion made on one phone is not undone by another.
        val tombstonesRead = readRemoteTombstones(remote)
        val seenAll = seen.copy(badTombstonesHash = tombstonesRead.badHash)
        if (seenAll != seen)
            saveState(seenAll)
        val remoteTombstones: Set<String> = (tombstonesRead.items ?: damagedOrAside(remote, "deleted.json", state.badTombstonesHash, tombstonesRead.badHash!!)).toSet()
        val deleted = remoteTombstones + repo.deletedIds.value
        val mergedIndex = SyncPlan.mergeIndex(metas, remoteIndex, deleted)
        val mergedSweeps = SyncPlan.mergeSweeps(repo.sweeps.value, remoteSweeps, deleted)
        val indexBytes = DatasetRepository.indexJson.encodeToString(mergedIndex).toByteArray(Charsets.UTF_8)
        val sweepsBytes = DatasetRepository.indexJson.encodeToString(mergedSweeps).toByteArray(Charsets.UTF_8)
        val tombstoneBytes = DatasetRepository.indexJson.encodeToString(deleted.sorted()).toByteArray(Charsets.UTF_8)
        val indexHash = sha1(indexBytes)
        val sweepsHash = sha1(sweepsBytes)

        // Belt and braces: a merged index can only be shorter than the folder's by what was deleted here.
        // Should it ever fail, bins and deletions still proceed; only the index writes are withheld.
        val indexWriteSafe = SyncPlan.indexWriteIsSafe(mergedIndex.map { it.id }, remoteIndex.map { it.id }, deleted)

        // A CSV a cut-short pass left under a name this phone no longer writes would otherwise stay partial for good.
        for (name in SyncPlan.staleCsvWrites(state, expectedCsv)) {
            val child = remote[name] ?: continue
            if (runCatching { DocumentsContract.deleteDocument(context.contentResolver, child.uri) }.getOrDefault(false))
                remote.remove(name)
        }

        val localSizes = metas.associate { it.id to repo.datasetFile(it.id).length() }
        val plan = SyncPlan.plan(
            metas.map { it.id },
            localSizes,
            remote.mapValues { RemoteFile(it.value.size) },
            remoteIndex.map { it.id },
            deleted,
            state,
            indexHash,
            indexBytes.size.toLong(),
            sweepsHash,
            sweepsBytes.size.toLong(),
            csv,
        )

        val mirrored = state.mirrored.toMutableSet()
        val csvNames = state.csvNames.toMutableMap()
        for (id in plan.binsToWrite) {
            val local = repo.datasetFile(id)
            if (!local.exists())
                continue
            writeDocument(tree, remote, "$id.bin", "application/octet-stream") { out -> local.inputStream().use { it.copyTo(out) } }
            mirrored.add(id)
        }
        val csvWritten = HashSet<String>()
        if (csv && plan.csvToWrite.isNotEmpty()) {
            // Recorded before streaming: should the pass die mid-file, the next one knows the copy may be partial.
            saveState(seenAll.copy(csvWriting = seenAll.csvWriting + plan.csvToWrite.mapNotNull { id -> expectedCsv[id]?.let { it to id } }))
            for (id in plan.csvToWrite) {
                val file = repo.datasetFile(id).takeIf { it.exists() } ?: continue
                val name = expectedCsv[id] ?: continue
                // Streamed row by row from the .bin: a long run must not be loaded into the heap for this.
                writeDocument(tree, remote, name, "text/csv") { out ->
                    out.bufferedWriter().use { w ->
                        w.write("\"Time (s)\",\"Acceleration x (m/s^2)\",\"Acceleration y (m/s^2)\",\"Acceleration z (m/s^2)\"\n")
                        DatasetRepository.streamSeries(file) { t, x, y, z -> w.write("$t,$x,$y,$z\n") }
                    }
                }
                csvNames[id] = name
                csvWritten.add(name)
            }
        }
        // A deletion is forgotten only once every file of that dataset has really gone.
        val released = HashSet<String>()
        for ((id, names) in plan.deletions) {
            val ok = names.all { name -> runCatching { DocumentsContract.deleteDocument(context.contentResolver, remote.getValue(name).uri) }.getOrDefault(false) }
            if (ok) {
                released.add(id)
                for (name in names)
                    remote.remove(name)
            }
        }
        mirrored.removeAll(released)
        for (id in released)
            csvNames.remove(id)
        // A local tombstone is forgotten only once the folder's own list carries it (read back on this pass, so a
        // neighbour's whole-file write cannot have dropped it unseen) and its files are gone.
        val settled = repo.deletedIds.value.filter { id ->
            id in remoteTombstones && (id in released || ("$id.bin" !in remote && state.csvNames[id]?.let { it in remote } != true))
        }
        if (plan.writeIndex && indexWriteSafe)
            writeDocument(tree, remote, "index.json", "application/json") { it.write(indexBytes) }
        if (plan.writeSweeps && indexWriteSafe)
            writeDocument(tree, remote, "sweeps.json", "application/json") { it.write(sweepsBytes) }
        if (deleted != remoteTombstones || ("deleted.json" !in remote && deleted.isNotEmpty()))
            writeDocument(tree, remote, "deleted.json", "application/json") { it.write(tombstoneBytes) }

        // Every local dataset with a complete remote .bin counts as mirrored, whoever wrote it; a run deleted
        // on another phone is not mirrored by definition.
        for (meta in metas) {
            val r = remote["${meta.id}.bin"]
            if (meta.id in plan.binsToWrite || (r != null && (r.size < 0 || r.size == localSizes[meta.id])))
                mirrored.add(meta.id)
        }
        mirrored.removeAll(remoteTombstones)
        saveState(
            SyncState(
                mirrored,
                csvNames,
                if (indexWriteSafe) indexHash else state.indexHash,
                if (indexWriteSafe) sweepsHash else state.sweepsHash,
                badIndexHash = null,
                badSweepsHash = null,
                badTombstonesHash = null,
                // Settled once written through on this pass, or gone; anything else may still be partial.
                csvWriting = state.csvWriting.filterKeys { it in remote && it !in csvWritten },
            )
        )
        if (settled.isNotEmpty())
            repo.forgetDeleted(settled.toSet())
        if (!indexWriteSafe)
            throw IllegalStateException("The folder's index lists datasets this pass cannot account for; files were mirrored but the index was left alone")
        return MirrorOutcome(metas.count { it.id in mirrored }, plan.notOnThisPhone.size, metas.count { it.id in remoteTombstones && it.id !in repo.deletedIds.value })
    }

    /** Ids deleted on any phone sharing the folder; read like the index, so a damaged list never reads as empty. */
    private fun readRemoteTombstones(remote: Map<String, Child>): RemoteList<String> {
        val uri = remote["deleted.json"]?.uri ?: return RemoteList(emptyList(), null)
        val bytes = readBytes(uri, "deleted.json")
        return runCatching {
            DatasetRepository.indexJson.decodeFromString<List<String>>(String(bytes, Charsets.UTF_8)).filter { ID_SHAPE.matches(it) }
        }.fold({ RemoteList(it, null) }, { RemoteList(null, sha1(bytes)) })
    }

    /** A damaged folder file: set aside on its second sighting (then treated as absent), otherwise the pass stops here. */
    private fun <T> damagedOrAside(remote: MutableMap<String, Child>, name: String, previousBadHash: String?, badHash: String): List<T> {
        when (SyncPlan.damagedFileAction(previousBadHash, badHash)) {
            SyncPlan.DamagedFile.ABORT_AND_REMEMBER ->
                throw IllegalStateException("The folder's $name is damaged (not valid JSON); nothing was written. If it is still damaged on the next pass it will be set aside as ${name.replace(".json", ".broken-*.json")}.")
            SyncPlan.DamagedFile.SET_ASIDE -> {
                if (!setAside(remote, name))
                    throw IllegalStateException("The folder's $name is damaged and this folder does not allow renaming it; remove or fix $name by hand.")
                return emptyList()
            }
        }
    }

    /** Renames a damaged folder file out of the way, as the local store does with its own index. */
    private fun setAside(remote: MutableMap<String, Child>, name: String): Boolean {
        val child = remote[name] ?: return true
        val kept = name.replace(".json", ".broken-${System.currentTimeMillis()}.json")
        val renamed = runCatching { DocumentsContract.renameDocument(context.contentResolver, child.uri, kept) }.getOrNull() ?: return false
        remote.remove(name)
        remote[kept] = Child(renamed, child.size)
        return true
    }

    /** A folder JSON file: its decoded items, or null items plus the fingerprint of the bytes that would not decode. */
    private class RemoteList<T>(val items: List<T>?, val badHash: String?)

    /**
     * The folder's own index: empty only when the folder has none. A file that
     * is there but cannot be read right now (provider offline, content not yet
     * downloaded) throws, so the pass stops before it can write an index that
     * forgets those entries; one that reads but does not decode is reported
     * as damaged for the caller to decide. Only UUID-shaped ids are trusted.
     */
    private fun readRemoteIndex(remote: Map<String, Child>): RemoteList<DatasetMeta> {
        val uri = remote["index.json"]?.uri ?: return RemoteList(emptyList(), null)
        val bytes = readBytes(uri, "index.json")
        return runCatching {
            DatasetRepository.indexJson.decodeFromString<List<DatasetMeta>>(DatasetRepository.migrateIndex(String(bytes, Charsets.UTF_8))).filter { ID_SHAPE.matches(it.id) }
        }.fold({ RemoteList(it, null) }, { RemoteList(null, sha1(bytes)) })
    }

    private fun readRemoteSweeps(remote: Map<String, Child>): RemoteList<Sweep> {
        val uri = remote["sweeps.json"]?.uri ?: return RemoteList(emptyList(), null)
        val bytes = readBytes(uri, "sweeps.json")
        return runCatching {
            DatasetRepository.indexJson.decodeFromString<List<Sweep>>(String(bytes, Charsets.UTF_8)).filter { ID_SHAPE.matches(it.id) }
        }.fold({ RemoteList(it, null) }, { RemoteList(null, sha1(bytes)) })
    }

    private fun readBytes(uri: Uri, name: String): ByteArray {
        // A provider streaming the download can also fail partway through: just as transient as failing to open.
        try {
            val stream = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("no stream")
            return stream.use { it.readBytes() }
        } catch (e: Exception) {
            throw RetryLater("The folder's $name could not be read right now")
        }
    }

    private companion object {
        /** The UUIDs this app generates; anything else in a folder's index is not ours to place in the sandbox. */
        val ID_SHAPE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        const val RETRY_DELAY_MS = 15_000L
        const val MAX_RETRIES = 3
    }

    /**
     * Brings datasets and sweeps from the folder that this phone does not have.
     * Existing ids and ids deleted on this phone are left alone, so a restore
     * after a reinstall or on a second phone never duplicates or resurrects
     * anything. Files that cannot be read now stay in the folder for a later
     * try; nothing marks them off.
     */
    suspend fun restore(tree: Uri): RestoreResult = withContext(Dispatchers.IO) {
        // Busy from the moment it is asked for, including the wait for a running mirror pass.
        _status.update { it.copy(restoring = true) }
        try {
            mutex.withLock { restoreLocked(tree) }
        } finally {
            _status.update { it.copy(restoring = false) }
        }
    }

    private suspend fun restoreLocked(tree: Uri): RestoreResult {
            val remote = listChildren(tree)
            if ("index.json" !in remote)
                throw IllegalStateException("No index.json in that folder")
            val metas = readRemoteIndex(remote).items
                ?: throw IllegalStateException("The folder's index.json is damaged (not valid JSON). The datasets' files are still in the folder; with mirroring on, the file is set aside after two passes, or fix it by hand.")
            // A damaged sweep list must not stand between the user and their datasets.
            val sweepsRead = readRemoteSweeps(remote)
            val sweeps = sweepsRead.items ?: emptyList()
            val have = repo.datasets.value.map { it.id }.toSet()
            val tombstones = readRemoteTombstones(remote).items
                ?: throw IllegalStateException("The folder's deleted.json is damaged (not valid JSON); restore would not know what was deleted. With mirroring on it is set aside after two passes, or fix it by hand.")
            val deleted = repo.deletedIds.value + tombstones
            val incoming = ArrayList<DatasetMeta>()
            val skipped = ArrayList<String>()
            if (sweepsRead.items == null)
                skipped.add("sweeps.json (damaged, sweeps not restored)")
            var sweepsAdded = 0
            try {
                for (meta in metas) {
                    if (meta.id in have || meta.id in deleted)
                        continue
                    val remoteBin = remote["${meta.id}.bin"] ?: continue
                    val target = repo.datasetFile(meta.id)
                    val tmp = File(target.path + ".restore")
                    try {
                        // A lost index leaves the files behind; one that is intact (and the size the folder reports) needs no download.
                        val onDisk = target.exists() && runCatching { DatasetRepository.validateSeriesFile(target) }.isSuccess &&
                            (remoteBin.size < 0 || remoteBin.size == target.length())
                        if (!onDisk) {
                            context.contentResolver.openInputStream(remoteBin.uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                                ?: throw IllegalStateException("could not be read")
                            // Refuse a truncated, foreign or oversized file before it becomes a dataset.
                            DatasetRepository.validateSeriesFile(tmp)
                            if (!tmp.renameTo(target))
                                throw IllegalStateException("could not be placed")
                        }
                        incoming.add(meta)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // One bad or unreachable file must not abandon the rest of the restore.
                        skipped.add("${meta.name} (${e.message ?: e.javaClass.simpleName})")
                    } finally {
                        tmp.delete()
                    }
                }
            } finally {
                // Whatever was copied in is registered, even if the loop was cut short.
                withContext(NonCancellable) {
                    if (incoming.isNotEmpty() || sweeps.isNotEmpty())
                        sweepsAdded = repo.adopt(incoming, sweeps.filter { it.id !in deleted })
                    if (incoming.isNotEmpty()) {
                        val state = loadState()
                        saveState(state.copy(mirrored = state.mirrored + incoming.map { it.id }))
                    }
                    // A complete restore answers the lost-index warning; a partial one does not.
                    if (incoming.isNotEmpty() && skipped.isEmpty())
                        repo.indexRestored()
                }
            }
            return RestoreResult(incoming.size, skipped, sweepsAdded)
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
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        ) ?: throw IllegalStateException("The folder is not reachable; pick it again in Settings")
        cursor.use { c ->
            // A cloud provider may answer with part of the folder while it fetches the rest; planning on that
            // would create duplicates and forget deletions. Wait for the next pass instead.
            if (c.extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) == true)
                throw RetryLater("The folder listing is still loading")
            val modified = HashMap<String, Long>()
            while (c.moveToNext()) {
                val name = c.getString(1)
                val size = if (c.isNull(2)) -1L else c.getLong(2)
                val stamp = if (c.isNull(3)) 0L else c.getLong(3)
                // Providers that allow duplicate names: the newest one is the live copy.
                if (name in out && modified.getValue(name) >= stamp)
                    continue
                modified[name] = stamp
                out[name] = Child(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), size)
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
