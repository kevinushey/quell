package com.kevinushey.quell.dsp

data class Peak(val freq: Double, val value: Double, val index: Int)

object Peaks {

    /**
     * Returns the `count` tallest local maxima of `values`, at least
     * `minSeparationBins` apart, so that one broad hump does not claim
     * every slot.
     */
    fun find(freq: DoubleArray, values: DoubleArray, count: Int, minSeparationBins: Int = 3): List<Peak> {
        val n = values.size
        if (n < 3 || count <= 0)
            return emptyList()

        val candidates = ArrayList<Peak>()
        for (i in 1 until n - 1) {
            val v = values[i]
            if (v.isNaN())
                continue
            if (v >= values[i - 1] && v > values[i + 1])
                candidates.add(Peak(freq[i], v, i))
        }
        candidates.sortByDescending { it.value }

        val chosen = ArrayList<Peak>()
        for (c in candidates) {
            if (chosen.size >= count)
                break
            if (chosen.none { kotlin.math.abs(it.index - c.index) < minSeparationBins })
                chosen.add(c)
        }
        return chosen.sortedBy { it.freq }
    }
}
