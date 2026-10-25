package com.kevinushey.quell.sync

import com.kevinushey.quell.data.DatasetMeta
import com.kevinushey.quell.data.Sweep
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/** What the mirror folder already holds, as written by this app. */
@Serializable
data class SyncState(
    /** Dataset ids whose .bin (and, if enabled, .csv) this app has written to the folder. */
    val mirrored: Set<String> = emptySet(),
    /** Dataset ids with a CSV copy in the folder, and the file name used. */
    val csvNames: Map<String, String> = emptyMap(),
    /** Content fingerprints of the last index.json and sweeps.json written. */
    val indexHash: String? = null,
    val sweepsHash: String? = null,
    /** Fingerprints of an undecodable index.json / sweeps.json seen on the previous pass; seen twice, it is set aside. */
    val badIndexHash: String? = null,
    val badSweepsHash: String? = null,
    val badTombstonesHash: String? = null,
)

/** A file in the mirror folder: its size when the provider reports one, else -1. */
data class RemoteFile(val size: Long)

/** The work one sync pass has to do; pure so it can be tested without a ContentResolver. */
class SyncPlan(
    val binsToWrite: List<String>,
    val csvToWrite: List<String>,
    /** Remote files to delete, grouped by the dataset id they belong to: only datasets the user deleted here. */
    val deletions: Map<String, List<String>>,
    val writeIndex: Boolean,
    val writeSweeps: Boolean,
    /** Datasets the folder's index lists that are not on this phone (and were not deleted here): a restore would bring them in. */
    val notOnThisPhone: Set<String>,
) {
    val filesToDelete: List<String> get() = deletions.values.flatten()
    val isEmpty: Boolean get() = binsToWrite.isEmpty() && csvToWrite.isEmpty() && deletions.isEmpty() && !writeIndex && !writeSweeps

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
         * than written a second time; the content is the same immutable .bin.
         * A file under the very name this phone would write is not adopted:
         * it may be this phone's own copy cut short, which the write repairs.
         * [expectedNames] maps each local id to the name this phone would use.
         */
        fun adoptCsvNames(state: SyncState, expectedNames: Map<String, String>, remoteNames: Collection<String>): SyncState {
            val found = expectedNames.filterKeys { it !in state.csvNames }.mapNotNull { (id, expected) ->
                remoteNames.firstOrNull { it.endsWith("-${id.take(8)}.csv") && it != expected }?.let { id to it }
            }
            return if (found.isEmpty()) state else state.copy(csvNames = state.csvNames + found)
        }

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
         * entry whose .bin is missing from the folder is carried too; restore
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
         * Bins are immutable, so one copy per id is enough; a missing remote
         * file, or one whose size disagrees with the local file (a copy cut
         * short), is written again. Only datasets the user deleted on this
         * phone are ever deleted from the folder: absence from the local list
         * proves nothing (the index may have failed to load), so it is never
         * read as a deletion. The folder's index is the union of what is here
         * and what the folder already listed, minus those deletions, so a
         * phone that lost entries, or a second phone, cannot thin it out.
         */
        fun plan(
            localIds: List<String>,
            localBinSizes: Map<String, Long>,
            remote: Map<String, RemoteFile>,
            remoteIndexIds: List<String>,
            deletedIds: Set<String>,
            state: SyncState,
            indexHash: String,
            indexSize: Long,
            sweepsHash: String,
            sweepsSize: Long,
            csv: Boolean,
        ): SyncPlan {
            val local = localIds.toSet()
            fun complete(name: String, expected: Long?): Boolean {
                val r = remote[name] ?: return false
                return expected == null || r.size < 0 || r.size == expected
            }
            // A run deleted on another phone (a folder tombstone) is kept here but never uploaded again.
            val bins = localIds.filter { it !in deletedIds && !complete("$it.bin", localBinSizes[it]) }
            val csvs = if (csv) localIds.filter { id -> id !in deletedIds && (state.csvNames[id]?.let { it !in remote } ?: true) } else emptyList()
            val deletions = LinkedHashMap<String, List<String>>()
            for (id in deletedIds) {
                if (id in local)
                    continue
                val names = listOfNotNull("$id.bin".takeIf { it in remote }, state.csvNames[id]?.takeIf { it in remote })
                if (names.isNotEmpty())
                    deletions[id] = names
            }
            val elsewhere = remoteIndexIds.filter { it !in local && it !in deletedIds && "$it.bin" in remote }.toSet()
            return SyncPlan(
                binsToWrite = bins,
                csvToWrite = csvs,
                deletions = deletions,
                writeIndex = indexHash != state.indexHash || !complete("index.json", indexSize),
                writeSweeps = sweepsHash != state.sweepsHash || !complete("sweeps.json", sweepsSize),
                notOnThisPhone = elsewhere,
            )
        }
    }
}
