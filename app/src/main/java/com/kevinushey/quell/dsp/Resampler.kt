package com.kevinushey.quell.dsp

/**
 * Phone sensor timestamps jitter and occasionally repeat, and an FFT needs a
 * uniform grid. We estimate the nominal rate from the median spacing and
 * linearly interpolate onto it.
 */
object Resampler {

    /** Upper bound on one resampled channel; beyond this the timestamps are almost certainly wrong. */
    const val MAX_SAMPLES = 4_000_000L

    /** Fewest resampled samples worth analyzing; matches the recorder's minimum capture. */
    const val MIN_SAMPLES = 16

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
     * Every prefix is judged by its own median gap: a Fenwick tree over the
     * ranks of the positive gaps yields the running median in O(log n), so the
     * whole scan is O(n log n) and no candidate is skipped.
     */
    fun analyzablePrefix(time: DoubleArray, maxSamples: Long): Int {
        val n = time.size
        if (n < 2)
            return n
        val fs = estimateSampleRate(time)
        if (fs <= 0 || resampledLength(time, fs) <= maxSamples)
            return n

        val gaps = DoubleArray(n - 1) { time[it + 1] - time[it] }
        var positive = 0
        for (d in gaps)
            if (d > 0)
                positive++
        if (positive == 0)
            return n
        val sortedPositive = DoubleArray(positive)
        var w = 0
        for (d in gaps)
            if (d > 0)
                sortedPositive[w++] = d
        sortedPositive.sort()

        val tree = IntArray(positive + 1)
        fun add(rank: Int) {
            var i = rank + 1
            while (i <= positive) {
                tree[i]++
                i += i and -i
            }
        }
        fun kthRank(k: Int): Int {
            var pos = 0
            var remaining = k
            var step = Integer.highestOneBit(positive)
            while (step > 0) {
                val next = pos + step
                if (next <= positive && tree[next] < remaining) {
                    pos = next
                    remaining -= tree[next]
                }
                step = step shr 1
            }
            return pos
        }

        var best = 1
        var inserted = 0
        for (i in gaps.indices) {
            val d = gaps[i]
            if (d > 0) {
                add(java.util.Arrays.binarySearch(sortedPositive, d))
                inserted++
            }
            val prefixLength = i + 2
            if (inserted == 0) {
                best = prefixLength
                continue
            }
            val median = sortedPositive[kthRank(inserted / 2 + 1)]
            val length = Math.rint((time[i + 1] - time[0]) / median) + 1.0
            if (length <= maxSamples)
                best = prefixLength
        }

        // estimateSampleRate subsamples very long inputs, so confirm with it and back off a little if needed.
        var k = best
        while (k >= 2) {
            val prefix = time.copyOf(k)
            val pfs = estimateSampleRate(prefix)
            if (pfs <= 0 || resampledLength(prefix, pfs) <= maxSamples)
                return k
            k -= maxOf(1, k / 100)
        }
        return k.coerceAtLeast(1)
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
