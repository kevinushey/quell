package com.kevinushey.quell.dsp

import kotlin.math.ln
import kotlin.math.sqrt

/** A resonance peak with its half-power bandwidth and the damping ratio implied by it. */
data class ModalPeak(
    val freq: Double,
    val power: Double,
    /** -3 dB (half power) bandwidth in Hz, or NaN if a band edge was hit before the power halved. */
    val bandwidth: Double,
) {
    val dampingRatio: Double get() = if (bandwidth.isNaN() || freq <= 0) Double.NaN else bandwidth / (2 * freq)
    val qFactor: Double get() = if (dampingRatio.isNaN() || dampingRatio <= 0) Double.NaN else 1 / (2 * dampingRatio)
}

/** What a tuned absorber does to one mode: the original peak becomes two with a notch between. */
data class SplitPeak(
    val lower: ModalPeak,
    val upper: ModalPeak,
    val notchFreq: Double,
    val notchPower: Double,
) {
    /**
     * Undamped-absorber relation for an absorber tuned to the structure:
     * f1*f2 = fn^2 and f1^2 + f2^2 = fn^2 (2 + mu). The fallback when no
     * baseline frequency is known; Tuning.absorber drops the tuning assumption.
     */
    val impliedNaturalFreq: Double get() = sqrt(lower.freq * upper.freq)
    val impliedMassRatio: Double get() = (lower.freq * lower.freq + upper.freq * upper.freq) / (lower.freq * upper.freq) - 2.0
}

object Modal {

    /** Tallest peak within [lo, hi] and its half-power bandwidth measured on the PSD. */
    fun dominantPeak(s: Spectrum, lo: Double, hi: Double): ModalPeak? {
        val idx = s.freq.indices.filter { s.freq[it] in lo..hi }
        if (idx.size < 3)
            return null
        val peak = idx.maxByOrNull { s.power[it] } ?: return null
        return peakAt(s, peak, idx.first(), idx.last())
    }

    fun peakAt(s: Spectrum, peak: Int, first: Int, last: Int): ModalPeak {
        val half = s.power[peak] / 2
        val left = crossing(s, peak, -1, first, half)
        val right = crossing(s, peak, +1, last, half)
        val bw = if (left.isNaN() || right.isNaN()) Double.NaN else right - left
        return ModalPeak(refinedFreq(s, peak), s.power[peak], bw)
    }

    /**
     * Peak frequency between bins, from a parabola through the log power of
     * the peak bin and its neighbours. Absorber tuning compares frequencies a
     * few percent apart, and a bin is about 1.6% of 25 Hz at 1024 points.
     */
    private fun refinedFreq(s: Spectrum, peak: Int): Double {
        if (peak <= 0 || peak >= s.size - 1)
            return s.freq[peak]
        val (p0, p1, p2) = Triple(s.power[peak - 1], s.power[peak], s.power[peak + 1])
        if (!(p0 > 0 && p1 > 0 && p2 > 0))
            return s.freq[peak]
        val y0 = ln(p0)
        val y1 = ln(p1)
        val y2 = ln(p2)
        val curvature = y0 - 2 * y1 + y2
        if (!(curvature < 0))
            return s.freq[peak]
        val delta = (0.5 * (y0 - y2) / curvature).coerceIn(-0.5, 0.5)
        return s.freq[peak] + delta * (s.freq[peak + 1] - s.freq[peak])
    }

    /** Frequency where power falls to `level` walking from `peak` in `dir`; linear interpolation between bins. */
    private fun crossing(s: Spectrum, peak: Int, dir: Int, limit: Int, level: Double): Double {
        var i = peak
        while (true) {
            val next = i + dir
            if (next < 0 || next >= s.size || (dir < 0 && next < limit) || (dir > 0 && next > limit))
                return Double.NaN
            if (s.power[next] <= level) {
                val frac = (s.power[i] - level) / (s.power[i] - s.power[next])
                return s.freq[i] + frac * (s.freq[next] - s.freq[i])
            }
            i = next
        }
    }

    /**
     * Looks for two peaks with a notch between them inside [lo, hi]. The
     * notch must sit at least `minNotchDb` below the lower of the two peaks.
     */
    fun splitPeak(s: Spectrum, lo: Double, hi: Double, minNotchDb: Double = 3.0): SplitPeak? {
        val idx = s.freq.indices.filter { s.freq[it] in lo..hi }
        if (idx.size < 5)
            return null
        val first = idx.first()
        val last = idx.last()
        val sub = s.restrictTo(lo, hi)
        val separation = (sub.size / 10).coerceAtLeast(2)
        val peaks = Peaks.find(sub.freq, sub.power, 2, separation)
        if (peaks.size < 2)
            return null

        val (a, b) = peaks.sortedBy { it.freq }
        val ia = first + a.index
        val ib = first + b.index
        var notch = ia
        for (i in ia..ib)
            if (s.power[i] < s.power[notch])
                notch = i
        val lowerPeak = minOf(s.power[ia], s.power[ib])
        val depthDb = Normalization.toDb(lowerPeak) - Normalization.toDb(s.power[notch])
        if (depthDb < minNotchDb)
            return null
        return SplitPeak(peakAt(s, ia, first, notch), peakAt(s, ib, notch, last), s.freq[notch], s.power[notch])
    }
}
