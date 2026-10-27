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

    private fun samplesCsv(s: TimeSeries, header: String = ""): String {
        val bin = File.createTempFile("mirror", ".bin")
        try {
            DatasetRepository.writeSeries(bin, s)
            val out = StringWriter()
            val bytes = MirrorCsv.writeSamples(bin, header, out)
            // The length reported, and the one measured without writing, are what the folder's copy is judged by.
            assertEquals(out.toString().toByteArray(Charsets.UTF_8).size.toLong(), bytes)
            assertEquals(bytes, MirrorCsv.writeSamples(bin, header, null))
            return out.toString()
        } finally {
            bin.delete()
        }
    }

    @Test
    fun samplesRoundTripExactly() {
        val s = awkwardSeries()
        val back = MirrorCsv.readSamples(samplesCsv(s).reader(), s.size).series
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
        val back = MirrorCsv.readSamples(samplesCsv(s).reader(), 3).series
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
        assertEquals(20, MirrorCsv.readSamples(text.reader(), 20).series.size)
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

    private val sweep = Sweep(id = "s1", name = "Mass, round 2", parameter = SweepParameter.MASS, createdAtEpochMs = 0)

    private val damped = DatasetMeta(
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

    private val bare = DatasetMeta("4567cdef-0000-0000-0000-000000000000", "Baseline", "baseline.zip", 0, 100, 200.0, 0.5, 1)

    @Test
    fun runsTableListsEveryRunWithItsDetails() {
        val table = MirrorCsv.runsTable(listOf(damped, bare), listOf(sweep), mapOf(damped.id to "Run 3-0123abcd.csv"))

        val expected = MirrorCsv.RUNS_COLUMNS.joinToString(",") + "\n" +
            "Run 3-0123abcd.csv,0123abcd-0000-0000-0000-000000000000,Run 3,\"With damper installed, \"\"soft\"\" spring\nsecond line\"," +
            "drive,2026-10-26 09:00:00,2025-10-26T12:00:00Z,Recorded on device,24000,400.0,60.0,5.0,60.0,120.0," +
            "\"Mass, round 2\",mass,g,120.0,samsung SM-S911U,\n" +
            ",4567cdef-0000-0000-0000-000000000000,Baseline,,general,,1970-01-01T00:00:00Z,baseline.zip,100,200.0,0.5,0.0,0.5,,,,,,,\n"
        assertEquals(expected, table)
    }

    @Test
    fun theHeaderRepeatsTheRunsRowAsYamlComments() {
        val expected = """
            # id: "0123abcd-0000-0000-0000-000000000000"
            # name: "Run 3"
            # notes: |-
            #   With damper installed, "soft" spring
            #   second line
            # kind: "drive"
            # recorded_at: "2026-10-26 09:00:00"
            # imported_at: "2025-10-26T12:00:00Z"
            # source: "Recorded on device"
            # samples: 24000
            # sample_rate_hz: 400.0
            # duration_s: 60.0
            # trim_start_s: 5.0
            # trim_end_s: 60.0
            # damper_mass_g: 120.0
            # sweep: "Mass, round 2"
            # sweep_parameter: "mass"
            # sweep_unit: "g"
            # sweep_value: 120.0
            # device: "samsung SM-S911U"

        """.trimIndent()
        assertEquals(expected, MirrorCsv.header(damped, sweep))

        // Empty values are left out rather than written as YAML nulls.
        val bareHeader = MirrorCsv.header(bare, null)
        assertTrue(bareHeader.lines().none { it.startsWith("# notes") || it.startsWith("# sweep") || it.startsWith("# device") })
    }

    @Test
    fun textALiteralBlockCannotCarryIsQuotedAndEscaped() {
        fun notesLine(notes: String) = MirrorCsv.header(bare.copy(notes = notes), null).lines().first { it.startsWith("# notes:") }

        // Leading spaces would read as indentation, a trailing break as chomping: both are kept only by quoting.
        assertEquals("# notes: \"  indented\\nsecond\"", notesLine("  indented\nsecond"))
        assertEquals("# notes: \"ends\\nwith a break\\n\"", notesLine("ends\nwith a break\n"))
        // A carriage return or NEL is a line break to some readers, and would end the comment line early.
        assertEquals("# notes: \"one\\rtwo\"", notesLine("one\rtwo"))
        assertEquals("# notes: \"one\\x85two\\u2028three\"", notesLine("one\u0085two\u2028three"))
        assertEquals("# notes: \"tab\\there, \\\"quote\\\", back\\\\slash, bell\\x07\"", notesLine("tab\there, \"quote\", back\\slash, bell\u0007"))
        // Printable text beyond ASCII is written as it is.
        assertEquals("# notes: \"Ressort tr\u00e8s souple \ud83d\ude97\"", notesLine("Ressort tr\u00e8s souple \ud83d\ude97"))

        // A literal block keeps blank lines and indented lines.
        val block = MirrorCsv.header(bare.copy(notes = "Setup:\n\n  - phone on the rail\nend"), null)
        assertTrue("# notes: |-\n#   Setup:\n#\n#     - phone on the rail\n#   end\n" in block)
    }

    @Test
    fun largeNumbersCarryTheirExponentSign() {
        // YAML 1.1 readers take 2.5E7 for a string; 2.5E+7 is a float to every reader. Small ones already carry "-".
        val header = MirrorCsv.header(bare.copy(damperMassGrams = 2.5e7, trimStart = 5e-4), null)
        assertTrue("# damper_mass_g: 2.5E+7\n" in header)
        assertTrue("# trim_start_s: 5.0E-4\n" in header)
    }

    @Test
    fun everyHeaderLineIsAComment() {
        val awkward = damped.copy(name = "Two\nlines", notes = "a\r\nb\n\nc \u2029 d\u0000")
        val header = MirrorCsv.header(awkward, sweep)
        assertTrue(header.endsWith("\n"))
        // Any of '\n', '\r', NEL, LS or PS could start a line some reader sees; each must start with '#'.
        for (line in header.dropLast(1).split('\n', '\r', '\u0085', '\u2028', '\u2029'))
            assertTrue(line, line.startsWith("#"))
    }

    @Test
    fun aHeadedCsvReadsBackWithItsHeaderAndImportsByHand() {
        val s = awkwardSeries(50)
        val header = MirrorCsv.header(damped.copy(notes = "Tr\u00e8s souple\nsecond line"), sweep)
        // samplesCsv checks the reported byte count against the UTF-8 the header encodes to.
        val text = samplesCsv(s, header)
        assertTrue(text.startsWith(header + MirrorCsv.SAMPLES_HEADER + "\n"))

        val back = MirrorCsv.readSamples(text.reader(), s.size)
        assertEquals(header, back.header)
        assertTrue(s.x.contentEquals(back.series.x))
        assertTrue(s.z.contentEquals(back.series.z))
        // Windows line endings give the same header back, so the fingerprint restore records still matches.
        assertEquals(header, MirrorCsv.readSamples(text.replace("\n", "\r\n").reader(), s.size).header)
        // A copy from before headers reads back with none.
        assertEquals("", MirrorCsv.readSamples(samplesCsv(s).reader(), s.size).header)

        val imported = AccelerometerParser.parseCsvText(text)
        assertEquals(s.size, imported.size)
        assertTrue(s.y.contentEquals(imported.y))
    }

    @Test
    fun aHeaderLongerThanAnyThisAppWritesIsRefused() {
        val s = awkwardSeries(5)
        val huge = "# notes: \"" + "x".repeat(80) + "\"\n"
        val text = samplesCsv(s, huge.repeat(20_000))
        assertThrows(IllegalStateException::class.java) { MirrorCsv.readSamples(text.reader(), 5) }
    }
}
