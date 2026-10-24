package com.kevinushey.quell.dsp

/** One-sided spectrum. `power` is in units^2/Hz unless normalized. */
class Spectrum(val freq: DoubleArray, val power: DoubleArray, val segments: Int = 0) {
    init {
        require(freq.size == power.size)
    }

    val size: Int get() = freq.size
    val binWidth: Double get() = if (freq.size > 1) freq[1] - freq[0] else 0.0
    val maxFreq: Double get() = freq.lastOrNull() ?: 0.0

    fun map(transform: (Int, Double) -> Double): Spectrum =
        Spectrum(freq, DoubleArray(size) { transform(it, power[it]) }, segments)

    /** Linear interpolation of `power` at frequency f (clamped at the ends). */
    fun interpolate(f: Double): Double {
        if (size == 0)
            return Double.NaN
        if (f <= freq[0])
            return power[0]
        if (f >= freq[size - 1])
            return power[size - 1]

        var lo = 0
        var hi = size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (freq[mid] <= f)
                lo = mid
            else
                hi = mid
        }
        val frac = (f - freq[lo]) / (freq[hi] - freq[lo])
        return power[lo] + frac * (power[hi] - power[lo])
    }

    /** Resample this spectrum onto another spectrum's frequency grid. */
    fun onGrid(grid: DoubleArray): Spectrum = Spectrum(grid, DoubleArray(grid.size) { interpolate(grid[it]) }, segments)

    fun restrictTo(minFreq: Double, maxFreq: Double): Spectrum {
        val idx = freq.indices.filter { freq[it] in minFreq..maxFreq }
        return Spectrum(DoubleArray(idx.size) { freq[idx[it]] }, DoubleArray(idx.size) { power[idx[it]] }, segments)
    }

    /** Mean power over [lo, hi]; NaN if no bins fall inside. */
    fun meanPower(lo: Double, hi: Double): Double {
        var sum = 0.0
        var n = 0
        for (i in 0 until size) {
            if (freq[i] in lo..hi) {
                sum += power[i]
                n++
            }
        }
        return if (n > 0) sum / n else Double.NaN
    }

    /** RMS of the underlying signal within [lo, hi]: sqrt of the integrated PSD. */
    fun bandRms(lo: Double, hi: Double): Double {
        var sum = 0.0
        for (i in 0 until size)
            if (freq[i] in lo..hi)
                sum += power[i]
        return Math.sqrt(sum * binWidth)
    }

    fun smoothed(bins: Int): Spectrum {
        if (bins <= 1 || size == 0)
            return this

        val half = bins / 2
        val out = DoubleArray(size)
        for (i in 0 until size) {
            val a = (i - half).coerceAtLeast(0)
            val b = (i + half).coerceAtMost(size - 1)
            var sum = 0.0
            for (k in a..b)
                sum += power[k]
            out[i] = sum / (b - a + 1)
        }
        return Spectrum(freq, out, segments)
    }
}
