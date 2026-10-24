package com.kevinushey.quell.dsp

import kotlin.math.abs
import kotlin.math.sqrt

data class DivergenceBand(val freq: Double, val delta: Double, val index: Int)

data class DiffResult(
    val freq: DoubleArray,
    /** other - reference, in dB (or z units when the normalization is already in dB domain). */
    val delta: DoubleArray,
    val meanAbs: Double,
    val rms: Double,
    val correlation: Double,
    val topBands: List<DivergenceBand>,
)

object SpectrumDiff {

    /**
     * Compares `other` against `reference`. Both are expected to already be
     * normalized the same way. Inputs in linear power are converted to dB so
     * that the difference reads as a ratio.
     */
    fun compute(
        reference: Spectrum,
        other: Spectrum,
        decibelDomain: Boolean,
        topCount: Int = 5,
        minSeparationBins: Int = 5,
    ): DiffResult {
        // Only the frequencies both spectra actually measured take part; a lower
        // sample rate must not be compared against the other run past its Nyquist.
        val shared = reference.freq.indices.filter { other.supports(reference.freq[it]) }
        val grid = DoubleArray(shared.size) { reference.freq[shared[it]] }
        val aligned = other.onGrid(grid)
        val n = grid.size

        val a = DoubleArray(n)
        val b = DoubleArray(n)
        for (i in 0 until n) {
            val rp = reference.power[shared[i]]
            a[i] = if (decibelDomain) rp else Normalization.toDb(rp)
            b[i] = if (decibelDomain) aligned.power[i] else Normalization.toDb(aligned.power[i])
        }

        val delta = DoubleArray(n) { b[it] - a[it] }
        val meanAbs = if (n > 0) delta.sumOf { abs(it) } / n else 0.0
        val rms = if (n > 0) sqrt(delta.sumOf { it * it } / n) else 0.0

        val absDelta = DoubleArray(n) { abs(delta[it]) }
        val top = Peaks.find(grid, absDelta, topCount, minSeparationBins)
            .map { DivergenceBand(it.freq, delta[it.index], it.index) }
            .sortedByDescending { abs(it.delta) }

        return DiffResult(grid, delta, meanAbs, rms, pearson(a, b), top)
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val n = a.size
        if (n < 2)
            return Double.NaN

        val ma = a.average()
        val mb = b.average()
        var sab = 0.0
        var saa = 0.0
        var sbb = 0.0
        for (i in 0 until n) {
            val da = a[i] - ma
            val db = b[i] - mb
            sab += da * db
            saa += da * da
            sbb += db * db
        }
        val denom = sqrt(saa * sbb)
        return if (denom > 0) sab / denom else Double.NaN
    }
}
