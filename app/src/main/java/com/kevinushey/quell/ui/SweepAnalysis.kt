package com.kevinushey.quell.ui

import com.kevinushey.quell.data.DatasetMeta
import com.kevinushey.quell.data.ExperimentKind
import com.kevinushey.quell.data.Sweep
import com.kevinushey.quell.dsp.Modal
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** One run's place in a sweep: its parameter value and the quantity being optimized. */
class SweepPoint(
    val meta: DatasetMeta,
    /** Parameter value, or null when the run has none yet. */
    val value: Double?,
    /** The optimized quantity: in-band peak in dB versus the baseline (drive) or damping ratio (tap). */
    val metric: Double,
    /** Dominant in-band peak (drive) or natural frequency (tap), Hz; NaN when none was found. */
    val peakFreq: Double,
    /** Band RMS in dB versus the baseline; drive runs only. */
    val bandRmsDb: Double,
    /** One-sigma uncertainty of `metric`, in its own units. */
    val error: Double,
    /** Why the run takes no part in the fit, or null when it does. */
    val problem: String?,
) {
    val usable: Boolean get() = problem == null && value != null && metric.isFinite()
}

/** Least-squares parabola metric = a x^2 + b x + c through the usable points. */
class SweepFit(val a: Double, val b: Double, val c: Double, val residualRms: Double) {
    val extremum: Double get() = -b / (2 * a)
    fun at(x: Double): Double = a * x * x + b * x + c
}

class SweepSummary(
    val points: List<SweepPoint>,
    val fit: SweepFit?,
    /** Parameter value at the fitted optimum, when the fit has one inside or near the range tried. */
    val optimum: Double?,
    /** Half-width of the parameter interval over which the fit stays within one sigma of the optimum. */
    val optimumHalfWidth: Double,
    /** Plain-language reading of the fit, for the screen. */
    val note: String,
) {
    val usable: List<SweepPoint> get() = points.filter { it.usable }
}

object SweepAnalysis {

    /** Fewest distinct parameter values a parabola can be trusted on. */
    const val MIN_POINTS = 3

    /** Lower is better for drive runs (peak dB versus baseline); higher is better for tap runs (damping). */
    fun minimizes(kind: ExperimentKind): Boolean = kind != ExperimentKind.TAP

    fun metricLabel(kind: ExperimentKind): String = if (kind == ExperimentKind.TAP) "damping ratio" else "peak vs baseline (dB)"

    /**
     * Drive runs: the tallest peak inside the target band on each member,
     * relative to the same peak on the baseline. Spectra are expected on the
     * reference-band scale when that normalization is active.
     */
    fun drivePoints(sweep: Sweep, members: List<SpectrumResult>, baseline: SpectrumResult?, lo: Double, hi: Double): List<SweepPoint> {
        val refSpectrum = baseline?.let { if (it.referenceScale != 1.0) it.raw.map { _, p -> p * it.referenceScale } else it.raw }
        val refPeak = refSpectrum?.let { Modal.dominantPeak(it, lo, hi) }
        val refRms = refSpectrum?.bandRms(lo, hi) ?: Double.NaN
        return members.map { r ->
            val s = if (r.referenceScale != 1.0) r.raw.map { _, p -> p * r.referenceScale } else r.raw
            val peak = Modal.dominantPeak(s, lo, hi)
            val rms = s.bandRms(lo, hi)
            val problem = when {
                baseline == null -> "no baseline chosen"
                baseline.referenceBandMissing || r.referenceBandMissing -> "reference band not covered"
                refPeak == null || peak == null -> "no peak inside the target band"
                else -> null
            }
            val metric = if (problem == null) 10 * log10(peak!!.power / refPeak!!.power) else Double.NaN
            val rmsDb = if (problem == null && refRms > 0 && rms > 0) 20 * log10(rms / refRms) else Double.NaN
            // Both runs contribute random error; the baseline's is shared by every point.
            val err = sqrt(r.randomErrorDb * r.randomErrorDb + (baseline?.randomErrorDb ?: 0.0).let { it * it })
            SweepPoint(r.meta, sweep.valueOf(r.meta), metric, peak?.freq ?: Double.NaN, rmsDb, err, problem)
        }
    }

    /** Tap runs: mean damping ratio per member; the baseline only supplies the natural frequency for context. */
    fun tapPoints(sweep: Sweep, members: List<Pair<DatasetMeta, Result<TapSummary>?>>): List<SweepPoint> {
        return members.map { (meta, state) ->
            val s = state?.getOrNull()
            val problem = when {
                state == null -> "analyzing"
                s == null -> "could not analyze"
                s.count == 0 -> "no taps found"
                else -> null
            }
            val metric = if (problem == null) s!!.meanDamping else Double.NaN
            val err = if (problem == null && s!!.count > 1 && !s.sdDamping.isNaN()) s.sdDamping / sqrt(s.count.toDouble()) else Double.NaN
            SweepPoint(meta, sweep.valueOf(meta), metric, s?.meanFreq ?: Double.NaN, Double.NaN, err, problem)
        }
    }

    /** Parabola through the usable points, or null with fewer than MIN_POINTS distinct values. */
    fun fit(points: List<SweepPoint>): SweepFit? {
        val pts = points.filter { it.usable }
        if (pts.map { it.value }.distinct().size < MIN_POINTS)
            return null
        // Normal equations for [x^2 x 1]; centre x first so large values (grams) do not lose precision.
        val x0 = pts.sumOf { it.value!! } / pts.size
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
        for (p in pts) {
            val x = p.value!! - x0
            val y = p.metric
            s0 += 1; s1 += x; s2 += x * x; s3 += x * x * x; s4 += x * x * x * x
            t0 += y; t1 += x * y; t2 += x * x * y
        }
        val det = det3(s4, s3, s2, s3, s2, s1, s2, s1, s0)
        if (abs(det) < 1e-12)
            return null
        val a = det3(t2, s3, s2, t1, s2, s1, t0, s1, s0) / det
        val b = det3(s4, t2, s2, s3, t1, s1, s2, t0, s0) / det
        val c = det3(s4, s3, t2, s3, s2, t1, s2, s1, t0) / det
        // Undo the centring: a (x - x0)^2 + b (x - x0) + c.
        val bb = b - 2 * a * x0
        val cc = a * x0 * x0 - b * x0 + c
        var ss = 0.0
        for (p in pts) {
            val d = p.metric - (a * p.value!! * p.value!! + bb * p.value!! + cc)
            ss += d * d
        }
        return SweepFit(a, bb, cc, sqrt(ss / pts.size))
    }

    fun summarize(sweep: Sweep, points: List<SweepPoint>): SweepSummary {
        val minimize = minimizes(sweep.kind)
        val usable = points.filter { it.usable }
        val distinct = usable.map { it.value }.distinct().size
        val unit = sweep.parameter
        if (distinct < MIN_POINTS) {
            val note = if (points.any { it.value == null })
                "Set the ${sweep.parameter.label.lowercase(Locale.US)} on every run, then record at least $MIN_POINTS different values to fit an optimum."
            else
                "Record at least $MIN_POINTS different values to fit an optimum (${distinct} so far)."
            return SweepSummary(points, null, null, Double.NaN, note)
        }
        val fit = fit(points) ?: return SweepSummary(points, null, null, Double.NaN, "The values tried do not determine a curve.")
        val lo = usable.minOf { it.value!! }
        val hi = usable.maxOf { it.value!! }
        val span = hi - lo
        val best = if (minimize) usable.minByOrNull { it.metric }!! else usable.maxByOrNull { it.metric }!!
        val curvatureRight = if (minimize) fit.a > 0 else fit.a < 0
        if (!curvatureRight) {
            // The curve bends the wrong way: the best value is at an end, and the trend says which.
            val further = if (best.value!! >= hi) "larger" else "smaller"
            return SweepSummary(
                points, fit, null, Double.NaN,
                "No optimum inside %s-%s: the best run is at %s and the trend continues toward %s values. Extend the sweep that way.".format(
                    Locale.US, unit.format(lo), unit.format(hi), unit.format(best.value), further,
                ),
            )
        }
        val x = fit.extremum
        // One sigma: the larger of the scatter about the curve and the measurement error of a typical point.
        val typicalError = usable.map { it.error }.filter { it.isFinite() }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        val sigma = maxOf(fit.residualRms, typicalError)
        val halfWidth = if (sigma > 0 && fit.a != 0.0) sqrt(sigma / abs(fit.a)) else Double.NaN
        val margin = 0.25 * span
        if (x < lo - margin || x > hi + margin) {
            val side = if (x < lo) "below" else "above"
            return SweepSummary(
                points, fit, x, halfWidth,
                "The fitted optimum, %s, lies %s the range tried (%s-%s). Extend the sweep toward it before trusting the number.".format(
                    Locale.US, unit.format(x), side, unit.format(lo), unit.format(hi),
                ),
            )
        }
        val gain = if (minimize) "%.1f dB below baseline".format(Locale.US, -fit.at(x)) else "damping ratio %.4f".format(Locale.US, fit.at(x))
        val pm = if (halfWidth.isFinite()) " (+/- %s at one sigma)".format(Locale.US, unit.format(halfWidth)) else ""
        val resolved = halfWidth.isFinite() && halfWidth < span / 2
        val note = buildString {
            append("Optimum near %s%s, %s.".format(Locale.US, unit.format(x), pm, gain))
            if (!resolved)
                append(" The runs differ by less than their uncertainty; repeat runs or spread the values further apart.")
        }
        return SweepSummary(points, fit, x, halfWidth, note)
    }

    private fun det3(
        a: Double, b: Double, c: Double,
        d: Double, e: Double, f: Double,
        g: Double, h: Double, i: Double,
    ): Double = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
}
