package com.kevinushey.quell

import com.kevinushey.quell.data.AccelerometerParser
import com.kevinushey.quell.data.DatasetMeta
import com.kevinushey.quell.data.DatasetRepository
import com.kevinushey.quell.data.ExperimentKind
import com.kevinushey.quell.data.Sweep
import com.kevinushey.quell.data.SweepParameter
import com.kevinushey.quell.data.TimeSeries
import com.kevinushey.quell.sync.MirrorCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.StringWriter

class MirrorCsvTest {

    /** Values a decimal round trip could plausibly lose: float-widened sensor readings, tiny and huge magnitudes. */
    private fun awkwardSeries(n: Int = 64): TimeSeries {
        val t = DoubleArray(n) { 1234.5 + it * (1.0 / 397.3) }
        val x = DoubleArray(n) { (0.1f * it - 3.3f).toDouble() }
        val y = DoubleArray(n) { if (it % 2 == 0) 1e-9 * it else -9.80665 + 0.1 + 0.2 }
        val z = DoubleArray(n) { 1e7 + it / 3.0 }
        return TimeSeries(t, x, y, z)
    }

    private fun samplesCsv(s: TimeSeries): String {
        val bin = File.createTempFile("mirror", ".bin")
        try {
            DatasetRepository.writeSeries(bin, s)
            val out = StringWriter()
            val bytes = MirrorCsv.writeSamples(bin, out)
            // The length reported, and the one measured without writing, are what the folder's copy is judged by.
            assertEquals(out.toString().toByteArray(Charsets.UTF_8).size.toLong(), bytes)
            assertEquals(bytes, MirrorCsv.writeSamples(bin, null))
            return out.toString()
        } finally {
            bin.delete()
        }
    }

    @Test
    fun samplesRoundTripExactly() {
        val s = awkwardSeries()
        val back = MirrorCsv.readSamples(samplesCsv(s).reader(), s.size)
        assertTrue(s.time.contentEquals(back.time))
        assertTrue(s.x.contentEquals(back.x))
        assertTrue(s.y.contentEquals(back.y))
        assertTrue(s.z.contentEquals(back.z))
    }

    @Test
    fun valuesThatAreNotFiniteRoundTripToo() {
        // Whatever the writer puts in the folder must read back, or that copy could never be restored.
        val s = TimeSeries(
            doubleArrayOf(0.0, 0.01, 0.02),
            doubleArrayOf(Double.NaN, 1.0, 2.0),
            doubleArrayOf(0.0, Double.POSITIVE_INFINITY, 0.0),
            doubleArrayOf(0.0, 0.0, Double.NEGATIVE_INFINITY),
        )
        val back = MirrorCsv.readSamples(samplesCsv(s).reader(), 3)
        assertTrue(back.x[0].isNaN())
        assertEquals(Double.POSITIVE_INFINITY, back.y[1], 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, back.z[2], 0.0)
    }

    @Test
    fun aMirroredCsvCanBeImportedByHand() {
        val s = awkwardSeries(200)
        val imported = AccelerometerParser.parseCsvText(samplesCsv(s))
        assertEquals(s.size, imported.size)
        assertTrue(s.x.contentEquals(imported.x))
        assertTrue(s.z.contentEquals(imported.z))
    }

    @Test
    fun windowsLineEndingsAndATrailingBlankLineAreAccepted() {
        val s = awkwardSeries(20)
        val text = samplesCsv(s).replace("\n", "\r\n") + "\r\n"
        assertEquals(20, MirrorCsv.readSamples(text.reader(), 20).size)
    }

    @Test
    fun aCopyThatDoesNotMatchTheIndexIsRefused() {
        val s = awkwardSeries(20)
        val text = samplesCsv(s)
        val lines = text.trimEnd('\n').split('\n')
        // Cut short, as an upload that died partway leaves it.
        val short = lines.dropLast(3).joinToString("\n") + "\n"
        assertThrows(IllegalStateException::class.java) { MirrorCsv.readSamples(short.reader(), 20) }
        // Cut inside the last row: z loses its exponent but still reads as a number; only the missing newline tells.
        val clipped = text.dropLast(4)
        assertTrue(clipped.substringAfterLast('\n').split(',').size == 4)
        assertThrows(IllegalStateException::class.java) { MirrorCsv.readSamples(clipped.reader(), 20) }
        // Longer than the index says.
        assertThrows(IllegalStateException::class.java) { MirrorCsv.readSamples(text.reader(), 19) }
        // Another file's header, a missing column, a value that is not a number.
        val foreign = "time,x,y,z\n" + lines.drop(1).joinToString("\n") + "\n"
        assertThrows(IllegalStateException::class.java) { MirrorCsv.readSamples(foreign.reader(), 20) }
        val narrow = (listOf(lines[0], "1.0,2.0,3.0") + lines.drop(2)).joinToString("\n") + "\n"
        assertThrows(IllegalStateException::class.java) { MirrorCsv.readSamples(narrow.reader(), 20) }
        val garbled = (listOf(lines[0], "1.0,abc,3.0,4.0") + lines.drop(2)).joinToString("\n") + "\n"
        assertThrows(IllegalStateException::class.java) { MirrorCsv.readSamples(garbled.reader(), 20) }
    }

    @Test
    fun runsTableListsEveryRunWithItsDetails() {
        val sweep = Sweep(id = "s1", name = "Mass, round 2", parameter = SweepParameter.MASS, createdAtEpochMs = 0)
        val damped = DatasetMeta(
            id = "0123abcd-0000-0000-0000-000000000000",
            name = "Run 3",
            sourceName = "Recorded on device",
            importedAtEpochMs = 1_761_480_000_000,
            sampleCount = 24000,
            sampleRate = 400.0,
            durationSec = 60.0,
            colorIndex = 0,
            notes = "With damper installed, \"soft\" spring\nsecond line",
            deviceModel = "samsung SM-S911U",
            recordedAt = "2026-10-26 09:00:00",
            kind = ExperimentKind.DRIVE,
            trimStart = 5.0,
            damperMassGrams = 120.0,
            sweepId = "s1",
        )
        val bare = DatasetMeta("4567cdef-0000-0000-0000-000000000000", "Baseline", "baseline.zip", 0, 100, 200.0, 0.5, 1)
        val table = MirrorCsv.runsTable(listOf(damped, bare), listOf(sweep), mapOf(damped.id to "Run 3-0123abcd.csv"))

        val expected = MirrorCsv.RUNS_COLUMNS.joinToString(",") + "\n" +
            "Run 3-0123abcd.csv,0123abcd-0000-0000-0000-000000000000,Run 3,\"With damper installed, \"\"soft\"\" spring\nsecond line\"," +
            "drive,2026-10-26 09:00:00,2025-10-26T12:00:00Z,Recorded on device,24000,400.0,60.0,5.0,60.0,120.0," +
            "\"Mass, round 2\",mass,g,120.0,samsung SM-S911U,\n" +
            ",4567cdef-0000-0000-0000-000000000000,Baseline,,general,,1970-01-01T00:00:00Z,baseline.zip,100,200.0,0.5,0.0,0.5,,,,,,,\n"
        assertEquals(expected, table)
    }
}
