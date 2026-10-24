package com.kevinushey.quell.data

import com.kevinushey.quell.dsp.Resampler

/** How many samples one recording may have on this device without starving the heap. */
object MemoryBudget {
    /** Four raw double arrays per sample. */
    private const val BYTES_PER_SAMPLE = 4L * 8

    /** Growth copies while parsing, the resampled copy, chart arrays and FFT scratch. */
    private const val HEADROOM = 6

    fun maxSamples(): Int {
        val heapLimit = Runtime.getRuntime().maxMemory() / (BYTES_PER_SAMPLE * HEADROOM)
        return minOf(Resampler.MAX_SAMPLES, heapLimit).toInt()
    }
}
