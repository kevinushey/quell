package com.kevinushey.quell

import com.kevinushey.quell.data.TimeSeries
import com.kevinushey.quell.ui.TapAnalysis
import com.kevinushey.quell.ui.TapSummary
import com.kevinushey.quell.ui.TapTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class TapTrackerTest {

    private val fs = 400.0
    private val taps = listOf(2.0, 6.0, 10.0, 14.0)

    private fun capture(seconds: Double, strikes: List<Double> = taps): TimeSeries {
        val n = (seconds * fs).toInt()
        val time = DoubleArray(n) { it / fs }
        val z = DoubleArray(n) { i ->
            var v = 9.81
            for (tap in strikes) {
                val dt = time[i] - tap
                if (dt >= 0)
                    v += 30.0 * exp(-0.02 * 2 * PI * 25.0 * dt) * sin(2 * PI * 25.0 * dt)
            }
            v
        }
        return TimeSeries(time, DoubleArray(n), DoubleArray(n), z)
    }

    /** Runs a tracker over a growing capture in 0.5 s steps, as the recorder would. */
    private fun track(seconds: Double, strikes: List<Double> = taps): TapSummary {
        val tracker = TapTracker(threshold = 5.0)
        var last = tracker.update(capture(1.0, strikes))
        var t = 1.5
        while (t <= seconds) {
            val whole = capture(t, strikes)
            // Feed only what the recorder would hand back: samples from the tracker's resume point.
            var start = 0
            while (start < whole.size && whole.time[start] < tracker.resumeFrom)
                start++
            val part = TimeSeries(
                whole.time.copyOfRange(start, whole.size),
                whole.x.copyOfRange(start, whole.size),
                whole.y.copyOfRange(start, whole.size),
                whole.z.copyOfRange(start, whole.size),
            )
            last = tracker.update(part)
            t += 0.5
        }
        return last
    }

    @Test
    fun incrementalTrackingMatchesFullAnalysis() {
        val full = TapAnalysis.analyze(capture(20.0), threshold = 5.0)
        assertEquals(4, full.count)
        assertEquals(25.0, full.meanFreq, 0.5)

        val last = track(20.0)
        assertEquals(4, last.count)
        assertEquals(full.meanFreq, last.meanFreq, 0.2)
        assertEquals(taps[0], last.taps.minOf { it.impulseTime }, 0.05)
        // Envelope times are "time after tap", regardless of where the tracker resumed.
        for (tap in last.taps)
            assertTrue("envelope not relative: ${tap.envelopeTimes.first()}", tap.envelopeTimes.first() < 3.0)
    }

    @Test
    fun aRejectedDoubleStrikeAddsNoPhantomTap() {
        // Strikes at 2.0 and 3.5 s share one window, whose envelope fit fails; clean taps follow at 10 and 14 s.
        val strikes = listOf(2.0, 3.5, 10.0, 14.0)
        fun times(s: TapSummary) = s.taps.map { Math.round(it.impulseTime * 10) / 10.0 }
        val full = TapAnalysis.analyze(capture(18.0, strikes), threshold = 5.0)
        assertEquals(listOf(10.0, 14.0), times(full))

        // The second strike sits inside the rejected window, so the tracker must never resume there and count it.
        assertEquals(times(full), times(track(18.0, strikes)))
    }
}
