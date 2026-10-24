package com.kevinushey.quell

import com.kevinushey.quell.dsp.RingDown
import com.kevinushey.quell.dsp.RingDownResult
import com.kevinushey.quell.ui.LocateAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class LocateAnalysisTest {

    private fun tap(time: Double, amplitude: Double, impact: Double, freq: Double = 25.0) =
        RingDownResult(freq, 0.02, time, DoubleArray(0), DoubleArray(0), DoubleArray(0), 0.95, Double.NaN, amplitude, impact)

    @Test
    fun marksSplitTheRecordingIntoLocations() {
        val marks = listOf(10.0, 20.0)
        assertEquals(0, LocateAnalysis.locationOf(3.0, marks))
        assertEquals(1, LocateAnalysis.locationOf(10.0, marks))
        assertEquals(2, LocateAnalysis.locationOf(25.0, marks))
        assertEquals(0, LocateAnalysis.locationOf(5.0, emptyList()))
    }

    @Test
    fun harderTapsDoNotWin() {
        // Location 1 tapped hard near a node; location 2 tapped gently near the antinode.
        val taps = listOf(
            tap(1.0, 1.0, 10.0), tap(4.0, 1.1, 10.0), tap(7.0, 0.9, 10.0),
            tap(12.0, 2.0, 4.0), tap(15.0, 2.2, 4.0), tap(18.0, 1.8, 4.0),
        )
        val s = LocateAnalysis.summarize(taps, listOf(10.0))
        assertEquals(2, s.locations.size)
        assertEquals(1, s.best!!.index)
        assertEquals(0.5, s.best!!.meanNormalized, 1e-9)
        assertEquals(0.2, s.shape(s.locations[0]), 1e-9)
        assertTrue(s.note, s.note.startsWith("Location 2 is closest to the antinode"))
        assertTrue(s.note, s.note.contains("400% stronger"))
    }

    @Test
    fun overlappingScatterAsksForMoreTaps() {
        val taps = listOf(
            tap(1.0, 1.0, 5.0), tap(4.0, 1.6, 5.0), tap(7.0, 1.2, 5.0),
            tap(12.0, 1.3, 5.0), tap(15.0, 1.1, 5.0), tap(18.0, 1.7, 5.0),
        )
        val s = LocateAnalysis.summarize(taps, listOf(10.0))
        assertTrue(s.note, s.note.contains("within the tap-to-tap scatter"))
    }

    @Test
    fun locationsWithoutTapsAreListedButNotRanked() {
        val s = LocateAnalysis.summarize(listOf(tap(12.0, 1.0, 5.0)), listOf(10.0, 20.0))
        assertEquals(3, s.locations.size)
        assertEquals(1, s.ranked.size)
        assertEquals(0, s.locations[0].taps)
        assertTrue(s.note, s.note.contains("Only Location 2"))
        // Taps from older fits without amplitude figures are ignored rather than ranked at NaN.
        val legacy = RingDownResult(25.0, 0.02, 12.0, DoubleArray(0), DoubleArray(0), DoubleArray(0), 0.95)
        assertTrue(LocateAnalysis.summarize(listOf(legacy), emptyList()).ranked.isEmpty())
    }

    @Test
    fun ringDownReportsHowHardTheModeRang() {
        // Quiet, then a 30 m/s^2 contact spike, then a 25 Hz decay starting at 4 m/s^2.
        val fs = 400.0
        val n = (4.0 * fs).toInt()
        val start = (1.0 * fs).toInt()
        val signal = DoubleArray(n) { i ->
            when {
                i < start -> 0.0
                i < start + 3 -> 30.0
                else -> {
                    val t = (i - start) / fs
                    4.0 * exp(-0.02 * 2 * PI * 25.0 * t) * sin(2 * PI * 25.0 * t)
                }
            }
        }
        val r = RingDown.analyze(signal, fs, impulseThreshold = 2.0)
        assertNotNull(r)
        assertEquals(25.0, r!!.naturalFreq, 0.5)
        assertTrue("impact ${r.impactPeak}", r.impactPeak >= 25.0)
        // The fit starts 20 ms after the impulse; the envelope there is a little under 4.
        assertEquals(4.0 * exp(-0.02 * 2 * PI * 25.0 * 0.02), r.initialAmplitude, 0.6)
    }
}
