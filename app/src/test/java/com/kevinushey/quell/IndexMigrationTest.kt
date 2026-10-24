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
}
