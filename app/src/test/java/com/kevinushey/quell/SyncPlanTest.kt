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
        indexHash: String = "i1",
        sweepsHash: String = "s1",
        csv: Boolean = false,
        localSizes: Map<String, Long> = emptyMap(),
        indexSize: Long = 100,
        sweepsSize: Long = 10,
    ) = SyncPlan.plan(local, localSizes, remote, indexIds, state, indexHash, indexSize, sweepsHash, sweepsSize, csv)

    @Test
    fun firstPassWritesEverything() {
        val p = plan(listOf("a", "b"), emptyMap())
        assertEquals(listOf("a", "b"), p.binsToWrite)
        assertTrue(p.csvToWrite.isEmpty())
        assertTrue(p.writeIndex)
        assertTrue(p.writeSweeps)
        assertTrue(p.filesToDelete.isEmpty())
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
    fun deletionsOnlyTouchFilesTheAppWrote() {
        val r = remote("a.bin", "b.bin", "stranger.bin", "b-run.csv", "index.json", "sweeps.json")
        val state = SyncState(mirrored = setOf("a", "b"), csvNames = mapOf("b" to "b-run.csv"), indexHash = "i1", sweepsHash = "s1")
        // b was deleted locally; stranger.bin was never ours.
        val p = plan(listOf("a"), r, state, indexIds = listOf("a", "b"), indexHash = "i2", csv = true)
        assertEquals(setOf("b.bin", "b-run.csv"), p.filesToDelete.toSet())
        assertFalse("stranger.bin" in p.filesToDelete)
        assertEquals(setOf("b"), p.releasedIds)
        assertTrue(p.writeIndex)
    }

    @Test
    fun aLocallyDeletedDatasetStillInTheRemoteIndexIsNotForeign() {
        // After the first pass the remote index lists everything we had; deleting one must not freeze the mirror.
        val r = remote("a.bin", "b.bin", "index.json", "sweeps.json")
        val state = SyncState(mirrored = setOf("a", "b"), indexHash = "i1", sweepsHash = "s1")
        val p = plan(listOf("a"), r, state, indexIds = listOf("a", "b"), indexHash = "i2")
        assertTrue(p.foreignIds.isEmpty())
        assertEquals(listOf("b.bin"), p.filesToDelete)
        assertTrue(p.writeIndex)
    }

    @Test
    fun foreignDatasetsFreezeTheIndexAndDeletions() {
        // A new phone pointed at an old phone's folder: write our bins, but never its index or files.
        val r = remote("old.bin", "gone.bin", "index.json", "sweeps.json")
        val state = SyncState(mirrored = setOf("gone"))
        val p = plan(listOf("new"), r, state, indexIds = listOf("old", "gone"))
        assertEquals(setOf("old"), p.foreignIds)
        assertEquals(listOf("new"), p.binsToWrite)
        assertFalse(p.writeIndex)
        assertFalse(p.writeSweeps)
        assertTrue(p.filesToDelete.isEmpty())
        assertTrue(p.releasedIds.isEmpty())
    }

    @Test
    fun damagedRemoteFilesDoNotFreezeTheMirror() {
        val r = remote("bad.bin", "a.bin", "index.json", "sweeps.json")
        val state = SyncState(mirrored = setOf("a"), damaged = setOf("bad"), indexHash = "i1", sweepsHash = "s1")
        val p = plan(listOf("a"), r, state, indexIds = listOf("a", "bad"), indexHash = "i2")
        assertTrue(p.foreignIds.isEmpty())
        assertTrue(p.writeIndex)
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
    fun csvCopiesFollowTheToggle() {
        val r = remote("a.bin", "a-run.csv", "index.json", "sweeps.json")
        val state = SyncState(mirrored = setOf("a"), csvNames = mapOf("a" to "a-run.csv"), indexHash = "i1", sweepsHash = "s1")
        assertTrue(plan(listOf("a", "b"), r, state, indexIds = listOf("a")).csvToWrite.isEmpty())
        assertEquals(listOf("b"), plan(listOf("a", "b"), r, state, indexIds = listOf("a"), csv = true).csvToWrite)
    }
}
