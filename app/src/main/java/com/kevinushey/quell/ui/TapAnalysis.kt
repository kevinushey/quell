package com.kevinushey.quell.ui

import com.kevinushey.quell.data.TimeSeries
import com.kevinushey.quell.dsp.Resampler
import com.kevinushey.quell.dsp.RingDown
import com.kevinushey.quell.dsp.RingDownResult
import kotlin.math.sqrt

/** All taps found in one recording, with their pooled modal estimate. */
class TapSummary(val taps: List<RingDownResult>, val sampleRate: Double) {
    val count: Int get() = taps.size
    val meanFreq: Double get() = taps.map { it.naturalFreq }.average()
    val meanDamping: Double get() = taps.map { it.dampingRatio }.average()
    val sdDamping: Double
        get() {
            if (taps.size < 2)
                return Double.NaN
            val m = meanDamping
            return sqrt(taps.sumOf { (it.dampingRatio - m) * (it.dampingRatio - m) } / (taps.size - 1))
        }
    val meanQ: Double get() = if (meanDamping > 0) 1 / (2 * meanDamping) else Double.NaN
    val beatingTaps: Int get() = taps.count { it.hasBeating }

    /** Mean of the two decay frequencies when beating is present, lower first. */
    val beatFrequencies: Pair<Double, Double>?
        get() {
            val b = taps.filter { it.hasBeating }
            if (b.isEmpty())
                return null
            val lo = b.map { minOf(it.naturalFreq, it.secondaryFreq) }.average()
            val hi = b.map { maxOf(it.naturalFreq, it.secondaryFreq) }.average()
            return lo to hi
        }

    /** Mass ratio implied by the two decay frequencies, as for a split PSD peak. */
    val impliedMassRatio: Double
        get() {
            val (f1, f2) = beatFrequencies ?: return Double.NaN
            return (f1 * f1 + f2 * f2) / (f1 * f2) - 2.0
        }
}

object TapAnalysis {

    /**
     * Resamples, picks the axis the taps excited most, then walks through the
     * recording analyzing each impulse in turn. Poor fits are kept out so a
     * fumbled tap does not drag the average.
     */
    fun analyze(series: TimeSeries, threshold: Double, windowSeconds: Double = 3.0, minR2: Double = 0.7): TapSummary {
        val fs = Resampler.estimateSampleRate(series.time)
        if (fs <= 0 || series.size < 64)
            return TapSummary(emptyList(), fs)
        val r = Resampled.from(series)
        val signal = RingDown.dominantAxis(r.x, r.y, r.z)

        val results = ArrayList<RingDownResult>()
        var from = 0
        // A bogus sample rate (e.g. nanosecond timestamps) can round the window to 0; always advance.
        val window = (windowSeconds * fs).toInt().coerceAtLeast(1)
        while (from < signal.size - 64) {
            val rel = RingDown.detectImpulse(signal.copyOfRange(from, signal.size), threshold)
            if (rel < 0)
                break
            val at = from + rel
            RingDown.analyze(signal, fs, impulseIndex = at, windowSeconds = windowSeconds)
                ?.takeIf { it.fitR2 >= minR2 && it.dampingRatio > 0 }
                ?.let { results.add(it) }
            from = at + window
        }
        return TapSummary(results, fs)
    }
}
