package com.kevinushey.quell

import com.kevinushey.quell.sync.RemoteFile
import com.kevinushey.quell.sync.SyncPlan
import com.kevinushey.quell.sync.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlanTest {

    private fun remote(vararg names: String, sizes: Map<String, Long> = emptyMap()): Map<String, RemoteFile> =
        names.associateWith { RemoteFile(sizes[it] ?: -1L) }

    private fun plan(
        local: List<String>,
        remote: Map<String, RemoteFile>,
        state: SyncState = SyncState(),
        indexIds: List<String> = emptyList(),
        deleted: Set<String> = emptySet(),
        indexHash: String = "i1",
        sweepsHash: String = "s1",
        csv: Boolean = false,
        localSizes: Map<String, Long> = emptyMap(),
        indexSize: Long = 100,
        sweepsSize: Long = 10,
    ) = SyncPlan.plan(local, localSizes, remote, indexIds, deleted, state, indexHash, indexSize, sweepsHash, sweepsSize, csv)

    @Test
    fun firstPassWritesEverything() {
        val p = plan(listOf("a", "b"), emptyMap())
        assertEquals(listOf("a", "b"), p.binsToWrite)
        assertTrue(p.csvToWrite.isEmpty())
        assertTrue(p.writeIndex)
        assertTrue(p.writeSweeps)
        assertTrue(p.deletions.isEmpty())
    }

    @Test
    fun unchangedStoreIsANoOp() {
        val state = SyncState(mirrored = setOf("a", "b"), indexHash = "i1", sweepsHash = "s1")
        val p = plan(listOf("a", "b"), remote("a.bin", "b.bin", "index.json", "sweeps.json"), state, indexIds = listOf("a", "b"))
        assertTrue(p.isEmpty)
    }

    @Test
    fun metadataEditsRewriteOnlyTheIndex() {
        val state = SyncState(mirrored = setOf("a"), indexHash = "i1", sweepsHash = "s1")
        val p = plan(listOf("a"), remote("a.bin", "index.json", "sweeps.json"), state, indexIds = listOf("a"), indexHash = "i2")
        assertTrue(p.binsToWrite.isEmpty())
        assertTrue(p.writeIndex)
        assertFalse(p.writeSweeps)
    }

    @Test
    fun cleanedFolderIsRefilled() {
        // The user emptied the folder: mirrored ids are written again rather than assumed present.
        val state = SyncState(mirrored = setOf("a"), indexHash = "i1", sweepsHash = "s1")
        val p = plan(listOf("a"), emptyMap(), state)
        assertEquals(listOf("a"), p.binsToWrite)
        assertTrue(p.writeIndex)
    }

    @Test
    fun onlyExplicitDeletesRemoveFiles() {
        val r = remote("a.bin", "b.bin", "stranger.bin", "b-run.csv", "index.json", "sweeps.json")
        val state = SyncState(mirrored = setOf("a", "b"), csvNames = mapOf("b" to "b-run.csv"), indexHash = "i1", sweepsHash = "s1")
        // b was deleted here; stranger.bin was never ours.
        val p = plan(listOf("a"), r, state, indexIds = listOf("a", "b"), deleted = setOf("b"), indexHash = "i2", csv = true)
        assertEquals(mapOf("b" to listOf("b.bin", "b-run.csv")), p.deletions)
        assertEquals(listOf("b.bin", "b-run.csv"), p.filesToDelete)
        assertFalse("stranger.bin" in p.filesToDelete)
        assertTrue(p.notOnThisPhone.isEmpty())
        assertTrue(p.writeIndex)
    }

    @Test
    fun absenceFromTheLocalListIsNotADeletion() {
        // The local index failed to load (or lost entries): nothing was deleted, so nothing goes.
        val r = remote("a.bin", "b.bin", "index.json", "sweeps.json")
        val state = SyncState(mirrored = setOf("a", "b"), indexHash = "i1", sweepsHash = "s1")
        val p = plan(emptyList(), r, state, indexIds = listOf("a", "b"), indexHash = "union")
        assertTrue(p.deletions.isEmpty())
        assertEquals(setOf("a", "b"), p.notOnThisPhone)
        // The merged index (which the caller hashed) is still written; it carries a and b forward.
        assertTrue(p.writeIndex)
    }

    @Test
    fun aPartiallyRestoredStoreAfterRestartDeletesNothing() {
        // Restore brought back a only; b is still in the folder, not local, not deleted. A later pass must keep it.
        val r = remote("a.bin", "b.bin", "index.json", "sweeps.json")
        val state = SyncState(mirrored = setOf("a"), indexHash = "i1", sweepsHash = "s1")
        val p = plan(listOf("a"), r, state, indexIds = listOf("a", "b"))
        assertTrue(p.deletions.isEmpty())
        assertEquals(setOf("b"), p.notOnThisPhone)
    }

    @Test
    fun deletedDatasetsAlreadyGoneNeedNothing() {
        val r = remote("a.bin", "index.json", "sweeps.json")
        val state = SyncState(mirrored = setOf("a"), indexHash = "i1", sweepsHash = "s1")
        val p = plan(listOf("a"), r, state, indexIds = listOf("a"), deleted = setOf("b"))
        assertTrue(p.deletions.isEmpty())
        assertTrue(p.isEmpty)
    }

    @Test
    fun aDeletedDatasetStillListedElsewhereIsNotOffered() {
        // The folder's index still lists b (deleted here, deletion not yet mirrored): it is not "not on this phone".
        val r = remote("a.bin", "b.bin", "index.json", "sweeps.json")
        val p = plan(listOf("a"), r, SyncState(mirrored = setOf("a", "b"), indexHash = "i1", sweepsHash = "s1"), indexIds = listOf("a", "b"), deleted = setOf("b"))
        assertTrue(p.notOnThisPhone.isEmpty())
        assertEquals(listOf("b.bin"), p.filesToDelete)
    }

    @Test
    fun truncatedRemoteFilesAreWrittenAgain() {
        // The provider reports sizes: a .bin shorter than ours was cut off mid-copy.
        val r = remote("a.bin", "b.bin", "index.json", "sweeps.json", sizes = mapOf("a.bin" to 1000L, "b.bin" to 500L, "index.json" to 100L))
        val state = SyncState(mirrored = setOf("a", "b"), indexHash = "i1", sweepsHash = "s1")
        val p = plan(listOf("a", "b"), r, state, indexIds = listOf("a", "b"), localSizes = mapOf("a" to 1000L, "b" to 2000L))
        assertEquals(listOf("b"), p.binsToWrite)
        assertFalse(p.writeIndex)
        // An index whose size disagrees with what we would write is rewritten even with an unchanged hash.
        assertTrue(plan(listOf("a", "b"), r, state, indexIds = listOf("a", "b"), localSizes = mapOf("a" to 1000L, "b" to 2000L), indexSize = 120).writeIndex)
    }

    @Test
    fun anIndexWriteMayOnlyDropExplicitDeletions() {
        // Remote lists a, b, c; we merged a, b; c was deleted here: fine. c not deleted: the picture is wrong, refuse.
        assertTrue(SyncPlan.indexWriteIsSafe(listOf("a", "b"), listOf("a", "b", "c"), setOf("c")))
        assertFalse(SyncPlan.indexWriteIsSafe(listOf("a", "b"), listOf("a", "b", "c"), emptySet()))
        // An unreadable remote index would look empty; a merge from it can never satisfy a populated listing.
        assertFalse(SyncPlan.indexWriteIsSafe(emptyList(), listOf("a"), emptySet()))
        assertTrue(SyncPlan.indexWriteIsSafe(emptyList(), emptyList(), emptySet()))
    }

    @Test
    fun remoteEntriesWithoutAFileAreCarriedNotDropped() {
        fun meta(id: String) = com.kevinushey.quell.data.DatasetMeta(id, id, "$id.csv", 0, 10, 400.0, 1.0, 0)
        val local = listOf(meta("a"))
        // b's .bin has not arrived yet; c was deleted here; a is also listed remotely.
        val remoteIndex = listOf(meta("a"), meta("b"), meta("c"))
        val merged = SyncPlan.mergeIndex(local, remoteIndex, setOf("c"))
        assertEquals(listOf("a", "b"), merged.map { it.id })
        assertTrue(SyncPlan.indexWriteIsSafe(merged.map { it.id }, remoteIndex.map { it.id }, setOf("c")))
        // The plan still does not count b as restorable until its file is there.
        val p = plan(listOf("a"), remote("a.bin", "index.json", "sweeps.json"), indexIds = listOf("a", "b"))
        assertTrue(p.notOnThisPhone.isEmpty())
    }

    @Test
    fun aDamagedFolderFileIsSetAsideOnlyOnItsSecondSighting() {
        assertEquals(SyncPlan.DamagedFile.ABORT_AND_REMEMBER, SyncPlan.damagedFileAction(null, "deadbeef"))
        assertEquals(SyncPlan.DamagedFile.ABORT_AND_REMEMBER, SyncPlan.damagedFileAction("other", "deadbeef"))
        assertEquals(SyncPlan.DamagedFile.SET_ASIDE, SyncPlan.damagedFileAction("deadbeef", "deadbeef"))
    }

    @Test
    fun aRunDeletedOnAnotherPhoneIsNotUploadedAgain() {
        // b is local here but tombstoned in the folder: it is neither uploaded nor carried, and nothing is deleted here.
        val r = remote("a.bin", "index.json", "sweeps.json", "deleted.json")
        val state = SyncState(mirrored = setOf("a"), indexHash = "i1", sweepsHash = "s1")
        val p = plan(listOf("a", "b"), r, state, indexIds = listOf("a"), deleted = setOf("b"))
        assertTrue(p.binsToWrite.isEmpty())
        assertTrue(p.deletions.isEmpty())
    }

    @Test
    fun csvCopiesFollowTheToggle() {
        val r = remote("a.bin", "a-run.csv", "index.json", "sweeps.json")
        val state = SyncState(mirrored = setOf("a"), csvNames = mapOf("a" to "a-run.csv"), indexHash = "i1", sweepsHash = "s1")
        assertTrue(plan(listOf("a", "b"), r, state, indexIds = listOf("a")).csvToWrite.isEmpty())
        assertEquals(listOf("b"), plan(listOf("a", "b"), r, state, indexIds = listOf("a"), csv = true).csvToWrite)
    }
}
