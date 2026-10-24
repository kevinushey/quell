package com.kevinushey.quell.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Result of a tap test: the structure's natural frequency and damping from its free decay. */
data class RingDownResult(
    val naturalFreq: Double,
    val dampingRatio: Double,
    /** Time of the detected impulse, seconds from the start of the signal. */
    val impulseTime: Double,
    /** Envelope samples used for the fit: (time, amplitude). */
    val envelopeTimes: DoubleArray,
    val envelopeAmps: DoubleArray,
    /** Fitted exponential at the envelope times. */
    val fittedAmps: DoubleArray,
    /** Coefficient of determination of the log-linear fit; low values mean the decay was not clean. */
    val fitR2: Double,
    /** A second strong frequency in the decay (beating), NaN if none. An absorber coupled to the mode produces one. */
    val secondaryFreq: Double = Double.NaN,
) {
    val hasBeating: Boolean get() = !secondaryFreq.isNaN()

    val qFactor: Double get() = if (dampingRatio > 0) 1 / (2 * dampingRatio) else Double.NaN
    val halfPowerBandwidth: Double get() = 2 * dampingRatio * naturalFreq
}

object RingDown {

    /** Index of the first sample whose deviation from the running level exceeds `threshold` m/s^2; -1 if none. */
    fun detectImpulse(signal: DoubleArray, threshold: Double): Int {
        if (signal.size < 8)
            return -1
        val mean = signal.average()
        for (i in signal.indices)
            if (abs(signal[i] - mean) > threshold)
                return i
        return -1
    }

    /**
     * Fits an exponentially decaying sinusoid to the free vibration after an
     * impulse. `signal` must be uniformly sampled; the impulse is detected
     * automatically unless `impulseIndex` is given.
     */
    fun analyze(
        signal: DoubleArray,
        sampleRate: Double,
        impulseIndex: Int = -1,
        impulseThreshold: Double = 2.0,
        windowSeconds: Double = 3.0,
        minFreq: Double = 2.0,
    ): RingDownResult? {
        val start = if (impulseIndex >= 0) impulseIndex else detectImpulse(signal, impulseThreshold)
        if (start < 0)
            return null

        // Skip the contact transient, then take a window of free decay.
        val skip = (0.02 * sampleRate).toInt()
        val from = (start + skip).coerceAtMost(signal.size - 1)
        val count = minOf((windowSeconds * sampleRate).toInt(), signal.size - from)
        if (count < 64)
            return null
        val n = FFT.nextPowerOfTwo(count)
        val mean = signal.copyOfRange(from, from + count).average()
        val re = DoubleArray(n) { if (it < count) signal[from + it] - mean else 0.0 }
        val im = DoubleArray(n)
        FFT.transform(re, im)

        // Dominant frequency of the decay.
        val minBin = (minFreq * n / sampleRate).toInt().coerceAtLeast(1)
        var peak = minBin
        var peakMag = 0.0
        for (k in minBin until n / 2) {
            val m = re[k] * re[k] + im[k] * im[k]
            if (m > peakMag) {
                peakMag = m
                peak = k
            }
        }
        val fn = peak * sampleRate / n
        if (fn <= 0)
            return null

        // Second peak within +/-40% of fn and at least a quarter of its magnitude: two coupled modes.
        var secondary = Double.NaN
        run {
            val lo = (peak * 0.6).toInt().coerceAtLeast(minBin)
            val hi = (peak * 1.4).toInt().coerceAtMost(n / 2 - 1)
            val exclude = (peak * 0.06).toInt().coerceAtLeast(2)
            var best = -1
            var bestMag = 0.25 * peakMag
            for (k in lo..hi) {
                if (abs(k - peak) <= exclude)
                    continue
                val m = re[k] * re[k] + im[k] * im[k]
                val isLocalMax = m >= re[k - 1] * re[k - 1] + im[k - 1] * im[k - 1] && m >= re[k + 1] * re[k + 1] + im[k + 1] * im[k + 1]
                if (isLocalMax && m > bestMag) {
                    bestMag = m
                    best = k
                }
            }
            if (best > 0)
                secondary = best * sampleRate / n
        }

        // Tapered band-pass around fn and an analytic (Hilbert) signal in one pass: keep positive
        // frequencies only, doubled, with a cosine-edged mask; the inverse FFT's magnitude is the envelope.
        val halfWidth = (peak * 0.3).coerceAtLeast(3.0)
        for (k in 0 until n) {
            val weight = if (k == 0 || k > n / 2) 0.0 else {
                val d = abs(k - peak) / halfWidth
                when {
                    d <= 0.5 -> 2.0
                    d >= 1.0 -> 0.0
                    else -> 2.0 * 0.5 * (1 + Math.cos(PI * (d - 0.5) / 0.5))
                }
            }
            re[k] *= weight
            im[k] *= weight
        }
        for (k in 0 until n)
            im[k] = -im[k]
        FFT.transform(re, im)
        val envelope = DoubleArray(count) { sqrt(re[it] * re[it] + im[it] * im[it]) / n }

        // Fit ln(A) = ln(A0) - zeta*wn*t, skipping the filter transient and the tail below 10% of peak.
        val skipCycles = (1.5 * sampleRate / fn).toInt()
        val envMax = envelope.max()
        val keep = (skipCycles until count).filter { envelope[it] > 0.1 * envMax }
        if (keep.size < 8)
            return null
        val xs = keep.map { it / sampleRate }
        val ys = keep.map { ln(envelope[it]) }
        val mx = xs.average()
        val my = ys.average()
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in xs.indices) {
            sxy += (xs[i] - mx) * (ys[i] - my)
            sxx += (xs[i] - mx) * (xs[i] - mx)
            syy += (ys[i] - my) * (ys[i] - my)
        }
        if (sxx <= 0)
            return null
        val slope = sxy / sxx
        val intercept = my - slope * mx
        val r2 = if (syy > 0) (sxy * sxy) / (sxx * syy) else 0.0
        val zeta = -slope / (2 * PI * fn)
        // Thin the envelope for display/storage.
        val stride = (keep.size / 200).coerceAtLeast(1)
        val picked = keep.indices.filter { it % stride == 0 }
        val envT = DoubleArray(picked.size) { xs[picked[it]] }
        val envA = DoubleArray(picked.size) { envelope[keep[picked[it]]] }
        val fit = DoubleArray(picked.size) { exp(intercept + slope * xs[picked[it]]) }
        return RingDownResult(fn, zeta, start / sampleRate, envT, envA, fit, r2, secondary)
    }

    /** Longest-variance axis of a 3-axis recording; the tap excites that one most. */
    fun dominantAxis(x: DoubleArray, y: DoubleArray, z: DoubleArray): DoubleArray {
        fun variance(a: DoubleArray): Double {
            val m = a.average()
            return a.sumOf { (it - m) * (it - m) }
        }
        val vx = variance(x)
        val vy = variance(y)
        val vz = variance(z)
        return when {
            vx >= vy && vx >= vz -> x
            vy >= vz -> y
            else -> z
        }
    }
}
