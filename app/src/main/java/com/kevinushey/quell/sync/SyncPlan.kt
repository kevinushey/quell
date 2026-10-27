package com.kevinushey.quell.sync

import com.kevinushey.quell.data.DatasetMeta
import com.kevinushey.quell.data.Sweep
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/** What the mirror folder already holds, as written by this app. */
@Serializable
data class SyncState(
    /** Dataset ids whose samples this app has seen complete in the folder. */
    val mirrored: Set<String> = emptySet(),
    /** Dataset ids with a samples CSV in the folder, and the file name used. */
    val csvNames: Map<String, String> = emptyMap(),
    /**
     * The byte length of each run's CSV as this phone writes it. A name in
     * [csvNames] proves nothing about content (another phone may have written
     * it, or cut it short), so a folder copy is judged by its listed size.
     */
    val csvSizes: Map<String, Long> = emptyMap(),
    /**
     * Fingerprint of the details header heading each run's CSV, as this phone
     * last wrote or restored it. [csvSizes] describes the copy with this header.
     */
    val csvHeaders: Map<String, String> = emptyMap(),
    /** Content fingerprints of the last index.json, sweeps.json and runs.csv written. */
    val indexHash: String? = null,
    val sweepsHash: String? = null,
    val runsHash: String? = null,
    /** Fingerprints of an undecodable index.json / sweeps.json seen on the previous pass; seen twice, it is set aside. */
    val badIndexHash: String? = null,
    val badSweepsHash: String? = null,
    val badTombstonesHash: String? = null,
    /**
     * CSV files a pass started writing and has not yet seen through, file
     * name to dataset id: possibly partial, so never adopted and always
     * rewritten. Keyed by name, as one run can leave copies under two names.
     */
    val csvWriting: Map<String, String> = emptyMap(),
)

/** A file in the mirror folder: its size when the provider reports one, else -1. */
data class RemoteFile(val size: Long)

/**
 * The work one sync pass has to do; pure so it can be tested without a ContentResolver.
 * Each run's samples go to the folder as a CSV; folders from before that hold
 * a "<id>.bin" per run instead, which goes once the run's CSV is complete.
 */
class SyncPlan(
    /** Local runs whose samples CSV the folder lacks, or may hold only in part. */
    val csvToWrite: List<String>,
    /**
     * Local runs whose CSV is whole in the folder but headed by details that
     * have changed since: written again in place, under the recorded name.
     */
    val csvToRefresh: List<String>,
    /** Remote files to delete, grouped by the dataset id they belong to: only datasets the user deleted here. */
    val deletions: Map<String, List<String>>,
    /** Local runs with an old .bin in the folder and their CSV listed at exactly the expected size: the CSV replaces the .bin. */
    val binsToRetire: List<String>,
    val writeIndex: Boolean,
    val writeSweeps: Boolean,
    /** Datasets the folder's index lists that are not on this phone (and were not deleted here): a restore would bring them in. */
    val notOnThisPhone: Set<String>,
) {
    val filesToDelete: List<String> get() = deletions.values.flatten()
    val isEmpty: Boolean get() = csvToWrite.isEmpty() && csvToRefresh.isEmpty() && deletions.isEmpty() && binsToRetire.isEmpty() && !writeIndex && !writeSweeps

    /** What to do with a folder file that reads but does not decode. */
    enum class DamagedFile { ABORT_AND_REMEMBER, SET_ASIDE }

    companion object {
        /**
         * A damaged index or sweep list is set aside only when the very same
         * bytes were already seen damaged on the previous pass: a neighbour's
         * write in progress is not damage. The first sighting aborts the pass
         * and the caller must remember the fingerprint before aborting.
         */
        fun damagedFileAction(previousBadHash: String?, currentBadHash: String): DamagedFile =
            if (previousBadHash == currentBadHash) DamagedFile.SET_ASIDE else DamagedFile.ABORT_AND_REMEMBER

        /**
         * A local run's CSV already in the folder under another name (another
         * phone's copy of a run a restore renamed here) is taken over by the
         * "-<id8>.csv" suffix FolderSync.csvName ends every name with, rather
         * than written a second time; the samples behind it never change, and
         * a header this phone has no record of is refreshed in place.
         * A file under the very name this phone would write is not adopted:
         * it may be this phone's own copy cut short, which the write repairs.
         * Neither is a run whose CSV was in flight here, whatever its name.
         * [expectedNames] maps each local id to the name this phone would use.
         */
        fun adoptCsvNames(state: SyncState, expectedNames: Map<String, String>, remoteNames: Collection<String>): SyncState {
            val writing = state.csvWriting.values.toSet()
            val found = expectedNames.filterKeys { it !in state.csvNames && it !in writing }.mapNotNull { (id, expected) ->
                remoteNames.firstOrNull { it.endsWith("-${id.take(8)}.csv") && it != expected }?.let { id to it }
            }
            return if (found.isEmpty()) state else state.copy(csvNames = state.csvNames + found)
        }

        /**
         * The folder's samples CSVs by run id, names sorted. A name ending in a
         * run's "-<id8>.csv" suffix is that run's, unless another known run
         * shares those eight characters and the name could be either's.
         */
        fun csvFilesById(knownIds: Collection<String>, remoteNames: Collection<String>): Map<String, List<String>> {
            val owners = knownIds.toSet().groupBy { it.take(8) }
            val out = LinkedHashMap<String, MutableList<String>>()
            for (name in remoteNames.sorted()) {
                val match = CSV_NAME.matchEntire(name) ?: continue
                val owner = owners[match.groupValues[1]]?.singleOrNull() ?: continue
                out.getOrPut(owner) { ArrayList() }.add(name)
            }
            return out
        }

        private val CSV_NAME = Regex(".*-([0-9a-fA-F]{8})\\.csv")

        /**
         * Each listed run's samples CSV, for runs.csv: the name this phone
         * recorded, else the one its suffix points to (another phone's copy).
         */
        fun runFiles(runIds: List<String>, csvNames: Map<String, String>, remoteNames: Collection<String>, knownIds: Collection<String>): Map<String, String> {
            val byId = csvFilesById(knownIds, remoteNames)
            val present = remoteNames.toSet()
            val out = LinkedHashMap<String, String>()
            for (id in runIds) {
                val file = csvNames[id]?.takeIf { it in present } ?: byId[id]?.firstOrNull() ?: continue
                out[id] = file
            }
            return out
        }

        /** A folder file the pass regenerates is written when its content changed, or the folder's copy is missing or the wrong size. */
        fun needsWrite(existing: RemoteFile?, hash: String, size: Long, lastHash: String?): Boolean {
            if (existing == null)
                return true
            return hash != lastHash || (existing.size >= 0 && existing.size != size)
        }

        /**
         * Unfinished CSVs under a name this phone will not write again for that
         * run (renamed since): possibly partial and otherwise never replaced,
         * so the pass removes them. Another phone that wrote the same name has
         * it in its own record and writes it again. A run missing from the
         * local list is left alone: absence proves nothing, and the file may
         * be the folder's only copy. A run deleted here loses its files anyway.
         */
        fun staleCsvWrites(state: SyncState, expectedNames: Map<String, String>): List<String> =
            state.csvWriting.filter { (name, id) -> id in expectedNames && name != expectedNames[id] }.keys.toList()

        /**
         * The state to plan from: unfinished entries under a name the run no
         * longer uses and that nothing records are left out, as the stale
         * delete handles them; one that would not delete must not force the
         * run's whole CSV to be uploaded again on every pass.
         */
        fun forPlanning(state: SyncState, expectedNames: Map<String, String>): SyncState =
            state.copy(csvWriting = state.csvWriting.filter { (name, id) -> name == expectedNames[id] || name == state.csvNames[id] })

        /**
         * Up to [maxAttempts] delayed passes after a transient folder failure,
         * stopping at the first that does not ask for another. True when every
         * attempt still asked for one: the caller hands over to Sync now.
         */
        suspend fun retryPasses(maxAttempts: Int, delayMs: Long, pass: suspend () -> Boolean): Boolean {
            repeat(maxAttempts) {
                delay(delayMs)
                if (!pass())
                    return false
            }
            return true
        }

        /**
         * The folder's index is a union: this phone's entries, then every entry
         * the folder already listed that is neither here nor deleted here. An
         * entry whose samples are missing from the folder is carried too; restore
         * skips it and the status does not count it, but dropping it would
         * forget a dataset that may still be on its way.
         */
        fun mergeIndex(local: List<DatasetMeta>, remote: List<DatasetMeta>, deletedIds: Set<String>): List<DatasetMeta> {
            val localIds = local.map { it.id }.toSet()
            // A local run tombstoned on another phone stays here but leaves the folder's index, so both phones write the same file.
            return local.filter { it.id !in deletedIds } + remote.filter { it.id !in localIds && it.id !in deletedIds }
        }

        fun mergeSweeps(local: List<Sweep>, remote: List<Sweep>, deletedIds: Set<String>): List<Sweep> {
            val localIds = local.map { it.id }.toSet()
            return local.filter { it.id !in deletedIds } + remote.filter { it.id !in localIds && it.id !in deletedIds }
        }

        /**
         * The folder's index may only lose entries the user deleted here. Any
         * other shrink means our picture of the folder is wrong (an unreadable
         * index, a transient provider failure) and the write must not happen.
         */
        fun indexWriteIsSafe(mergedIds: Collection<String>, remoteIds: Collection<String>, deletedIds: Set<String>): Boolean {
            val merged = mergedIds.toSet()
            return remoteIds.all { it in merged || it in deletedIds }
        }

        /**
         * Samples never change, so one CSV per run is enough; a missing one, or
         * one a pass may have cut short, is written again. The details header
         * at its top does change: a whole copy whose header fingerprint is not
         * the one [headers] gives for the run now is refreshed in place, so a
         * renamed run keeps a single CSV. A run with no entry in [headers] is
         * not judged by it. Only datasets the
         * user deleted on this phone are ever deleted from the folder: absence
         * from the local list proves nothing (the index may have failed to
         * load), so it is never read as a deletion. The folder's index is the
         * union of what is here and what the folder already listed, minus
         * those deletions, so a phone that lost entries, or a second phone,
         * cannot thin it out.
         *
         * A recorded CSV listed smaller than [SyncState.csvSizes] says was cut
         * short and is written again; one listed larger is left alone, as
         * another phone whose number formatting differs may have written it,
         * and rewriting that would only start the two phones trading copies.
         * An old .bin goes only once the folder lists its run's CSV at exactly
         * the expected size.
         */
        fun plan(
            localIds: List<String>,
            remote: Map<String, RemoteFile>,
            remoteIndexIds: List<String>,
            deletedIds: Set<String>,
            state: SyncState,
            headers: Map<String, String>,
            indexHash: String,
            indexSize: Long,
            sweepsHash: String,
            sweepsSize: Long,
        ): SyncPlan {
            val local = localIds.toSet()
            val csvFiles = csvFilesById(local + remoteIndexIds + deletedIds, remote.keys)

            // A run with any copy a pass was still writing when it died (under its recorded name or its current one)
            // may have a partial file in the folder: its CSV is written again. A run deleted on another phone (a
            // folder tombstone) is kept here but never uploaded again.
            val unfinished = state.csvWriting.values.toSet()
            fun listedCsv(id: String): RemoteFile? = state.csvNames[id]?.let { remote[it] }?.takeIf { id !in unfinished }
            val csvs = localIds.filter { id ->
                val listed = listedCsv(id)
                val expected = state.csvSizes[id]
                id !in deletedIds && (listed == null || (expected != null && listed.size in 0 until expected))
            }
            val refresh = localIds.filter { id ->
                val header = headers[id]
                id !in deletedIds && id !in csvs && listedCsv(id) != null && header != null && header != state.csvHeaders[id]
            }

            val retire = localIds.filter { id ->
                val listed = listedCsv(id)
                "$id.bin" in remote && id !in deletedIds && listed != null && listed.size >= 0 && listed.size == state.csvSizes[id]
            }

            val deletions = LinkedHashMap<String, List<String>>()
            for (id in deletedIds) {
                if (id in local)
                    continue
                val recorded = listOfNotNull("$id.bin".takeIf { it in remote }, state.csvNames[id]?.takeIf { it in remote })
                // Another phone's copy is found by its suffix, so a run deleted here leaves no CSV behind.
                val names = (recorded + csvFiles[id].orEmpty()).distinct()
                if (names.isNotEmpty())
                    deletions[id] = names
            }

            val elsewhere = remoteIndexIds.filter { it !in local && it !in deletedIds && ("$it.bin" in remote || it in csvFiles) }.toSet()
            return SyncPlan(
                csvToWrite = csvs,
                csvToRefresh = refresh,
                deletions = deletions,
                binsToRetire = retire,
                writeIndex = needsWrite(remote["index.json"], indexHash, indexSize, state.indexHash),
                writeSweeps = needsWrite(remote["sweeps.json"], sweepsHash, sweepsSize, state.sweepsHash),
                notOnThisPhone = elsewhere,
            )
        }
    }
}
