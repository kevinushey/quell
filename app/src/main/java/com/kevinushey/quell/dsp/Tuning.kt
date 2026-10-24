package com.kevinushey.quell.dsp

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

enum class DampingVerdict(val label: String) {
    UNDER_DAMPED("Under-damped absorber"),
    OVER_DAMPED("Over-damped absorber"),
    NO_EFFECT("No measurable effect"),
    DETUNED_LOW("Absorber tuned too low"),
    DETUNED_HIGH("Absorber tuned too high"),
    NEAR_OPTIMAL("Near optimal"),
    INCONCLUSIVE("Inconclusive"),
}

/**
 * Den Hartog comparison of a damped run against its baseline. All "optimal"
 * numbers assume a single structural mode with a classic tuned absorber under
 * broadband excitation; use them as guidance, not gospel.
 */
data class TuningDiagnosis(
    val massRatio: Double,
    val optimalTuningRatio: Double,
    val optimalDampingRatio: Double,
    /** Best achievable drop of the PSD peak versus baseline, dB (negative = lower). */
    val optimalReductionDb: Double,
    /** Measured drop of the tallest in-band PSD peak versus baseline, dB. */
    val achievedReductionDb: Double,
    /** 10 log10 of lower-peak power over upper-peak power; 0 means balanced. */
    val peakBalanceDb: Double,
    /** Depth of the notch below the lower of the two side peaks, dB. */
    val notchDepthDb: Double,
    val verdict: DampingVerdict,
    val advice: String,
)

object Tuning {

    /** Den Hartog optimal tuning ratio f_absorber / f_structure. */
    fun optimalTuningRatio(mu: Double): Double = 1.0 / (1.0 + mu)

    /** Den Hartog optimal absorber damping ratio. */
    fun optimalDampingRatio(mu: Double): Double = sqrt(3 * mu / (8 * (1 + mu).pow(3)))

    /**
     * Optimal peak reduction in dB. Baseline magnification is 1/(2 zeta_s);
     * the optimally damped absorber limits it to sqrt(1 + 2/mu).
     */
    fun optimalReductionDb(mu: Double, structureDamping: Double): Double {
        if (mu <= 0 || structureDamping <= 0)
            return Double.NaN
        val baseline = 1 / (2 * structureDamping)
        val best = sqrt(1 + 2 / mu)
        return 20 * log10(best / baseline)
    }

    fun diagnose(baselinePeak: ModalPeak, dampedSpectrum: Spectrum, lo: Double, hi: Double, split: SplitPeak?): TuningDiagnosis? {
        val dampedPeak = Modal.dominantPeak(dampedSpectrum, lo, hi) ?: return null
        // A silent band (all-zero PSD) has no level to compare; a ratio of zeros is not a diagnosis.
        if (!(baselinePeak.power > 0) || !(dampedPeak.power > 0))
            return null
        val achieved = 10 * log10(dampedPeak.power / baselinePeak.power)
        if (!achieved.isFinite())
            return null
        val zetaS = baselinePeak.dampingRatio

        if (split == null) {
            // One peak: either the absorber is so damped it rides with the structure, or it is barely coupled.
            val broadened = !dampedPeak.dampingRatio.isNaN() && !zetaS.isNaN() && dampedPeak.dampingRatio > 1.5 * zetaS
            val verdict = when {
                achieved > -1 -> DampingVerdict.NO_EFFECT
                broadened -> DampingVerdict.OVER_DAMPED
                achieved < -3 -> DampingVerdict.NEAR_OPTIMAL
                else -> DampingVerdict.INCONCLUSIVE
            }
            val advice = when {
                achieved > -1 -> "No split and no level drop: the absorber is not coupling to this mode. Check mounting stiffness and that its own frequency is near %.1f Hz.".format(java.util.Locale.US, baselinePeak.freq)
                broadened -> "Single broadened peak %.1f dB lower: the absorber adds damping but is too lossy to create the anti-resonance. Reduce absorber damping (stiffer, less lossy mount) to open a notch.".format(java.util.Locale.US, -achieved)
                achieved < -3 -> "Single peak lowered by %.1f dB without a visible split; the mass ratio may be too small to resolve. Try more mass to see whether a notch opens.".format(java.util.Locale.US, -achieved)
                else -> "Peak moved by %.1f dB, within run-to-run scatter. Repeat the baseline to establish the noise floor before reading anything into this.".format(java.util.Locale.US, achieved)
            }
            return TuningDiagnosis(Double.NaN, Double.NaN, Double.NaN, Double.NaN, achieved, Double.NaN, Double.NaN, verdict, advice)
        }

        val mu = split.impliedMassRatio.coerceAtLeast(1e-4)
        val optRatio = optimalTuningRatio(mu)
        val optZeta = optimalDampingRatio(mu)
        val optDb = optimalReductionDb(mu, zetaS)
        val balance = 10 * log10(split.lower.power / split.upper.power)
        val notch = 10 * log10(minOf(split.lower.power, split.upper.power) / split.notchPower)
        val shortfall = if (optDb.isNaN()) Double.NaN else achieved - optDb

        val verdict: DampingVerdict
        val advice: String
        when {
            abs(balance) > 4 && balance < 0 -> {
                verdict = DampingVerdict.DETUNED_LOW
                advice = "Upper peak is %.1f dB taller than the lower one: the absorber frequency sits below optimal. Remove a little mass or stiffen its spring.".format(java.util.Locale.US, -balance)
            }
            abs(balance) > 4 -> {
                verdict = DampingVerdict.DETUNED_HIGH
                advice = "Lower peak is %.1f dB taller than the upper one: the absorber frequency sits above optimal. Add a little mass or soften its spring.".format(java.util.Locale.US, balance)
            }
            notch > 10 && (shortfall.isNaN() || shortfall > 3) -> {
                verdict = DampingVerdict.UNDER_DAMPED
                advice = "Deep %.0f dB notch with tall side peaks: the absorber needs more damping. Target zeta about %.3f (a lossier mount or rubber element).".format(java.util.Locale.US, notch, optZeta)
            }
            // No over-damped case here: in the 2-DOF model even optimal damping leaves a notch under 1 dB, and more merges
            // the peaks, so any split splitPeak reports (3 dB or deeper) is on the under-damped side. Over-damping shows
            // up as the single broadened peak handled above.
            achieved >= 0 -> {
                verdict = DampingVerdict.INCONCLUSIVE
                advice = "Peaks are split but the tallest in-band peak is %.1f dB above baseline. Check that both runs cover the same mode and road input before tuning further.".format(java.util.Locale.US, achieved)
            }
            shortfall.isNaN() -> {
                // Without the baseline's half-power bandwidth inside the band there is no optimum to compare against.
                verdict = DampingVerdict.INCONCLUSIVE
                advice = "Balanced peaks with a %.0f dB notch, but the baseline's damping could not be estimated inside the target band, so the optimum is unknown. Widen the band to include the baseline peak's half-power points.".format(java.util.Locale.US, notch)
            }
            shortfall <= 3 -> {
                verdict = DampingVerdict.NEAR_OPTIMAL
                advice = "Balanced peaks within %.1f dB of the Den Hartog optimum for mass ratio %.3f.".format(java.util.Locale.US, shortfall, mu)
            }
            else -> {
                verdict = DampingVerdict.INCONCLUSIVE
                advice = "Peaks are balanced but %.1f dB short of optimum; damping is off in one direction. Compare notch depth across runs to see which.".format(java.util.Locale.US, shortfall)
            }
        }
        return TuningDiagnosis(mu, optRatio, optZeta, optDb, achieved, balance, notch, verdict, advice)
    }
}
