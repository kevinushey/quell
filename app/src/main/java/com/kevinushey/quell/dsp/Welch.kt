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
        val windowPower = w.sumOf { it * it }
        val step = (n * (1.0 - overlap)).toInt().coerceAtLeast(1)

        // Short recordings still get one zero-padded segment rather than nothing.
        val padded = if (signal.size < n) signal.copyOf(n) else signal
        val segments = (padded.size - n) / step + 1

        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (s in 0 until segments) {
            val start = s * step
            var mean = 0.0
            if (detrend) {
                for (i in 0 until n)
                    mean += padded[start + i]
                mean /= n
            }
            for (i in 0 until n) {
                re[i] = (padded[start + i] - mean) * w[i]
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
