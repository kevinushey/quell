package com.kevinushey.quell.record

import com.kevinushey.quell.dsp.Resampler

/**
 * Breaks in a recording's sample stream: Android stops delivering the
 * accelerometer while the app is in the background, and the resampler would
 * bridge such a hole with a straight line that reads as near-silence.
 */
class StreamGaps(
    val count: Int,
    /** Seconds without samples, all breaks together. */
    val missingSec: Double,
    /** The longest unbroken stretch, in seconds from the first sample. */
    val longestStart: Double,
    val longestEnd: Double,
) {
    companion object {
        /** An interval this many times the median one, and at least MIN_GAP_SEC, is a break rather than jitter. */
        private const val FACTOR = 10.0
        private const val MIN_GAP_SEC = 0.1

        /** The breaks in `time`, or null when there are none. */
        fun find(time: DoubleArray): StreamGaps? {
            val fs = Resampler.estimateSampleRate(time)
            if (fs <= 0)
                return null
            val limit = maxOf(FACTOR / fs, MIN_GAP_SEC)
            var count = 0
            var missing = 0.0
            var runStart = 0
            var bestStart = 0
            var bestEnd = 0
            for (i in 0 until time.size - 1) {
                val d = time[i + 1] - time[i]
                if (d <= limit)
                    continue
                count++
                missing += d
                if (time[i] - time[runStart] > time[bestEnd] - time[bestStart]) {
                    bestStart = runStart
                    bestEnd = i
                }
                runStart = i + 1
            }
            if (count == 0)
                return null
            val last = time.size - 1
            if (time[last] - time[runStart] > time[bestEnd] - time[bestStart]) {
                bestStart = runStart
                bestEnd = last
            }
            return StreamGaps(count, missing, time[bestStart] - time[0], time[bestEnd] - time[0])
        }
    }
}
