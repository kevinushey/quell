package com.kevinushey.quell

import com.kevinushey.quell.data.AccelerometerParser
import com.kevinushey.quell.data.DatasetRepository
import com.kevinushey.quell.data.ImportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
