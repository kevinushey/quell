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
    /** Ids whose remote .bin failed validation on restore; not ours, not restorable, not a reason to freeze. */
    val damaged: Set<String> = emptySet(),
)

/** A file in the mirror folder: its size when the provider reports one, else -1. */
data class RemoteFile(val size: Long)

/** The work one sync pass has to do; pure so it can be tested without a ContentResolver. */
class SyncPlan(
    val binsToWrite: List<String>,
    val csvToWrite: List<String>,
    /** Remote file names to delete: mirrors of datasets that no longer exist locally. */
    val filesToDelete: List<String>,
    /** Ids whose mirrored files are gone after this pass, to drop from the state. */
    val releasedIds: Set<String>,
    val writeIndex: Boolean,
    val writeSweeps: Boolean,
    /** Datasets in the folder's index that are neither local nor ours: someone else's store, left untouched. */
    val foreignIds: Set<String>,
    /** The local list is known to be incomplete, so nothing was deleted and the folder's index was kept. */
    val frozenByDegradedIndex: Boolean = false,
) {
    val isEmpty: Boolean get() = binsToWrite.isEmpty() && csvToWrite.isEmpty() && filesToDelete.isEmpty() && !writeIndex && !writeSweeps

    companion object {
        /**
         * Bins are immutable, so one copy per id is enough; a missing remote
         * file, or one whose size disagrees with the local file (a copy cut
         * short), is written again. Deletions only ever touch files this app
         * wrote. A folder holding datasets that are neither local nor ours
         * is someone else's store (an old phone's, say): its index is left
         * alone until they are restored.
         */
        fun plan(
            localIds: List<String>,
            localBinSizes: Map<String, Long>,
            remote: Map<String, RemoteFile>,
            remoteIndexIds: List<String>,
            state: SyncState,
            indexHash: String,
            indexSize: Long,
            sweepsHash: String,
            sweepsSize: Long,
            csv: Boolean,
            /** The local index could not be fully read; absence from the local list means nothing. */
            degraded: Boolean = false,
        ): SyncPlan {
            val local = localIds.toSet()
            fun complete(name: String, expected: Long?): Boolean {
                val r = remote[name] ?: return false
                return expected == null || r.size < 0 || r.size == expected
            }
            val bins = localIds.filter { !complete("$it.bin", localBinSizes[it]) }
            val csvs = if (csv) localIds.filter { id -> state.csvNames[id]?.let { it !in remote } ?: true } else emptyList()
            // Ours: anything we mirrored or wrote, whether or not it is still local. Damaged files are known, not foreign.
            val foreign = remoteIndexIds.filter { it !in local && it !in state.mirrored && it !in state.damaged && "$it.bin" in remote }.toSet()
            if (foreign.isNotEmpty())
                return SyncPlan(bins, csvs, emptyList(), emptySet(), writeIndex = false, writeSweeps = false, foreignIds = foreign)
            // With the local index lost, the folder may be the only complete copy: write new bins, touch nothing else.
            if (degraded)
                return SyncPlan(bins, csvs, emptyList(), emptySet(), writeIndex = false, writeSweeps = false, foreignIds = emptySet(), frozenByDegradedIndex = true)

            val gone = state.mirrored.filter { it !in local }
            val deletions = ArrayList<String>()
            for (id in gone) {
                if ("$id.bin" in remote)
                    deletions.add("$id.bin")
                state.csvNames[id]?.let { if (it in remote) deletions.add(it) }
            }
            return SyncPlan(
                binsToWrite = bins,
                csvToWrite = csvs,
                filesToDelete = deletions,
                releasedIds = gone.toSet(),
                writeIndex = indexHash != state.indexHash || !complete("index.json", indexSize),
                writeSweeps = sweepsHash != state.sweepsHash || !complete("sweeps.json", sweepsSize),
                foreignIds = emptySet(),
            )
        }
    }
}
