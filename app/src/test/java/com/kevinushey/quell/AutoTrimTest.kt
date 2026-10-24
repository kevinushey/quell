package com.kevinushey.quell

import com.kevinushey.quell.dsp.AutoTrim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class AutoTrimTest {

    private val fs = 400.0

    /** A run built from (seconds, noise amplitude) phases, with optional spikes at given seconds. */
    private fun run(phases: List<Pair<Double, Double>>, spikes: List<Double> = emptyList()): Pair<DoubleArray, DoubleArray> {
        val total = phases.sumOf { it.first }
        val n = (total * fs).toInt()
        val rnd = java.util.Random(7)
        val time = DoubleArray(n) { it / fs }
        val mag = DoubleArray(n)
        var i = 0
        for ((seconds, amp) in phases) {
            val count = (seconds * fs).toInt()
            for (k in 0 until count) {
                if (i >= n)
                    break
                // Gravity plus broadband noise plus a 25 Hz tone scaled with the noise, like a drive.
                mag[i] = 9.81 + amp * rnd.nextGaussian() + 0.5 * amp * sin(2 * Math.PI * 25 * time[i])
                i++
            }
        }
        for (s in spikes) {
            val at = (s * fs).toInt()
            for (k in at until minOf(n, at + 20))
                mag[k] += 15.0
        }
        return time to mag
    }

    @Test
    fun findsTheDriveBetweenDoorSlamsAndIdling() {
        // 10 s idle (door slam at 2 s), 40 s drive, 6 s parking shuffle with another slam.
        val (t, m) = run(listOf(10.0 to 0.02, 40.0 to 0.6, 6.0 to 0.05), spikes = listOf(2.0, 53.0))
        val w = AutoTrim.steadyWindow(t, m)
        assertNotNull(w)
        assertEquals(10.0, w!!.start, 1.0)
        assertEquals(50.0, w.end, 1.0)
        assertTrue("range ${w.rangeDb}", w.rangeDb <= AutoTrim.BAND_DB)
    }

    @Test
    fun prefersTheDriveEvenWhenIdlingLasted() {
        // 40 s of idling is longer than the 30 s drive, but quieter: the drive wins.
        val (t, m) = run(listOf(40.0 to 0.02, 30.0 to 0.6))
        val w = AutoTrim.steadyWindow(t, m)!!
        assertEquals(40.0, w.start, 1.0)
        assertEquals(70.0, w.end, 1.0)
    }

    @Test
    fun levelsAreAmplitudeDecibels() {
        // A tenfold RMS step is 20 dB in the app's convention, well past the band, so it splits the run.
        val (t, m) = run(listOf(20.0 to 0.05, 30.0 to 0.5))
        val w = AutoTrim.steadyWindow(t, m)!!
        assertEquals(20.0, w.start, 1.0)
        assertEquals(50.0, w.end, 1.0)
    }

    @Test
    fun aSteadyRunIsKeptWhole() {
        val (t, m) = run(listOf(30.0 to 0.5))
        val w = AutoTrim.steadyWindow(t, m)!!
        assertTrue(w.coversAll(30.0))
        // Edges snap to the run itself, not to the last whole analysis cell.
        assertEquals(0.0, w.start, 0.0)
        assertEquals(t.last(), w.end, 0.0)
    }

    @Test
    fun aBumpInTheMiddleSplitsTheRunAndTheLongerSideWins() {
        val (t, m) = run(listOf(20.0 to 0.5, 35.0 to 0.5), spikes = listOf(20.0))
        val w = AutoTrim.steadyWindow(t, m)!!
        assertEquals(20.5, w.start, 1.0)
        assertEquals(55.0, w.end, 1.0)
    }

    @Test
    fun tooShortOrEmptyGivesNothing() {
        val (t, m) = run(listOf(4.0 to 0.5))
        assertNull(AutoTrim.steadyWindow(t, m))
        assertNull(AutoTrim.steadyWindow(DoubleArray(0), DoubleArray(0)))
    }
}
