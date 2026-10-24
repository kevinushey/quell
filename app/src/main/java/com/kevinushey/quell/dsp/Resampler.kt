package com.kevinushey.quell.dsp

/**
 * Phone sensor timestamps jitter and occasionally repeat, and an FFT needs a
 * uniform grid. We estimate the nominal rate from the median spacing and
 * linearly interpolate onto it.
 */
object Resampler {

    /** Upper bound on one resampled channel; beyond this the timestamps are almost certainly wrong. */
    const val MAX_SAMPLES = 4_000_000L

    private const val MAX_DELTAS = 1 shl 16

    /** Number of samples a uniform grid at `sampleRate` needs to span `time`, without overflow. */
    fun resampledLength(time: DoubleArray, sampleRate: Double): Long {
        if (time.isEmpty() || sampleRate <= 0.0)
            return 0L
        val n = Math.rint((time.last() - time.first()) * sampleRate) + 1.0
        return if (n.isNaN() || n < 0) Long.MAX_VALUE else minOf(n, Long.MAX_VALUE.toDouble()).toLong()
    }

    fun estimateSampleRate(time: DoubleArray): Double {
        if (time.size < 2)
            return 0.0

        // Every gap is inspected (so repeated timestamps cannot hide the real spacing), but only a
        // bounded reservoir sample of the positive ones is kept and sorted. Reservoir sampling is
        // uniform, so periodic patterns in the data cannot bias which gaps are kept.
        val deltas = DoubleArray(MAX_DELTAS)
        var seen = 0L
        val rng = java.util.Random(0x5EED)
        for (i in 0 until time.size - 1) {
            val d = time[i + 1] - time[i]
            if (d <= 0.0)
                continue
            if (seen < MAX_DELTAS)
                deltas[seen.toInt()] = d
            else {
                val slot = (rng.nextDouble() * (seen + 1)).toLong()
                if (slot < MAX_DELTAS)
                    deltas[slot.toInt()] = d
            }
            seen++
        }
        if (seen == 0L)
            return 0.0

        val n = minOf(seen, MAX_DELTAS.toLong()).toInt()
        val sorted = deltas.copyOf(n).also { it.sort() }
        return 1.0 / sorted[n / 2]
    }

    /**
     * Longest leading prefix of `time` whose resampled length fits `maxSamples`.
     * Dropping the tail changes the median rate, so the check is repeated on the
     * retained prefix until it holds.
     */
    fun analyzablePrefix(time: DoubleArray, maxSamples: Long): Int {
        var n = time.size
        while (n >= 2) {
            val prefix = if (n == time.size) time else time.copyOf(n)
            val fs = estimateSampleRate(prefix)
            if (fs <= 0 || resampledLength(prefix, fs) <= maxSamples)
                return n
            // Dropping a fast tail can lower the median rate enough that a slightly shorter
            // prefix fits; probe those before cutting all the way back by span.
            for (probe in intArrayOf(n - 1, n - maxOf(2, n / 16))) {
                if (probe < 2)
                    continue
                val candidate = time.copyOf(probe)
                val pfs = estimateSampleRate(candidate)
                if (pfs <= 0 || resampledLength(candidate, pfs) <= maxSamples)
                    return probe
            }
            val maxSpan = (maxSamples - 1) / fs
            var m = n
            while (m > 0 && prefix[m - 1] - prefix[0] > maxSpan)
                m--
            // Always make progress, even if the span test alone would not shrink the prefix.
            n = minOf(m, n - 1)
        }
        return n
    }

    fun resample(time: DoubleArray, values: DoubleArray, sampleRate: Double): DoubleArray {
        require(time.size == values.size)
        if (time.isEmpty() || sampleRate <= 0.0)
            return DoubleArray(0)

        val length = resampledLength(time, sampleRate)
        require(length <= MAX_SAMPLES) {
            "Timestamps span %.0f s at %.0f Hz, which needs %d samples; check the time column units".format(
                java.util.Locale.US,
                time.last() - time.first(),
                sampleRate,
                length,
            )
        }

        val t0 = time.first()
        val n = length.toInt()
        val out = DoubleArray(n)
        val dt = 1.0 / sampleRate

        var j = 0
        for (i in 0 until n) {
            val t = t0 + i * dt
            while (j < time.size - 2 && time[j + 1] < t)
                j++

            val ta = time[j]
            val tb = time[j + 1]
            out[i] = if (tb <= ta) {
                values[j]
            } else {
                val frac = ((t - ta) / (tb - ta)).coerceIn(0.0, 1.0)
                values[j] + frac * (values[j + 1] - values[j])
            }
        }
        return out
    }
}
