package com.kevinushey.quell.ui

import com.kevinushey.quell.data.DatasetMeta
import com.kevinushey.quell.data.ExperimentKind
import com.kevinushey.quell.data.Sweep
import com.kevinushey.quell.dsp.Modal
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
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
    /** One-sigma uncertainty of `metric` as an absolute level versus the baseline, in its own units. */
    val error: Double,
    /** Why the run takes no part in the fit, or null when it does. */
    val problem: String?,
    /**
     * The run's own share of that uncertainty. The baseline's error is common
     * to every point and cancels when values are compared with each other, so
     * fits, spread tests and duration hints use this one.
     */
    val ownError: Double = error,
) {
    val usable: Boolean get() = problem == null && value != null && metric.isFinite()

    /** Seconds of data behind this run's estimate. */
    val durationSec: Double get() = (meta.effectiveTrimEnd - meta.trimStart).coerceAtLeast(0.0)
}

/** Every usable run at one parameter value, combined; repeats tighten the estimate. */
class PooledPoint(val value: Double, val metric: Double, val error: Double, val ownError: Double, val runs: List<SweepPoint>) {
    val repeats: Int get() = runs.size
}

/** Weighted least-squares parabola metric = a x^2 + b x + c through the pooled points. */
class SweepFit(val a: Double, val b: Double, val c: Double, val residualRms: Double) {
    val extremum: Double get() = -b / (2 * a)
    fun at(x: Double): Double = a * x * x + b * x + c
}

class SweepSummary(
    val points: List<SweepPoint>,
    /** Usable runs pooled by value, in value order. */
    val pooled: List<PooledPoint>,
    val fit: SweepFit?,
    /** One sigma of the metric for the sweep as a whole: the larger of scatter about the curve and a typical point's error. */
    val sigma: Double,
    /** Parameter value at the fitted optimum, when the fit has one inside or near the range tried. */
    val optimum: Double?,
    /** Half-width of the parameter interval over which the fit stays within one sigma of the optimum. */
    val optimumHalfWidth: Double,
    /** Plain-language reading of the fit, for the screen. */
    val note: String,
    /** Parameter value worth recording next, or null when the data give no direction. */
    val nextValue: Double? = null,
) {
    val usable: List<SweepPoint> get() = points.filter { it.usable }
}

object SweepAnalysis {

    /** Fewest distinct parameter values a parabola can be trusted on. */
    const val MIN_POINTS = 3

    /** Difference between two runs that counts as a real tuning effect, dB; the duration hint aims at resolving it. */
    const val RESOLVE_DB = 1.0

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
                // A dead channel or an all-zero capture has no level to compare.
                !(peak.power > 0) || !(refPeak.power > 0) -> "no power inside the target band"
                else -> null
            }
            val metric = if (problem == null) 10 * log10(peak!!.power / refPeak!!.power) else Double.NaN
            val rmsDb = if (problem == null && refRms > 0 && rms > 0) 20 * log10(rms / refRms) else Double.NaN
            // Both runs contribute random error to the level versus baseline; the baseline's is shared by every point.
            val err = sqrt(r.randomErrorDb * r.randomErrorDb + (baseline?.randomErrorDb ?: 0.0).let { it * it })
            SweepPoint(r.meta, sweep.valueOf(r.meta), metric, peak?.freq ?: Double.NaN, rmsDb, err, problem, ownError = r.randomErrorDb)
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

    /**
     * Combines repeated runs at the same value. The metric is the
     * inverse-variance weighted mean when every run has an error, else the
     * plain mean; the error is the larger of the propagated error and the
     * standard error of the repeats, so disagreement between repeats shows.
     */
    fun pool(points: List<SweepPoint>): List<PooledPoint> {
        val groups = points.filter { it.usable }.groupBy { it.value!! }.toSortedMap()
        return groups.map { (value, runs) ->
            val weighted = runs.all { it.ownError.isFinite() && it.ownError > 0 }
            val weights = runs.map { if (weighted) 1 / (it.ownError * it.ownError) else 1.0 }
            val wsum = weights.sum()
            val mean = runs.indices.sumOf { weights[it] * runs[it].metric } / wsum
            val sampleSe = if (runs.size >= 2) sqrt(runs.sumOf { (it.metric - mean) * (it.metric - mean) } / (runs.size - 1) / runs.size) else Double.NaN
            fun combine(errors: List<Double>): Double {
                val finite = errors.all { it.isFinite() && it > 0 }
                val propagated = if (finite) 1 / sqrt(errors.sumOf { 1 / (it * it) }) else Double.NaN
                return when {
                    runs.size == 1 -> errors[0]
                    propagated.isFinite() && sampleSe.isFinite() -> maxOf(propagated, sampleSe)
                    propagated.isFinite() -> propagated
                    else -> sampleSe
                }
            }
            PooledPoint(value, mean, combine(runs.map { it.error }), combine(runs.map { it.ownError }), runs)
        }
    }

    /** Parabola through the pooled points, or null with fewer than MIN_POINTS distinct values. */
    fun fit(points: List<SweepPoint>): SweepFit? = fitPooled(pool(points))

    fun fitPooled(pts: List<PooledPoint>): SweepFit? {
        if (pts.size < MIN_POINTS)
            return null
        // Weighted normal equations for [u^2 u 1] on u = (x - x0) / scale, so neither large values
        // (grams) nor tiny steps (N/mm) upset the conditioning; the result is mapped back to x.
        val weighted = pts.all { it.ownError.isFinite() && it.ownError > 0 }
        val x0 = pts.sumOf { it.value } / pts.size
        val scale = (pts.maxOf { it.value } - pts.minOf { it.value }).takeIf { it > 0 } ?: 1.0
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
        for (p in pts) {
            val x = (p.value - x0) / scale
            val y = p.metric
            val w = if (weighted) 1 / (p.ownError * p.ownError) else 1.0
            s0 += w; s1 += w * x; s2 += w * x * x; s3 += w * x * x * x; s4 += w * x * x * x * x
            t0 += w * y; t1 += w * x * y; t2 += w * x * x * y
        }
        // Normalize by the total weight so the singularity test is scale-free.
        val det = det3(s4, s3, s2, s3, s2, s1, s2, s1, s0) / (s0 * s0 * s0)
        if (abs(det) < 1e-12)
            return null
        val au = det3(t2, s3, s2, t1, s2, s1, t0, s1, s0) / (det * s0 * s0 * s0)
        val bu = det3(s4, t2, s2, s3, t1, s1, s2, t0, s0) / (det * s0 * s0 * s0)
        val cu = det3(s4, s3, t2, s3, s2, t1, s2, s1, t0) / (det * s0 * s0 * s0)
        // Undo the scaling and centring: au u^2 + bu u + cu with u = (x - x0) / scale.
        val a = au / (scale * scale)
        val bb = bu / scale - 2 * a * x0
        val cc = a * x0 * x0 - bu * x0 / scale + cu
        var ss = 0.0
        var wTotal = 0.0
        for (p in pts) {
            val w = if (weighted) 1 / (p.ownError * p.ownError) else 1.0
            val d = p.metric - (a * p.value * p.value + bb * p.value + cc)
            ss += w * d * d
            wTotal += w
        }
        // Weighted RMS residual in metric units: with 1/sigma^2 weights this is sigma * sqrt(chi^2 / n).
        val residual = if (weighted) sqrt(ss / pts.size) * medianError(pts) else sqrt(ss / wTotal)
        return SweepFit(a, bb, cc, residual)
    }

    fun summarize(sweep: Sweep, points: List<SweepPoint>): SweepSummary {
        val minimize = minimizes(sweep.kind)
        val usable = points.filter { it.usable }
        val pooled = pool(points)
        val distinct = pooled.size
        val unit = sweep.parameter
        val typicalError = medianError(pooled)
        if (distinct < MIN_POINTS) {
            val note = if (points.any { it.value == null })
                "Set the ${sweep.parameter.label.lowercase(Locale.US)} on every run, then record at least $MIN_POINTS different values to fit an optimum."
            else
                "Record at least $MIN_POINTS different values to fit an optimum (${distinct} so far)."
            return SweepSummary(points, pooled, null, typicalError, null, Double.NaN, note, suggest(sweep, nextAfterTwo(pooled, minimize)))
        }
        val fit = fitPooled(pooled)
            ?: return SweepSummary(points, pooled, null, typicalError, null, Double.NaN, "The values tried do not determine a curve.")
        val lo = pooled.first().value
        val hi = pooled.last().value
        val span = hi - lo
        val step = span / (distinct - 1)
        val best = if (minimize) pooled.minByOrNull { it.metric }!! else pooled.maxByOrNull { it.metric }!!
        val sigma = maxOf(fit.residualRms, typicalError)
        val spread = pooled.maxOf { it.metric } - pooled.minOf { it.metric }
        // Nothing to optimize yet when every value agrees within its error bars.
        if (sigma > 0 && spread < 2 * sigma) {
            val note = buildString {
                append(
                    "The values differ by %.1f%s between best and worst, less than twice their uncertainty (%.1f%s). Spread the values further apart or repeat runs before reading an optimum.".format(
                        Locale.US, spread, if (minimize) " dB" else "", sigma, if (minimize) " dB" else "",
                    )
                )
                if (minimize)
                    durationHint(usable)?.let { append(" ").append(it) }
            }
            return SweepSummary(points, pooled, fit, sigma, null, Double.NaN, note, suggest(sweep, if (best.value >= hi) hi + step else lo - step))
        }
        val curvatureRight = if (minimize) fit.a > 0 else fit.a < 0
        if (!curvatureRight) {
            // The curve bends the wrong way: the best value is at an end, and the trend says which.
            val further = if (best.value >= hi) "larger" else "smaller"
            return SweepSummary(
                points, pooled, fit, sigma, null, Double.NaN,
                "No optimum inside %s-%s: the best value is %s and the trend continues toward %s values. Extend the sweep that way.".format(
                    Locale.US, unit.format(lo), unit.format(hi), unit.format(best.value), further,
                ),
                suggest(sweep, if (best.value >= hi) hi + step else lo - step),
            )
        }
        val x = fit.extremum
        val halfWidth = if (sigma > 0 && fit.a != 0.0) sqrt(sigma / abs(fit.a)) else Double.NaN
        val margin = 0.25 * span
        if (x < lo - margin || x > hi + margin) {
            val side = if (x < lo) "below" else "above"
            // Head toward it, but no further than one more span: far extrapolations of a parabola are guesses.
            val next = suggest(sweep, x.coerceIn(lo - span, hi + span))
            return SweepSummary(
                points, pooled, fit, sigma, x, halfWidth,
                "The fitted optimum, %s, lies %s the range tried (%s-%s). Extend the sweep toward it before trusting the number.".format(
                    Locale.US, unit.format(x), side, unit.format(lo), unit.format(hi),
                ),
                next,
            )
        }
        val gain = if (minimize) "%.1f dB below baseline".format(Locale.US, -fit.at(x)) else "damping ratio %.4f".format(Locale.US, fit.at(x))
        val pm = if (halfWidth.isFinite()) " (+/- %s at one sigma)".format(Locale.US, unit.format(halfWidth)) else ""
        // A prediction counts as confirmed once a value sits inside the one-sigma interval around it.
        val reach = if (halfWidth.isFinite()) halfWidth else 0.1 * span
        val confirmed = pooled.any { abs(it.value - x) <= reach }
        val note = buildString {
            append("Optimum near %s%s, %s.".format(Locale.US, unit.format(x), pm, gain))
            if (!confirmed)
                append(" No run has been made there yet; the curve predicts it from the neighbours. Record one at %s to confirm.".format(Locale.US, unit.format(x)))
        }
        return SweepSummary(points, pooled, fit, sigma, x, halfWidth, note, if (confirmed) null else x)
    }

    /**
     * What it would take to tell two values RESOLVE_DB apart at two sigma:
     * the random error falls with the square root of the run length, so the
     * answer is a run length (or a number of repeats of the current length).
     */
    fun durationHint(usable: List<SweepPoint>): String? {
        val errors = usable.map { it.ownError }.filter { it.isFinite() && it > 0 }.sorted()
        val durations = usable.map { it.durationSec }.filter { it > 0 }.sorted()
        if (errors.isEmpty() || durations.isEmpty())
            return null
        val eNow = errors[errors.size / 2]
        val tNow = durations[durations.size / 2]
        // Two points each with error e differ by RESOLVE_DB at two sigma when 2 * sqrt(2) * e = RESOLVE_DB.
        val eTarget = RESOLVE_DB / (2 * sqrt(2.0))
        if (eNow <= eTarget)
            return null
        val factor = (eNow / eTarget) * (eNow / eTarget)
        return "Each run of about %.0f s gives +/- %.1f dB; about %.0f s per value (or %d repeats of the current length) would resolve %.0f dB differences.".format(
            Locale.US, tNow, eNow, tNow * factor, ceil(factor).toInt(), RESOLVE_DB,
        )
    }

    /** A recommendation the parameter can take: masses and stiffnesses stop at zero. */
    private fun suggest(sweep: Sweep, value: Double?): Double? {
        if (value == null)
            return null
        return if (sweep.parameterNonNegative) value.coerceAtLeast(0.0) else value
    }

    /** With two values, the better one says which way to go; with one or none, nothing does. */
    private fun nextAfterTwo(pooled: List<PooledPoint>, minimize: Boolean): Double? {
        if (pooled.size != 2)
            return null
        val best = if (minimize) pooled.minByOrNull { it.metric }!! else pooled.maxByOrNull { it.metric }!!
        val step = pooled[1].value - pooled[0].value
        return if (best.value == pooled[1].value) pooled[1].value + step else pooled[0].value - step
    }

    /** Typical own error of a pooled point: the baseline's share cancels between values. */
    private fun medianError(pts: List<PooledPoint>): Double {
        val e = pts.map { it.ownError }.filter { it.isFinite() }.sorted()
        return if (e.isEmpty()) 0.0 else e[e.size / 2]
    }

    private fun det3(
        a: Double, b: Double, c: Double,
        d: Double, e: Double, f: Double,
        g: Double, h: Double, i: Double,
    ): Double = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
}
