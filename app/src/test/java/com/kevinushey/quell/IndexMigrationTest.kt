package com.kevinushey.quell

import com.kevinushey.quell.data.DatasetMeta
import com.kevinushey.quell.data.DatasetRepository
import com.kevinushey.quell.data.ExperimentKind
import org.junit.Assert.assertEquals
import org.junit.Test

class IndexMigrationTest {

    private val legacy = """
        [{"id":"a","name":"Baseline","sourceName":"a.zip","importedAtEpochMs":1,"sampleCount":10,"sampleRate":400.0,"durationSec":1.0,"colorIndex":0},
         {"id":"b","name":"Taps","sourceName":"b.zip","importedAtEpochMs":2,"sampleCount":10,"sampleRate":400.0,"durationSec":1.0,"colorIndex":1,"kind":"TAP"}]
    """

    @Test
    fun legacyEntriesWithoutKindBecomeDriveRuns() {
        val list = DatasetRepository.indexJson.decodeFromString<List<DatasetMeta>>(DatasetRepository.migrateIndex(legacy))
        assertEquals(ExperimentKind.DRIVE, list[0].kind)
        assertEquals(ExperimentKind.TAP, list[1].kind)
    }

    @Test
    fun newEntriesAlwaysCarryKind() {
        val meta = DatasetMeta("c", "Plain", "c.csv", 3, 10, 400.0, 1.0, 2)
        val text = DatasetRepository.indexJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(DatasetMeta.serializer()), listOf(meta))
        val back = DatasetRepository.indexJson.decodeFromString<List<DatasetMeta>>(DatasetRepository.migrateIndex(text))
        assertEquals(ExperimentKind.GENERAL, back[0].kind)
    }

    @Test
    fun restoredRunsGetTheirOwnColourAndName() {
        fun meta(id: String, name: String, color: Int) = DatasetMeta(id, name, "$id.csv", 0, 10, 400.0, 1.0, color)
        val here = listOf(meta("a", "Baseline", 0), meta("b", "Run", 1))
        // The other phone's runs start at colour 0 too, and one shares a name; colour 5 is free and kept.
        val placed = DatasetRepository.placeAdopted(here, listOf(meta("x", "Baseline", 0), meta("y", "Mass 30 g", 1), meta("z", "Taps", 5)))
        assertEquals(listOf("Baseline (2)", "Mass 30 g", "Taps"), placed.map { it.name })
        assertEquals(listOf(2, 3, 5), placed.map { it.colorIndex })
    }
}
