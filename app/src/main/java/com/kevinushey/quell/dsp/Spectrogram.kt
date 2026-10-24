package com.kevinushey.quell.dsp

/** Short-time power spectrum in dB, laid out as [timeFrame][frequencyBin]. */
class SpectrogramData(
    /** Frame centres in seconds. */
    val times: DoubleArray,
    val freq: DoubleArray,
    val db: Array<DoubleArray>,
    val minDb: Double,
    val maxDb: Double,
    /** Seconds between consecutive frame centres (one frame's length when there is a single frame). */
    val frameSpacing: Double = 0.0,
)

object Spectrogram {

    /** Sum of per-axis power, matching the Spectrum tab's TOTAL channel rather than |a|'s spectrum. */
    fun computeTotal(
        x: DoubleArray,
        y: DoubleArray,
        z: DoubleArray,
        sampleRate: Double,
        segmentSize: Int,
        window: WindowType = WindowType.HANN,
        overlap: Double = 0.5,
        maxFrames: Int = 600,
    ): SpectrogramData {
        val px = linearFrames(x, sampleRate, segmentSize, window, overlap, maxFrames)
        val py = linearFrames(y, sampleRate, segmentSize, window, overlap, maxFrames)
        val pz = linearFrames(z, sampleRate, segmentSize, window, overlap, maxFrames)
        for (f in px.power.indices) {
            val row = px.power[f]
            for (k in row.indices)
                row[k] += py.power[f][k] + pz.power[f][k]
        }
        return px.toDb()
    }

    fun compute(
        signal: DoubleArray,
        sampleRate: Double,
        segmentSize: Int,
        window: WindowType = WindowType.HANN,
        overlap: Double = 0.5,
        maxFrames: Int = 600,
    ): SpectrogramData = linearFrames(signal, sampleRate, segmentSize, window, overlap, maxFrames).toDb()

    private class LinearFrames(val times: DoubleArray, val freq: DoubleArray, val power: Array<DoubleArray>, val frameSpacing: Double) {
        fun toDb(): SpectrogramData {
            var minDb = Double.POSITIVE_INFINITY
            var maxDb = Double.NEGATIVE_INFINITY
            val out = Array(power.size) { f ->
                val row = power[f]
                DoubleArray(row.size) { k ->
                    val db = Normalization.toDb(row[k])
                    if (k > 0) {
                        if (db < minDb)
                            minDb = db
                        if (db > maxDb)
                            maxDb = db
                    }
                    db
                }
            }
            if (out.isEmpty())
                return SpectrogramData(times, freq, out, 0.0, 0.0, frameSpacing)
            return SpectrogramData(times, freq, out, minDb, maxDb, frameSpacing)
        }
    }

    private fun linearFrames(
        signal: DoubleArray,
        sampleRate: Double,
        segmentSize: Int,
        window: WindowType,
        overlap: Double,
        maxFrames: Int,
    ): LinearFrames {
        val n = segmentSize
        val half = n / 2
        val freq = DoubleArray(half + 1) { it * sampleRate / n }
        if (signal.size < n || sampleRate <= 0.0)
            return LinearFrames(DoubleArray(0), freq, emptyArray(), 0.0)

        // Cap the frame count so long recordings stay cheap to compute and draw.
        var step = (n * (1.0 - overlap)).toInt().coerceAtLeast(1)
        val maxPossible = (signal.size - n) / step + 1
        if (maxPossible > maxFrames)
            step = ((signal.size - n) / (maxFrames - 1)).coerceAtLeast(1)
        val frames = (signal.size - n) / step + 1

        val w = window.coefficients(n)
        val windowPower = w.sumOf { it * it }
        val scale = 1.0 / (sampleRate * windowPower)

        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val times = DoubleArray(frames)
        val out = Array(frames) { DoubleArray(half + 1) }

        for (f in 0 until frames) {
            val start = f * step
            times[f] = (start + n / 2.0) / sampleRate
            var mean = 0.0
            for (i in 0 until n)
                mean += signal[start + i]
            mean /= n
            for (i in 0 until n) {
                re[i] = (signal[start + i] - mean) * w[i]
                im[i] = 0.0
            }

            FFT.transform(re, im)
            val row = out[f]
            for (k in 0..half) {
                val oneSided = if (k == 0 || k == half) 1.0 else 2.0
                row[k] = (re[k] * re[k] + im[k] * im[k]) * scale * oneSided
            }
        }
        val spacing = if (frames > 1) step / sampleRate else n / sampleRate
        return LinearFrames(times, freq, out, spacing)
    }
}
