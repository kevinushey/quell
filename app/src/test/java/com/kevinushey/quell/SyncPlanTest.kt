package com.kevinushey.quell

import com.kevinushey.quell.sync.SyncPlan
import com.kevinushey.quell.sync.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlanTest {

    @Test
    fun firstPassWritesEverything() {
        val plan = SyncPlan.plan(listOf("a", "b"), emptySet(), SyncState(), "i1", "s1", csv = false)
        assertEquals(listOf("a", "b"), plan.binsToWrite)
        assertTrue(plan.csvToWrite.isEmpty())
        assertTrue(plan.writeIndex)
        assertTrue(plan.writeSweeps)
        assertTrue(plan.filesToDelete.isEmpty())
    }

    @Test
    fun unchangedStoreIsANoOp() {
        val remote = setOf("a.bin", "b.bin", "index.json", "sweeps.json")
        val state = SyncState(mirrored = setOf("a", "b"), indexHash = "i1", sweepsHash = "s1")
        val plan = SyncPlan.plan(listOf("a", "b"), remote, state, "i1", "s1", csv = false)
        assertTrue(plan.isEmpty)
    }

    @Test
    fun metadataEditsRewriteOnlyTheIndex() {
        val remote = setOf("a.bin", "index.json", "sweeps.json")
        val state = SyncState(mirrored = setOf("a"), indexHash = "i1", sweepsHash = "s1")
        val plan = SyncPlan.plan(listOf("a"), remote, state, "i2", "s1", csv = false)
        assertTrue(plan.binsToWrite.isEmpty())
        assertTrue(plan.writeIndex)
        assertFalse(plan.writeSweeps)
    }

    @Test
    fun cleanedFolderIsRefilled() {
        // The user emptied the folder: mirrored ids are written again rather than assumed present.
        val state = SyncState(mirrored = setOf("a"), indexHash = "i1", sweepsHash = "s1")
        val plan = SyncPlan.plan(listOf("a"), emptySet(), state, "i1", "s1", csv = false)
        assertEquals(listOf("a"), plan.binsToWrite)
        assertTrue(plan.writeIndex)
    }

    @Test
    fun deletionsOnlyTouchFilesTheAppWrote() {
        val remote = setOf("a.bin", "b.bin", "stranger.bin", "b-run.csv", "index.json", "sweeps.json")
        val state = SyncState(mirrored = setOf("a", "b"), csvNames = mapOf("b" to "b-run.csv"), indexHash = "i1", sweepsHash = "s1")
        // b was deleted locally; stranger.bin was never ours.
        val plan = SyncPlan.plan(listOf("a"), remote, state, "i2", "s1", csv = true)
        assertEquals(setOf("b.bin", "b-run.csv"), plan.filesToDelete.toSet())
        assertFalse("stranger.bin" in plan.filesToDelete)
    }

    @Test
    fun foreignDatasetsFreezeTheIndexAndDeletions() {
        // A new phone pointed at an old phone's folder: write our bins, but never its index or files.
        val remote = setOf("old.bin", "index.json", "sweeps.json")
        val state = SyncState(mirrored = setOf("gone"))
        val plan = SyncPlan.plan(listOf("new"), remote + "gone.bin", state, "i1", "s1", csv = false, foreignIds = setOf("old"))
        assertEquals(listOf("new"), plan.binsToWrite)
        assertFalse(plan.writeIndex)
        assertFalse(plan.writeSweeps)
        assertTrue(plan.filesToDelete.isEmpty())
    }

    @Test
    fun csvCopiesFollowTheToggle() {
        val remote = setOf("a.bin", "a-run.csv", "index.json", "sweeps.json")
        val state = SyncState(mirrored = setOf("a"), csvNames = mapOf("a" to "a-run.csv"), indexHash = "i1", sweepsHash = "s1")
        assertTrue(SyncPlan.plan(listOf("a", "b"), remote, state, "i1", "s1", csv = false).csvToWrite.isEmpty())
        assertEquals(listOf("b"), SyncPlan.plan(listOf("a", "b"), remote, state, "i1", "s1", csv = true).csvToWrite)
    }
}
