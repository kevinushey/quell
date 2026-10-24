package com.kevinushey.quell

import com.kevinushey.quell.data.AccelerometerParser
import com.kevinushey.quell.data.DatasetRepository
import com.kevinushey.quell.data.ImportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ParserTest {

    private fun phyphoxCsv(n: Int): String {
        val sb = StringBuilder()
        sb.append("\"Time (s)\",\"Acceleration x (m/s^2)\",\"Acceleration y (m/s^2)\",\"Acceleration z (m/s^2)\",\"Absolute acceleration (m/s^2)\"\n")
        for (i in 0 until n)
            sb.append("${"%.9E".format(i * 0.0025)},${0.5},${3.4},${-8.6},${9.3}\n")
        return sb.toString()
    }

    @Test
    fun parsesPhyphoxCsvHeader() {
        val run = AccelerometerParser.parseCsvText(phyphoxCsv(100))
        assertEquals(100, run.size)
        assertEquals(0.5, run.x[0], 1e-9)
        assertEquals(-8.6, run.z[0], 1e-9)
        assertEquals(99 * 0.0025, run.duration, 1e-9)
    }

    @Test
    fun parsesSemicolonDelimitedWithDecimalPoint() {
        val text = "t;x;y;z\n" + (0 until 50).joinToString("\n") { "${it * 0.01};1;2;3" }
        val run = AccelerometerParser.parseCsvText(text)
        assertEquals(50, run.size)
        assertEquals(2.0, run.y[10], 1e-9)
    }

    @Test
    fun parsesMagnitudeOnlyFile() {
        val text = "time,absolute acceleration\n" + (0 until 50).joinToString("\n") { "${it * 0.01},${9.8 + it}" }
        val run = AccelerometerParser.parseCsvText(text)
        assertEquals(50, run.size)
        assertEquals(9.8, run.x[0], 1e-9)
        // Only one axis carries the value, so |a| and summed power match the file.
        assertEquals(0.0, run.y[0], 0.0)
        assertEquals(9.8, run.magnitude()[0], 1e-9)
    }

    @Test
    fun skipsRowsWithNonFiniteValues() {
        val rows = (0 until 50).map { "${it * 0.01},1,2,3" }.toMutableList()
        rows[10] = "0.10,NaN,2,3"
        rows[11] = "0.11,1,Infinity,3"
        val run = AccelerometerParser.parseCsvText("time,x,y,z\n" + rows.joinToString("\n"))
        assertEquals(48, run.size)
        assertTrue(run.x.all { it.isFinite() } && run.y.all { it.isFinite() })
    }

    @Test
    fun badAxisCellSkipsRowInsteadOfSwitchingToMagnitude() {
        val rows = (0 until 50).map { "${it * 0.01},0,0,9.81,9.81" }.toMutableList()
        rows[7] = "0.07,,0,9.81,9.81"
        val run = AccelerometerParser.parseCsvText("time,x,y,z,absolute\n" + rows.joinToString("\n"))
        assertEquals(49, run.size)
        assertTrue(run.x.all { it == 0.0 })
        assertTrue(run.z.all { it == 9.81 })
    }

    @Test
    fun lineReaderBoundsLineLengthAndHandlesCrlf() {
        val lines = AccelerometerParser.LineReader("a,b\r\n1,2\n\n3,4".reader())
        assertEquals("a,b", lines.readLine())
        assertEquals("1,2", lines.readLine())
        assertEquals("", lines.readLine())
        assertEquals("3,4", lines.readLine())
        assertEquals(null, lines.readLine())

        val cr = AccelerometerParser.LineReader("a,b\r1,2\r3,4".reader())
        assertEquals("a,b", cr.readLine())
        assertEquals("1,2", cr.readLine())
        assertEquals("3,4", cr.readLine())
        assertEquals(null, cr.readLine())

        val peek = AccelerometerParser.LineReader("h\nrow".reader())
        val first = peek.readLine()!!
        peek.pushBack(first)
        assertEquals("h", peek.readLine())
        assertEquals("row", peek.readLine())

        val huge = AccelerometerParser.LineReader("x".repeat(200_000).reader(), maxChars = 1000)
        try {
            huge.readLine()
            fail("expected ImportException")
        } catch (e: ImportException) {
            // expected
        }
    }

    @Test
    fun parsesCarriageReturnSeparatedCsv() {
        val text = "time,x,y,z\r" + (0 until 50).joinToString("\r") { "${it * 0.01},1,2,3" }
        val run = AccelerometerParser.parseCsvText(text)
        assertEquals(50, run.size)
    }

    @Test
    fun crlfSplitAcrossBuffersIsOneSeparator() {
        // 65_535 'a's then CRLF: the CR is the last char of the first 64K buffer, the LF starts the next.
        val text = "a".repeat(65_535) + "\r\n" + "b\n"
        val lines = AccelerometerParser.LineReader(text.reader(), maxChars = 100_000)
        assertEquals(65_535, lines.readLine()!!.length)
        assertEquals("b", lines.readLine())
        assertEquals(null, lines.readLine())
    }

    @Test
    fun rejectsInconsistentTimestamps() {
        val rows = (0 until 50).map { "${it * 0.01},1,2,3" } + "1700000000.0,1,2,3"
        try {
            AccelerometerParser.parseCsvText("time,x,y,z\n" + rows.joinToString("\n"))
            fail("expected ImportException")
        } catch (e: ImportException) {
            assertTrue(e.message!!.contains("Timestamps"))
        }
    }

    @Test
    fun rejectsFilesWithoutTime() {
        try {
            AccelerometerParser.parseCsvText("a,b,c\n1,2,3\n")
            fail("expected ImportException")
        } catch (e: ImportException) {
            // expected
        }
    }

    @Test
    fun parsesPhyphoxZipWithMetadata() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("Raw Data.csv"))
            zip.write(phyphoxCsv(200).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("meta/device.csv"))
            zip.write("\"property\",\"value\"\n\"deviceModel\",\"SM-S911U1\"\n\"deviceBrand\",\"samsung\"\n\"accelerometer Name\",\"lsm6dso\"\n".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("meta/time.csv"))
            zip.write("\"event\",\"experiment time\",\"system time\",\"system time text\"\n\"START\",0.0,1792530831.678,\"2026-10-20 14:13:51.678 UTC-07:00\"\n".toByteArray())
            zip.closeEntry()
        }
        val run = AccelerometerParser.parseZip(bytes.toByteArray().inputStream())
        assertEquals(200, run.series.size)
        assertEquals("samsung SM-S911U1", run.deviceModel)
        assertEquals("lsm6dso", run.sensorName)
        assertNotNull(run.recordedAt)
    }

    @Test
    fun zipSurvivesMalformedAuxiliaryTable() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("junk.csv"))
            zip.write("x".repeat(AccelerometerParser.MAX_LINE_CHARS + 10).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("Raw Data.csv"))
            zip.write(phyphoxCsv(100).toByteArray())
            zip.closeEntry()
        }
        val run = AccelerometerParser.parseZip(bytes.toByteArray().inputStream())
        assertEquals(100, run.series.size)
    }

    @Test
    fun detectsZipByContentWithoutExtension() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("Raw Data.csv"))
            zip.write(phyphoxCsv(100).toByteArray())
            zip.closeEntry()
        }
        val run = AccelerometerParser.parse(bytes.toByteArray().inputStream(), "123")
        assertEquals(100, run.series.size)
    }

    @Test
    fun stripsUtf8Bom() {
        val text = "\uFEFFtime,x,y,z\n" + (0 until 50).joinToString("\n") { "${it * 0.01},1,2,3" }
        val run = AccelerometerParser.parseCsvText(text)
        assertEquals(50, run.size)
    }

    @Test
    fun headerlessScientificNotationIsData() {
        val text = (0 until 50).joinToString("\n") { "${"%.3E".format(it * 0.01)},1,2,3" }
        val run = AccelerometerParser.parseCsvText(text)
        assertEquals(50, run.size)
        assertEquals(0.0, run.time[0], 1e-9)
    }

    @Test
    fun defaultNameStripsPhyphoxPrefix() {
        assertEquals("2026-10-20 14-14-14 Baseline", DatasetRepository.defaultName("Acceleration with g 2026-10-20 14-14-14 Baseline.zip"))
        assertEquals("run1", DatasetRepository.defaultName("run1.csv"))
    }
}
