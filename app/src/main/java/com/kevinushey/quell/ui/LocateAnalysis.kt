package com.kevinushey.quell.ui

import com.kevinushey.quell.dsp.RingDownResult
import java.util.Locale
import kotlin.math.sqrt

/** How strongly the structure's mode answered taps at one location. */
class LocationStat(
    /** Zero-based location number; marks split the recording into locations 0..marks.size. */
    val index: Int,
    val taps: Int,
    /** Mean initial amplitude of the mode's ring-down, m/s^2. */
    val meanAmplitude: Double,
    val seAmplitude: Double,
    /** Mean ring-down amplitude per unit impact peak: the mode-shape estimate, independent of how hard the tap was. */
    val meanNormalized: Double,
    val seNormalized: Double,
    val meanFreq: Double,
) {
    val label: String get() = "Location ${index + 1}"
}

class LocateSummary(
    /** Every location in recording order, including ones without a usable tap. */
    val locations: List<LocationStat>,
    /** Locations with taps, strongest response first. */
    val ranked: List<LocationStat>,
    val note: String,
) {
    val best: LocationStat? get() = ranked.firstOrNull()

    /** Mode shape at a location relative to the strongest one, 0..1. */
    fun shape(stat: LocationStat): Double {
        val top = best?.meanNormalized ?: return Double.NaN
        return if (top > 0) stat.meanNormalized / top else Double.NaN
    }
}

/**
 * Antinode search: with the sensor fixed, the ring-down amplitude of the mode
 * scales with the mode shape at the tap point. Taps are grouped by the
 * "next location" marks and compared per unit impact, so a harder tap does
 * not pass for a better spot.
 */
object LocateAnalysis {

    /** Which location a tap at `time` belongs to. */
    fun locationOf(time: Double, marks: List<Double>): Int = marks.count { it <= time }

    fun summarize(taps: List<RingDownResult>, marks: List<Double>): LocateSummary {
        val count = marks.size + 1
        val groups = List(count) { ArrayList<RingDownResult>() }
        for (t in taps) {
            // Taps without the amplitude figures (older fits) cannot be ranked.
            if (!t.initialAmplitude.isFinite() || !t.impactPeak.isFinite() || t.impactPeak <= 0)
                continue
            groups[locationOf(t.impulseTime, marks).coerceIn(0, count - 1)].add(t)
        }
        val stats = groups.mapIndexed { i, g ->
            val amps = g.map { it.initialAmplitude }
            val norms = g.map { it.initialAmplitude / it.impactPeak }
            LocationStat(i, g.size, amps.mean(), amps.standardError(), norms.mean(), norms.standardError(), g.map { it.naturalFreq }.mean())
        }
        val ranked = stats.filter { it.taps > 0 }.sortedByDescending { it.meanNormalized }
        return LocateSummary(stats, ranked, note(ranked))
    }

    private fun note(ranked: List<LocationStat>): String {
        if (ranked.isEmpty())
            return "No clean tap yet. Strike once, wait for the ring to die away, and repeat; move on with \"Next location\"."
        if (ranked.size == 1)
            return "Only ${ranked[0].label} has taps so far. Tap the next spot to compare."
        val best = ranked[0]
        val second = ranked[1]
        val gap = best.meanNormalized - second.meanNormalized
        val spread = sqrt(sq(best.seNormalizedOrZero()) + sq(second.seNormalizedOrZero()))
        val ratio = if (best.meanNormalized > 0) second.meanNormalized / best.meanNormalized else Double.NaN
        val thin = ranked.filter { it.taps < 3 }
        return buildString {
            when {
                spread > 0 && gap > 2 * spread ->
                    append("%s is closest to the antinode: the mode answers %.0f%% stronger there than at %s.".format(Locale.US, best.label, (1 / ratio - 1) * 100, second.label))
                spread > 0 ->
                    append("%s leads, but %s is within the tap-to-tap scatter; tap both a few more times before choosing.".format(Locale.US, best.label, second.label))
                else ->
                    append("%s leads %s; one tap each is not enough to tell.".format(Locale.US, best.label, second.label))
            }
            if (ratio.isFinite() && ratio < 0.2)
                append(" The weakest spots respond at under a fifth of the best: they sit near a node of this mode.")
            if (thin.isNotEmpty())
                append(" Fewer than three clean taps at ${thin.joinToString { it.label.lowercase(Locale.US) }}.")
        }
    }

    private fun LocationStat.seNormalizedOrZero(): Double = if (seNormalized.isFinite()) seNormalized else 0.0

    private fun sq(v: Double) = v * v

    private fun List<Double>.mean(): Double = if (isEmpty()) Double.NaN else average()

    private fun List<Double>.standardError(): Double {
        if (size < 2)
            return Double.NaN
        val m = average()
        return sqrt(sumOf { (it - m) * (it - m) } / (size - 1) / size)
    }
}
