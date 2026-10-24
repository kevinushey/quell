package com.kevinushey.quell.dsp

import kotlin.math.log10
import kotlin.math.sqrt

/** A stretch of a recording, in seconds from its first sample, whose vibration level stayed within `rangeDb`. */
data class SteadyWindow(val start: Double, val end: Double, val rangeDb: Double) {
    val duration: Double get() = end - start

    /** True when the stretch is the whole run (within one analysis cell at each end). */
    fun coversAll(total: Double, slack: Double = 1.0): Boolean = start <= slack && end >= total - slack
}

/**
 * Finds the part of a run worth analyzing: the longest stretch whose
 * short-term vibration level stays within a band, taken from the louder
 * part of the recording. Door slams and bumps are spikes that break the
 * band; idling and the parking shuffle are quiet stretches that fall below
 * the loudness floor. Nothing here knows about cars: it is a level test.
 */
object AutoTrim {

    /** Width of the level band a steady stretch may wander over. */
    const val BAND_DB = 10.0

    /** How far below the run's loud level (75th percentile) a stretch may sit and still count as the drive. */
    const val FLOOR_BELOW_LOUD_DB = 6.0

    /** Shortest stretch worth suggesting, seconds. */
    const val MIN_SECONDS = 5.0

    private const val CELL_SECONDS = 0.5
    private const val MAX_CELLS = 1200

    fun steadyWindow(time: DoubleArray, magnitude: DoubleArray): SteadyWindow? {
        val n = time.size
        if (n < 16)
            return null
        val t0 = time[0]
        val total = time[n - 1] - t0
        if (total < 2 * MIN_SECONDS)
            return null

        // Short-term RMS of the magnitude about its mean, in dB, one value per cell.
        val cell = maxOf(CELL_SECONDS, total / MAX_CELLS)
        val cells = (total / cell).toInt().coerceAtLeast(1)
        val mean = magnitude.average()
        val sum = DoubleArray(cells)
        val count = IntArray(cells)
        for (i in 0 until n) {
            val c = ((time[i] - t0) / cell).toInt().coerceIn(0, cells - 1)
            val d = magnitude[i] - mean
            sum[c] += d * d
            count[c]++
        }
        val level = DoubleArray(cells) { c ->
            if (count[c] == 0) Double.NaN else 10 * log10(sqrt(sum[c] / count[c]).coerceAtLeast(1e-9))
        }
        // A cell without samples (a delivery gap) breaks any stretch.
        val filled = level.indices.filter { !level[it].isNaN() }
        if (filled.size < 2)
            return null
        val sorted = filled.map { level[it] }.sorted()
        val loud = sorted[(sorted.size * 3) / 4]
        val floor = loud - FLOOR_BELOW_LOUD_DB
        val minCells = (MIN_SECONDS / cell).toInt().coerceAtLeast(2)

        // Longest run of cells whose levels span at most BAND_DB and whose median stays near the loud level.
        var bestStart = -1
        var bestLen = 0
        var bestRange = 0.0
        for (start in 0 until cells) {
            if (level[start].isNaN())
                continue
            var lo = level[start]
            var hi = level[start]
            var end = start
            while (end + 1 < cells && !level[end + 1].isNaN()) {
                val next = level[end + 1]
                val nlo = minOf(lo, next)
                val nhi = maxOf(hi, next)
                if (nhi - nlo > BAND_DB)
                    break
                lo = nlo
                hi = nhi
                end++
            }
            val len = end - start + 1
            if (len <= bestLen || len < minCells)
                continue
            val median = (start..end).map { level[it] }.sorted().let { it[it.size / 2] }
            if (median < floor)
                continue
            bestStart = start
            bestLen = len
            bestRange = hi - lo
        }
        if (bestStart < 0)
            return null
        // Cells quantize the edges; a stretch reaching the first or last cell means the run's edge.
        val start = if (bestStart == 0) 0.0 else bestStart * cell
        val end = if (bestStart + bestLen >= cells) total else (bestStart + bestLen) * cell
        return SteadyWindow(start, end, bestRange)
    }
}
