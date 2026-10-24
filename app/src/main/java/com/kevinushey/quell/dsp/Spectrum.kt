package com.kevinushey.quell.dsp

/** One-sided spectrum. `power` is in units^2/Hz unless normalized. */
class Spectrum(
    val freq: DoubleArray,
    val power: DoubleArray,
    val segments: Int = 0,
    /** Real samples under the window per segment (0 = unknown, assume the FFT length). */
    val windowLength: Int = 0,
) {
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

    /**
     * Resample this spectrum onto another frequency grid. Frequencies outside
     * this spectrum's support (e.g. past its Nyquist) become NaN rather than a
     * repeated endpoint, so comparisons cannot fabricate differences there.
     */
    fun onGrid(grid: DoubleArray): Spectrum =
        Spectrum(grid, DoubleArray(grid.size) { if (supports(grid[it])) interpolate(grid[it]) else Double.NaN }, segments)

    fun supports(f: Double): Boolean = size > 0 && f >= freq[0] && f <= freq[size - 1]

    /** True when [lo, hi] is a proper band inside this spectrum's range that contains at least one bin. */
    fun covers(lo: Double, hi: Double): Boolean =
        size > 0 && lo < hi && lo >= freq[0] - binWidth / 2 && hi <= freq[size - 1] + binWidth / 2 && freq.any { it in lo..hi }

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

    /** RMS of the underlying signal within [lo, hi]: sqrt of the integrated PSD; NaN if the band is not covered. */
    fun bandRms(lo: Double, hi: Double): Double {
        if (!covers(lo, hi))
            return Double.NaN
        var sum = 0.0
        for (i in 0 until size)
            if (freq[i] in lo..hi)
                sum += power[i]
        return Math.sqrt(sum * binWidth)
    }

    companion object {
        /**
         * Mean number of bins actually combined by `smoothed(bins)` on a
         * spectrum of `size` bins, accounting for the clipped windows at both
         * ends and for ranges narrower than the window.
         */
        fun effectiveSmoothingBins(size: Int, bins: Int): Int {
            if (bins <= 1 || size <= 0)
                return 1
            val half = bins / 2
            var total = 0L
            for (i in 0 until size) {
                val a = (i - half).coerceAtLeast(0)
                val b = (i + half).coerceAtMost(size - 1)
                total += b - a + 1
            }
            return (total / size).toInt().coerceAtLeast(1)
        }
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
