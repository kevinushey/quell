package com.kevinushey.quell.dsp

/** Welch's averaged-periodogram power spectral density estimate. */
object Welch {

    /**
     * Number of statistically independent segments that `segments` overlapping
     * ones are worth. Overlapping segments share samples, so their periodograms
     * correlate by the squared window autocorrelation at the segment step
     * (Welch 1967), and the variance reduction falls short of 1/K.
     */
    fun effectiveSegments(segments: Int, segmentSize: Int, window: WindowType, overlap: Double): Double {
        if (segments <= 1)
            return segments.toDouble().coerceAtLeast(0.0)
        val n = segmentSize
        val step = (n * (1.0 - overlap)).toInt().coerceAtLeast(1)
        val w = window.coefficients(n)
        val energy = w.sumOf { it * it }

        var sum = 0.0
        var m = 1
        while (m < segments && m * step < n) {
            val d = m * step
            var c = 0.0
            for (i in 0 until n - d)
                c += w[i] * w[i + d]
            val rho = c / energy
            sum += (1.0 - m.toDouble() / segments) * rho * rho
            m++
        }
        return segments / (1.0 + 2.0 * sum)
    }

    /**
     * Independent values that a `bins`-wide moving average over adjacent PSD
     * bins is worth. Windowing correlates neighbouring bins (for Hann, about
     * 0.44 for adjacent bins), so smoothing buys less than one per bin.
     */
    fun effectiveSmoothingBins(bins: Int, segmentSize: Int, window: WindowType): Double {
        if (bins <= 1)
            return 1.0
        val n = segmentSize
        val w2 = window.coefficients(n).map { it * it }
        val energy = w2.sum()

        var sum = 0.0
        for (k in 1 until bins) {
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                val phase = 2.0 * Math.PI * k * i / n
                re += w2[i] * Math.cos(phase)
                im += w2[i] * Math.sin(phase)
            }
            val c = (re * re + im * im) / (energy * energy)
            sum += (1.0 - k.toDouble() / bins) * c
        }
        return bins / (1.0 + 2.0 * sum)
    }

    fun psd(
        signal: DoubleArray,
        sampleRate: Double,
        segmentSize: Int,
        window: WindowType = WindowType.HANN,
        overlap: Double = 0.5,
        detrend: Boolean = true,
    ): Spectrum {
        require(FFT.isPowerOfTwo(segmentSize)) { "segmentSize must be a power of two" }
        require(overlap in 0.0..0.95)

        val n = segmentSize
        val half = n / 2
        val freq = DoubleArray(half + 1) { it * sampleRate / n }
        val acc = DoubleArray(half + 1)
        if (signal.isEmpty() || sampleRate <= 0.0)
            return Spectrum(freq, acc)

        val w = window.coefficients(n)
        val step = (n * (1.0 - overlap)).toInt().coerceAtLeast(1)

        // A short recording gets one segment: its real samples are detrended and
        // windowed first, then zero padded, so the gravity offset does not become a step.
        val count = minOf(signal.size, n)
        val segments = if (signal.size < n) 1 else (signal.size - n) / step + 1
        var windowPower = 0.0
        for (i in 0 until count)
            windowPower += w[i] * w[i]

        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (s in 0 until segments) {
            val start = s * step
            var mean = 0.0
            if (detrend) {
                for (i in 0 until count)
                    mean += signal[start + i]
                mean /= count
            }
            for (i in 0 until n) {
                re[i] = if (i < count) (signal[start + i] - mean) * w[i] else 0.0
                im[i] = 0.0
            }

            FFT.transform(re, im)
            for (k in 0..half)
                acc[k] += re[k] * re[k] + im[k] * im[k]
        }

        val scale = 1.0 / (segments * sampleRate * windowPower)
        for (k in 0..half) {
            val oneSided = if (k == 0 || k == half) 1.0 else 2.0
            acc[k] *= scale * oneSided
        }
        return Spectrum(freq, acc, segments)
    }
}
