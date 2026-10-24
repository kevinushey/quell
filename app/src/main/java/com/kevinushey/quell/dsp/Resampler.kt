package com.kevinushey.quell.dsp

import kotlin.math.roundToInt

/**
 * Phone sensor timestamps jitter and occasionally repeat, and an FFT needs a
 * uniform grid. We estimate the nominal rate from the median spacing and
 * linearly interpolate onto it.
 */
object Resampler {

    fun estimateSampleRate(time: DoubleArray): Double {
        if (time.size < 2)
            return 0.0

        val deltas = DoubleArray(time.size - 1) { time[it + 1] - time[it] }
            .filter { it > 0.0 }
            .sorted()
        if (deltas.isEmpty())
            return 0.0

        val median = deltas[deltas.size / 2]
        return 1.0 / median
    }

    fun resample(time: DoubleArray, values: DoubleArray, sampleRate: Double): DoubleArray {
        require(time.size == values.size)
        if (time.isEmpty() || sampleRate <= 0.0)
            return DoubleArray(0)

        val t0 = time.first()
        val duration = time.last() - t0
        val n = (duration * sampleRate).roundToInt() + 1
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
