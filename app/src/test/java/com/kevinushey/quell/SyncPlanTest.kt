package com.kevinushey.quell

import com.kevinushey.quell.sync.RemoteFile
import com.kevinushey.quell.sync.SyncPlan
import com.kevinushey.quell.sync.SyncState
import kotlinx.serialization.json.Json
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
        indexSize: Long = 100,
        sweepsSize: Long = 10,
    ) = SyncPlan.plan(local, remote, indexIds, deleted, state, indexHash, indexSize, sweepsHash, sweepsSize)

    @Test
    fun firstPassWritesEverything() {
        val p = plan(listOf("a", "b"), emptyMap())
        assertEquals(listOf("a", "b"), p.csvToWrite)
        assertTrue(p.binsToRetire.isEmpty())
        assertTrue(p.writeIndex)
        assertTrue(p.writeSweeps)
        assertTrue(p.deletions.isEmpty())
    }

    @Test
    fun unchangedStoreIsANoOp() {
        val state = SyncState(mirrored = setOf("a", "b"), csvNames = mapOf("a" to "A-run.csv", "b" to "B-run.csv"), indexHash = "i1", sweepsHash = "s1")
        val p = plan(listOf("a", "b"), remote("A-run.csv", "B-run.csv", "index.json", "sweeps.json"), state, indexIds = listOf("a", "b"))
        assertTrue(p.isEmpty)
    }

    @Test
    fun metadataEditsRewriteOnlyTheIndex() {
        val state = SyncState(mirrored = setOf("a"), csvNames = mapOf("a" to "A-run.csv"), indexHash = "i1", sweepsHash = "s1")
        val p = plan(listOf("a"), remote("A-run.csv", "index.json", "sweeps.json"), state, indexIds = listOf("a"), indexHash = "i2")
        assertTrue(p.csvToWrite.isEmpty())
        assertTrue(p.writeIndex)
        assertFalse(p.writeSweeps)
    }

    @Test
    fun cleanedFolderIsRefilled() {
        // The user emptied the folder: recorded CSVs are written again rather than assumed present.
        val state = SyncState(mirrored = setOf("a"), csvNames = mapOf("a" to "A-run.csv"), indexHash = "i1", sweepsHash = "s1")
        val p = plan(listOf("a"), emptyMap(), state)
        assertEquals(listOf("a"), p.csvToWrite)
        assertTrue(p.writeIndex)
    }

    @Test
    fun everyLocalRunGetsItsCsvOnce() {
        val r = remote("a-run.csv", "index.json", "sweeps.json")
        val state = SyncState(mirrored = setOf("a"), csvNames = mapOf("a" to "a-run.csv"), indexHash = "i1", sweepsHash = "s1")
        assertEquals(listOf("b"), plan(listOf("a", "b"), r, state, indexIds = listOf("a")).csvToWrite)
    }

    @Test
    fun onlyExplicitDeletesRemoveFiles() {
        val r = remote("a.bin", "b.bin", "stranger.bin", "b-run.csv", "index.json", "sweeps.json")
        val state = SyncState(mirrored = setOf("a", "b"), csvNames = mapOf("b" to "b-run.csv"), indexHash = "i1", sweepsHash = "s1")
        // b was deleted here; stranger.bin was never ours.
        val p = plan(listOf("a"), r, state, indexIds = listOf("a", "b"), deleted = setOf("b"), indexHash = "i2")
        assertEquals(mapOf("b" to listOf("b.bin", "b-run.csv")), p.deletions)
        assertEquals(listOf("b.bin", "b-run.csv"), p.filesToDelete)
        assertFalse("stranger.bin" in p.filesToDelete)
        assertTrue(p.notOnThisPhone.isEmpty())
        assertTrue(p.writeIndex)
    }

    @Test
    fun aDeletedRunsCsvFromAnotherPhoneGoesToo() {
        // The run was deleted here, but its CSV in the folder is another phone's copy under another name.
        val id = "0123abcd-0000-0000-0000-000000000000"
        val r = remote("$id.bin", "Theirs-0123abcd.csv", "index.json", "sweeps.json")
        assertEquals(mapOf(id to listOf("$id.bin", "Theirs-0123abcd.csv")), plan(emptyList(), r, indexIds = listOf(id), deleted = setOf(id)).deletions)
        // Another run sharing the first eight characters makes the name ambiguous: only the .bin goes.
        val twin = "0123abcd-1111-1111-1111-111111111111"
        assertEquals(mapOf(id to listOf("$id.bin")), plan(listOf(twin), r, indexIds = listOf(id, twin), deleted = setOf(id)).deletions)
    }

    @Test
    fun absenceFromTheLocalListIsNotADeletion() {
        // The local index failed to load (or lost entries): nothing was deleted, so nothing goes.
        val r = remote("a.bin", "b.bin", "index.json", "sweeps.json")
        val state = SyncState(mirrored = setOf("a", "b"), indexHash = "i1", sweepsHash = "s1")
        val p = plan(emptyList(), r, state, indexIds = listOf("a", "b"), indexHash = "union")
        assertTrue(p.deletions.isEmpty())
        assertTrue(p.binsToRetire.isEmpty())
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
    fun aRunWithOnlyACsvElsewhereIsRestorable() {
        val id = "0123abcd-0000-0000-0000-000000000000"
        val p = plan(listOf("a"), remote("Run-0123abcd.csv", "index.json", "sweeps.json"), indexIds = listOf("a", id))
        assertEquals(setOf(id), p.notOnThisPhone)
    }

    @Test
    fun deletedDatasetsAlreadyGoneNeedNothing() {
        val r = remote("A-run.csv", "index.json", "sweeps.json")
        val state = SyncState(mirrored = setOf("a"), csvNames = mapOf("a" to "A-run.csv"), indexHash = "i1", sweepsHash = "s1")
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

    private fun withCsv(size: Long?) =
        remote("a.bin", "A-run.csv", "index.json", "sweeps.json", sizes = if (size == null) emptyMap() else mapOf("A-run.csv" to size))

    private val measured = SyncState(mirrored = setOf("a"), csvNames = mapOf("a" to "A-run.csv"), csvSizes = mapOf("a" to 500L), indexHash = "i1", sweepsHash = "s1")

    @Test
    fun anOldBinGoesOnlyOnceTheFolderListsItsCsvWhole() {
        assertEquals(listOf("a"), plan(listOf("a"), withCsv(500), measured, indexIds = listOf("a")).binsToRetire)

        // A name alone proves nothing: with no listed size, or no expected one, the .bin stays and nothing is rewritten.
        val unjudged = listOf(
            plan(listOf("a"), withCsv(null), measured, indexIds = listOf("a")),
            plan(listOf("a"), withCsv(500), measured.copy(csvSizes = emptyMap()), indexIds = listOf("a")),
        )
        for (p in unjudged) {
            assertTrue(p.binsToRetire.isEmpty())
            assertTrue(p.csvToWrite.isEmpty())
        }

        // Listed short: cut off by a pass that died, here or on another phone. Written again; the .bin stays.
        val short = plan(listOf("a"), withCsv(499), measured, indexIds = listOf("a"))
        assertEquals(listOf("a"), short.csvToWrite)
        assertTrue(short.binsToRetire.isEmpty())

        // Listed long: another phone formats some numbers differently. Left alone rather than traded back and forth.
        val long = plan(listOf("a"), withCsv(501), measured, indexIds = listOf("a"))
        assertTrue(long.csvToWrite.isEmpty())
        assertTrue(long.binsToRetire.isEmpty())

        // A CSV still in flight may be partial whatever its size.
        val writing = plan(listOf("a"), withCsv(500), measured.copy(csvWriting = mapOf("A-run.csv" to "a")), indexIds = listOf("a"))
        assertEquals(listOf("a"), writing.csvToWrite)
        assertTrue(writing.binsToRetire.isEmpty())
    }

    @Test
    fun anOldBinThatIsTheOnlyCopyStays() {
        // No CSV yet, or a recorded one the folder has lost.
        assertTrue(plan(listOf("a"), remote("a.bin", "index.json", "sweeps.json"), SyncState(), indexIds = listOf("a")).binsToRetire.isEmpty())
        assertTrue(plan(listOf("a"), remote("a.bin", "index.json", "sweeps.json"), measured, indexIds = listOf("a")).binsToRetire.isEmpty())
        // Another phone's runs, and runs deleted elsewhere, are not this phone's to convert.
        assertTrue(plan(emptyList(), withCsv(500), measured, indexIds = listOf("a")).binsToRetire.isEmpty())
        assertTrue(plan(listOf("a"), withCsv(500), measured, indexIds = listOf("a"), deleted = setOf("a")).binsToRetire.isEmpty())
    }

    @Test
    fun aRecordFromBeforeCsvOnlyMirrorsRetiresNothingUnmeasured() {
        // The previous version wrote no sizes, and its csvNames could hold another phone's copy adopted unread.
        val old = """
            {"mirrored":["a"],"csvNames":{"a":"A-run.csv"},"indexHash":"i1","sweepsHash":"s1",
             "badIndexHash":null,"badSweepsHash":null,"badTombstonesHash":null,"csvWriting":{}}
        """.trimIndent()
        val state = Json { ignoreUnknownKeys = true }.decodeFromString<SyncState>(old)
        assertTrue(plan(listOf("a"), withCsv(500), state, indexIds = listOf("a")).binsToRetire.isEmpty())
        // Once this phone has measured what the copy should be, a match lets the .bin go.
        assertEquals(listOf("a"), plan(listOf("a"), withCsv(500), state.copy(csvSizes = mapOf("a" to 500L)), indexIds = listOf("a")).binsToRetire)
    }

    @Test
    fun regeneratedFilesOfTheWrongSizeAreWrittenAgain() {
        // The provider reports sizes: an index or sweep list that disagrees with what we would write is rewritten.
        val r = remote("index.json", "sweeps.json", sizes = mapOf("index.json" to 100L, "sweeps.json" to 10L))
        val state = SyncState(indexHash = "i1", sweepsHash = "s1")
        assertFalse(plan(emptyList(), r, state).writeIndex)
        assertTrue(plan(emptyList(), r, state, indexSize = 120).writeIndex)
        assertTrue(plan(emptyList(), r, state, sweepsSize = 12).writeSweeps)
    }

    @Test
    fun aRegeneratedFileIsWrittenWhenChangedMissingOrTheWrongSize() {
        assertTrue(SyncPlan.needsWrite(null, "h", 10, "h"))
        assertFalse(SyncPlan.needsWrite(RemoteFile(-1), "h", 10, "h"))
        assertFalse(SyncPlan.needsWrite(RemoteFile(10), "h", 10, "h"))
        assertTrue(SyncPlan.needsWrite(RemoteFile(9), "h", 10, "h"))
        assertTrue(SyncPlan.needsWrite(RemoteFile(10), "h2", 10, "h"))
        assertTrue(SyncPlan.needsWrite(RemoteFile(10), "h", 10, null))
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
        // b's samples have not arrived yet; c was deleted here; a is also listed remotely.
        val remoteIndex = listOf(meta("a"), meta("b"), meta("c"))
        val merged = SyncPlan.mergeIndex(local, remoteIndex, setOf("c"))
        assertEquals(listOf("a", "b"), merged.map { it.id })
        assertTrue(SyncPlan.indexWriteIsSafe(merged.map { it.id }, remoteIndex.map { it.id }, setOf("c")))
        // The plan still does not count b as restorable until its file is there.
        val p = plan(listOf("a"), remote("A-run.csv", "index.json", "sweeps.json"), indexIds = listOf("a", "b"))
        assertTrue(p.notOnThisPhone.isEmpty())
    }

    @Test
    fun aDamagedFolderFileIsSetAsideOnlyOnItsSecondSighting() {
        assertEquals(SyncPlan.DamagedFile.ABORT_AND_REMEMBER, SyncPlan.damagedFileAction(null, "deadbeef"))
        assertEquals(SyncPlan.DamagedFile.ABORT_AND_REMEMBER, SyncPlan.damagedFileAction("other", "deadbeef"))
        assertEquals(SyncPlan.DamagedFile.SET_ASIDE, SyncPlan.damagedFileAction("deadbeef", "deadbeef"))
    }

    @Test
    fun aRestoredRunsFolderCsvIsAdoptedNotWrittenTwice() {
        // The run was "Baseline" on the other phone and is "Baseline (2)" here; its CSV is already in the folder.
        val id = "0123abcd-0000-0000-0000-000000000000"
        val r = remote("$id.bin", "Baseline-0123abcd.csv", "index.json", "sweeps.json")
        val expected = mapOf(id to "Baseline _2_-0123abcd.csv")
        val state = SyncPlan.adoptCsvNames(SyncState(mirrored = setOf(id)), expected, r.keys)
        assertEquals(mapOf(id to "Baseline-0123abcd.csv"), state.csvNames)
        val p = plan(listOf(id), r, state, indexIds = listOf(id))
        assertTrue(p.csvToWrite.isEmpty())
        // A copy taken over unread drops the run's old .bin only once the folder lists it at the size expected here.
        assertTrue(p.binsToRetire.isEmpty())
        val sized = remote("$id.bin", "Baseline-0123abcd.csv", "index.json", "sweeps.json", sizes = mapOf("Baseline-0123abcd.csv" to 800L))
        assertEquals(listOf(id), plan(listOf(id), sized, state.copy(csvSizes = mapOf(id to 800L)), indexIds = listOf(id)).binsToRetire)
        // A name this phone already tracks is left alone.
        val tracked = SyncState(csvNames = mapOf(id to "Mine-0123abcd.csv"))
        assertEquals(tracked, SyncPlan.adoptCsvNames(tracked, expected, r.keys))
        // This phone's own name with no record is a write cut short: not adopted, so it is written again.
        val own = remote("$id.bin", "Baseline _2_-0123abcd.csv", "index.json", "sweeps.json")
        val untracked = SyncPlan.adoptCsvNames(SyncState(mirrored = setOf(id)), expected, own.keys)
        assertTrue(untracked.csvNames.isEmpty())
        assertEquals(listOf(id), plan(listOf(id), own, untracked, indexIds = listOf(id)).csvToWrite)
    }

    @Test
    fun aCsvCutShortAndThenRenamedIsRemovedNotAdopted() {
        // A pass died writing "Run-0123abcd.csv"; the run has since been renamed, so this phone would now write "Fast-...".
        val id = "0123abcd-0000-0000-0000-000000000000"
        val expected = mapOf(id to "Fast-0123abcd.csv")
        val r = remote("$id.bin", "Run-0123abcd.csv", "index.json", "sweeps.json")
        val state = SyncState(mirrored = setOf(id), csvWriting = mapOf("Run-0123abcd.csv" to id))
        assertEquals(state, SyncPlan.adoptCsvNames(state, expected, r.keys))
        assertEquals(listOf("Run-0123abcd.csv"), SyncPlan.staleCsvWrites(state, expected))
        // Under the name this phone still writes, the copy is simply written again, not removed.
        assertTrue(SyncPlan.staleCsvWrites(state, mapOf(id to "Run-0123abcd.csv")).isEmpty())
        // One run can leave unfinished copies under its old and new names; only the old one is stale.
        val both = state.copy(csvWriting = mapOf("Run-0123abcd.csv" to id, "Fast-0123abcd.csv" to id))
        assertEquals(listOf("Run-0123abcd.csv"), SyncPlan.staleCsvWrites(both, expected))
        // A run missing from the local list (an index that failed to load) keeps it: it may be the folder's only copy.
        assertTrue(SyncPlan.staleCsvWrites(state, emptyMap()).isEmpty())
    }

    @Test
    fun aRecordedCsvThatWasStillBeingWrittenIsWrittenAgain() {
        // The recorded name is in the folder, but a pass died while rewriting it: it may be partial.
        val id = "0123abcd-0000-0000-0000-000000000000"
        val r = remote("$id.bin", "Run-0123abcd.csv", "index.json", "sweeps.json")
        val state = SyncState(mirrored = setOf(id), csvNames = mapOf(id to "Run-0123abcd.csv"), indexHash = "i1", sweepsHash = "s1")
        assertTrue(plan(listOf(id), r, state, indexIds = listOf(id)).csvToWrite.isEmpty())
        val cutShort = state.copy(csvWriting = mapOf("Run-0123abcd.csv" to id))
        assertEquals(listOf(id), plan(listOf(id), r, cutShort, indexIds = listOf(id)).csvToWrite)
        // The unfinished copy is under the run's current name while the recorded one is another, complete file.
        val renamed = remote("$id.bin", "Run-0123abcd.csv", "Fast-0123abcd.csv", "index.json", "sweeps.json")
        val newName = state.copy(csvWriting = mapOf("Fast-0123abcd.csv" to id))
        assertEquals(listOf(id), plan(listOf(id), renamed, newName, indexIds = listOf(id)).csvToWrite)
    }

    @Test
    fun aStaleUnfinishedCopyThatWillNotDeleteForcesNoRewrite() {
        // "Old-..." was cut short before a rename and the folder refuses to delete it; "Fast-..." is complete and recorded.
        val id = "0123abcd-0000-0000-0000-000000000000"
        val expected = mapOf(id to "Fast-0123abcd.csv")
        val r = remote("$id.bin", "Old-0123abcd.csv", "Fast-0123abcd.csv", "index.json", "sweeps.json")
        val state = SyncState(mirrored = setOf(id), csvNames = expected, indexHash = "i1", sweepsHash = "s1", csvWriting = mapOf("Old-0123abcd.csv" to id))
        assertTrue(plan(listOf(id), r, SyncPlan.forPlanning(state, expected), indexIds = listOf(id)).csvToWrite.isEmpty())
        // The entry itself survives for the delete to retry.
        assertEquals(listOf("Old-0123abcd.csv"), SyncPlan.staleCsvWrites(state, expected))
    }

    @Test
    fun aRecordedCopyCutShortAndThenRenamedIsWrittenUnderTheNewName() {
        // The recorded "Run-..." was itself cut short, the run is now "Fast", and the folder will not delete "Run-...".
        val id = "0123abcd-0000-0000-0000-000000000000"
        val expected = mapOf(id to "Fast-0123abcd.csv")
        val r = remote("$id.bin", "Run-0123abcd.csv", "index.json", "sweeps.json")
        val state = SyncState(
            mirrored = setOf(id),
            csvNames = mapOf(id to "Run-0123abcd.csv"),
            indexHash = "i1",
            sweepsHash = "s1",
            csvWriting = mapOf("Run-0123abcd.csv" to id),
        )
        val p = plan(listOf(id), r, SyncPlan.forPlanning(state, expected), indexIds = listOf(id))
        assertEquals(listOf(id), p.csvToWrite)
        assertTrue(p.binsToRetire.isEmpty())
        assertEquals(listOf("Run-0123abcd.csv"), SyncPlan.staleCsvWrites(state, expected))
    }

    @Test
    fun aTransientFailureGetsEveryRetryThenHandsOverToSyncNow() = kotlinx.coroutines.runBlocking {
        // Every pass still failing: all three run, then the caller is told to stop.
        var attempts = 0
        assertTrue(SyncPlan.retryPasses(3, 0) { attempts++; true })
        assertEquals(3, attempts)
        // The second pass gets through: no third, and nothing to hand over.
        attempts = 0
        assertFalse(SyncPlan.retryPasses(3, 0) { attempts++; attempts < 2 })
        assertEquals(2, attempts)
    }

    @Test
    fun aRunDeletedOnAnotherPhoneIsNotUploadedAgain() {
        // b is local here but tombstoned in the folder: it is neither uploaded nor carried, and nothing is deleted here.
        val r = remote("a.bin", "b.bin", "index.json", "sweeps.json", "deleted.json")
        val state = SyncState(mirrored = setOf("a"), indexHash = "i1", sweepsHash = "s1")
        val p = plan(listOf("a", "b"), r, state, indexIds = listOf("a"), deleted = setOf("b"))
        assertFalse("b" in p.csvToWrite)
        assertFalse("b" in p.binsToRetire)
        assertTrue(p.deletions.isEmpty())
        // ...and it leaves the folder's index, so both phones write the same file.
        fun meta(id: String) = com.kevinushey.quell.data.DatasetMeta(id, id, "$id.csv", 0, 10, 400.0, 1.0, 0)
        val merged = SyncPlan.mergeIndex(listOf(meta("a"), meta("b")), listOf(meta("a")), setOf("b"))
        assertEquals(listOf("a"), merged.map { it.id })
        assertTrue(SyncPlan.indexWriteIsSafe(merged.map { it.id }, listOf("a"), setOf("b")))
    }

    @Test
    fun tombstonedRunsGetNoCsvCopyEither() {
        val r = remote("a.bin", "index.json", "sweeps.json", "deleted.json")
        val state = SyncState(mirrored = setOf("a"), indexHash = "i1", sweepsHash = "s1")
        val p = plan(listOf("a", "b"), r, state, indexIds = listOf("a"), deleted = setOf("b"))
        assertEquals(listOf("a"), p.csvToWrite)
    }

    @Test
    fun csvFilesAreFoundByTheirIdSuffixUnlessItIsShared() {
        val a = "0123abcd-0000-0000-0000-000000000000"
        val b = "4567cdef-0000-0000-0000-000000000000"
        val names = listOf("Zed-0123abcd.csv", "Alpha-0123abcd.csv", "Other-4567cdef.csv", "runs.csv", "index.json", "Stray-89abcdef.csv")
        assertEquals(mapOf(a to listOf("Alpha-0123abcd.csv", "Zed-0123abcd.csv"), b to listOf("Other-4567cdef.csv")), SyncPlan.csvFilesById(listOf(a, b), names))
        val twin = "0123abcd-1111-1111-1111-111111111111"
        assertEquals(mapOf(b to listOf("Other-4567cdef.csv")), SyncPlan.csvFilesById(listOf(a, b, twin), names))
    }

    @Test
    fun runsCsvNamesTheRecordedCopyFirst() {
        val a = "0123abcd-0000-0000-0000-000000000000"
        val b = "4567cdef-0000-0000-0000-000000000000"
        val c = "89abcdef-0000-0000-0000-000000000000"
        val names = listOf("Mine-0123abcd.csv", "Theirs-0123abcd.csv", "Other-4567cdef.csv")
        // a has a recorded copy; b only another phone's; c has no CSV in the folder at all.
        val files = SyncPlan.runFiles(listOf(a, b, c), mapOf(a to "Theirs-0123abcd.csv"), names, listOf(a, b, c))
        assertEquals(mapOf(a to "Theirs-0123abcd.csv", b to "Other-4567cdef.csv"), files)
        // A recorded name the folder no longer holds falls back to the suffix.
        assertEquals("Mine-0123abcd.csv", SyncPlan.runFiles(listOf(a), mapOf(a to "Gone-0123abcd.csv"), names, listOf(a))[a])
    }
}
