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
     * Analyzes the trim window [start, end] (seconds from the first sample) and
     * reports impulse times in recording time, so they line up with the trim
     * dialog, the waveform and the antinode location marks.
     */
    fun analyzeWindow(series: TimeSeries, start: Double, end: Double, threshold: Double): TapSummary {
        val slice = series.slice(start, end)
        val summary = analyze(slice, threshold)
        if (slice.size == 0 || series.size == 0)
            return summary
        val offset = slice.time.first() - series.time.first()
        if (offset == 0.0)
            return summary
        return TapSummary(summary.taps.map { it.copy(impulseTime = it.impulseTime + offset) }, summary.sampleRate)
    }

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
        // The impulse test compares against the mean of the yet-unscanned suffix; keep that sum
        // running instead of copying and re-averaging the suffix for every tap.
        var suffixSum = signal.sum()
        while (from < signal.size - 64) {
            val mean = suffixSum / (signal.size - from)
            val at = RingDown.detectImpulse(signal, threshold, from, mean)
            if (at < 0)
                break
            RingDown.analyze(signal, fs, impulseIndex = at, windowSeconds = windowSeconds)
                ?.takeIf { it.fitR2 >= minR2 && it.dampingRatio > 0 }
                ?.let { results.add(it) }
            val next = minOf(at + window, signal.size)
            for (i in from until next)
                suffixSum -= signal[i]
            from = next
        }
        return TapSummary(results, fs)
    }
}

/**
 * Live tap analysis during a recording. Taps whose ring-down window has fully
 * elapsed are final and never re-analyzed; each update only looks at the
 * capture from the last final tap onward. Times are in the recording's base.
 */
class TapTracker(private val threshold: Double, private val windowSeconds: Double = 3.0, private val minR2: Double = 0.7) {
    private val completed = ArrayList<RingDownResult>()
    private var sampleRate = 0.0

    /** Recording time before which every tap is final; the caller may snapshot from here. */
    var resumeFrom: Double = 0.0
        private set

    fun update(series: TimeSeries): TapSummary {
        if (series.size < 64)
            return TapSummary(completed.toList(), sampleRate)
        var start = 0
        while (start < series.size && series.time[start] < resumeFrom)
            start++
        start = start.coerceAtMost(series.size - 64)
        val sub = if (start == 0) series else TimeSeries(
            series.time.copyOfRange(start, series.size),
            series.x.copyOfRange(start, series.size),
            series.y.copyOfRange(start, series.size),
            series.z.copyOfRange(start, series.size),
        )

        val summary = TapAnalysis.analyze(sub, threshold, windowSeconds, minR2)
        if (summary.sampleRate > 0)
            sampleRate = summary.sampleRate
        val origin = sub.time.first()
        val end = series.time.last()
        val pending = ArrayList<RingDownResult>()
        for (tap in summary.taps) {
            // Only the impulse moves into recording time; envelope times stay relative to the tap.
            val shifted = tap.copy(impulseTime = tap.impulseTime + origin)
            if (shifted.impulseTime + windowSeconds <= end) {
                completed.add(shifted)
                resumeFrom = maxOf(resumeFrom, shifted.impulseTime + windowSeconds)
            } else {
                pending.add(shifted)
            }
        }
        // With nothing unfolding, anything older than two windows has had every chance to be detected.
        if (pending.isEmpty())
            resumeFrom = maxOf(resumeFrom, end - 2 * windowSeconds)
        return TapSummary(completed + pending, sampleRate)
    }
}
