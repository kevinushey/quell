package com.kevinushey.quell.dsp

/** Welch's averaged-periodogram power spectral density estimate. */
object Welch {

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
