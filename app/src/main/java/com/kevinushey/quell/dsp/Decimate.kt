package com.kevinushey.quell.dsp

/** Min/max decimation: keeps every bucket's extremes so peaks survive, with a bounded point count. */
object Decimate {

    /** Largest series the waveform chart retains per dataset. */
    const val MAX_POINTS = 16_384

    class Result(val time: DoubleArray, val values: DoubleArray)

    fun minMax(time: DoubleArray, values: DoubleArray, maxPoints: Int = MAX_POINTS): Result {
        require(time.size == values.size)
        val n = time.size
        if (n <= maxPoints)
            return Result(time, values)

        val buckets = (maxPoints / 2).coerceAtLeast(1)
        val outT = DoubleArray(buckets * 2)
        val outV = DoubleArray(buckets * 2)
        var k = 0
        for (b in 0 until buckets) {
            val a = (b.toLong() * n / buckets).toInt()
            val e = ((b + 1).toLong() * n / buckets).toInt().coerceAtLeast(a + 1).coerceAtMost(n)
            var iMin = a
            var iMax = a
            for (i in a + 1 until e) {
                if (values[i] < values[iMin])
                    iMin = i
                if (values[i] > values[iMax])
                    iMax = i
            }
            // Emit in time order so the polyline does not double back.
            val first = minOf(iMin, iMax)
            val second = maxOf(iMin, iMax)
            outT[k] = time[first]; outV[k] = values[first]; k++
            if (second != first) {
                outT[k] = time[second]; outV[k] = values[second]; k++
            }
        }
        return Result(outT.copyOf(k), outV.copyOf(k))
    }
}
