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

/** The work one sync pass has to do; pure so it can be tested without a ContentResolver. */
class SyncPlan(
    val binsToWrite: List<String>,
    val csvToWrite: List<String>,
    /** Remote file names to delete: mirrors of datasets that no longer exist locally. */
    val filesToDelete: List<String>,
    val writeIndex: Boolean,
    val writeSweeps: Boolean,
) {
    val isEmpty: Boolean get() = binsToWrite.isEmpty() && csvToWrite.isEmpty() && filesToDelete.isEmpty() && !writeIndex && !writeSweeps

    companion object {
        /**
         * Bins are immutable, so one copy per id is enough; a missing remote
         * file is rewritten even if it was mirrored before (the user may have
         * cleaned the folder). Deletions only ever touch files this app wrote.
         */
        fun plan(
            localIds: List<String>,
            remoteNames: Set<String>,
            state: SyncState,
            indexHash: String,
            sweepsHash: String,
            csv: Boolean,
            /** Ids listed in the folder's own index that this phone does not have. */
            foreignIds: Set<String> = emptySet(),
        ): SyncPlan {
            val local = localIds.toSet()
            val bins = localIds.filter { "$it.bin" !in remoteNames }
            val csvs = if (csv) localIds.filter { id -> state.csvNames[id]?.let { it !in remoteNames } ?: true } else emptyList()
            // A folder holding datasets this phone lacks is someone else's store (an old phone's, say):
            // writing our index over it or deleting from it would lose them. Restore first.
            if (foreignIds.isNotEmpty())
                return SyncPlan(bins, csvs, emptyList(), writeIndex = false, writeSweeps = false)
            val gone = state.mirrored.filter { it !in local }
            val deletions = ArrayList<String>()
            for (id in gone) {
                if ("$id.bin" in remoteNames)
                    deletions.add("$id.bin")
                state.csvNames[id]?.let { if (it in remoteNames) deletions.add(it) }
            }
            return SyncPlan(
                binsToWrite = bins,
                csvToWrite = csvs,
                filesToDelete = deletions,
                writeIndex = indexHash != state.indexHash || "index.json" !in remoteNames,
                writeSweeps = sweepsHash != state.sweepsHash || "sweeps.json" !in remoteNames,
            )
        }
    }
}
