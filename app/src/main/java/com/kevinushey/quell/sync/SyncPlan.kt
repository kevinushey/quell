package com.kevinushey.quell.sync

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

    companion object {
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
            val bins = localIds.filter { !complete("$it.bin", localBinSizes[it]) }
            val csvs = if (csv) localIds.filter { id -> state.csvNames[id]?.let { it !in remote } ?: true } else emptyList()
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
