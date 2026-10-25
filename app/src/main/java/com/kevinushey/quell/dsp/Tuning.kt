package com.kevinushey.quell.dsp

import kotlin.math.PI
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
    /** The absorber's own frequency and spring, when the split is real and consistent with the baseline frequency. */
    val absorber: AbsorberEstimate? = null,
    /** Why a split gave no absorber estimate, for the screen; null when there is one or there is no split. */
    val absorberWithheld: String? = null,
)

/**
 * The absorber itself, worked out from the two split frequencies and the
 * structure's own frequency (the baseline's peak) rather than assuming the
 * absorber is tuned to it. Spring and masses need the damper mass, taken as
 * the absorber's moving mass; they are NaN without it.
 */
data class AbsorberEstimate(
    /** The absorber's own natural frequency, Hz. */
    val freq: Double,
    val massRatio: Double,
    /** Absorber frequency over the structure's. */
    val tuningRatio: Double,
    val optimalTuningRatio: Double,
    /** Spring stiffness, N/m. */
    val stiffness: Double,
    /** The mode's effective mass, kg. */
    val modalMass: Double,
    /** Mass on this spring that would tune it optimally, kg (heavier also raises the mass ratio; both are solved together). */
    val optimalMass: Double,
    /** Spring that would tune this mass optimally, N/m. */
    val optimalStiffness: Double,
)

object Tuning {

    /**
     * Optimal tuning ratio f_absorber / f_structure for the structure's
     * acceleration, which is what the phone measures and what the sweeps and
     * the peak balance work on: equal acceleration peaks come at 1/sqrt(1 + mu).
     * Den Hartog's 1/(1 + mu) equalizes displacement peaks instead, which in an
     * acceleration spectrum leaves the upper peak 2-6 dB taller for mu 0.02-0.1.
     */
    fun optimalTuningRatio(mu: Double): Double = 1.0 / sqrt(1.0 + mu)

    /**
     * The notch depth a split needs before its frequencies mean anything: two
     * sigma of the difference between two raw bins, each with one-sigma
     * random error `noiseDb`. Short runs at long segments (few averages) can
     * show a 3 dB "notch" between two noise bumps a bin or two apart.
     */
    fun requiredNotchDb(noiseDb: Double): Double = 2 * sqrt(2.0) * noiseDb

    fun notchDepthDb(split: SplitPeak): Double = 10 * log10(minOf(split.lower.power, split.upper.power) / split.notchPower)

    /**
     * The undamped two-mass relations f1 f2 = fn fa and
     * f1^2 + f2^2 = fn^2 + (1 + mu) fa^2 give the absorber's frequency and the
     * mass ratio with no tuning assumption. A split deep enough for
     * Modal.splitPeak means a lightly damped absorber, whose peaks sit within
     * about a percent of the undamped ones. Null unless fn lies between the
     * two peaks, which any two-mass system requires (and which is exactly
     * when the mass ratio comes out positive).
     */
    fun absorber(fn: Double, f1: Double, f2: Double, damperMassKg: Double? = null): AbsorberEstimate? {
        if (!(fn > 0 && f1 < fn && fn < f2))
            return null
        val fa = f1 * f2 / fn
        val mu = (f1 * f1 + f2 * f2 - fn * fn) / (fa * fa) - 1
        if (!(mu > 0))
            return null
        val rOpt = optimalTuningRatio(mu)
        val m = damperMassKg?.takeIf { it > 0 }
        val wn = 2 * PI * fn
        val k = if (m != null) m * (2 * PI * fa).pow(2) else Double.NaN
        val modal = if (m != null) m / mu else Double.NaN
        // On a fixed spring, fa = sqrt(k / m) and the target fn / sqrt(1 + m / M) meet at m = k / (wn^2 - k / M).
        val mOpt = if (m != null && wn * wn > k / modal) k / (wn * wn - k / modal) else Double.NaN
        val kOpt = if (m != null) m * (rOpt * wn).pow(2) else Double.NaN
        return AbsorberEstimate(fa, mu, fa / fn, rOpt, k, modal, mOpt, kOpt)
    }

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

    /**
     * `damperMassGrams` (the damped run's) adds the absorber's spring and the
     * mode's mass to the diagnosis. `noiseDb`, the damped spectrum's one-sigma
     * error per raw bin, withholds the absorber estimate for a split no
     * deeper than its noise.
     */
    fun diagnose(
        baselinePeak: ModalPeak,
        dampedSpectrum: Spectrum,
        lo: Double,
        hi: Double,
        split: SplitPeak?,
        damperMassGrams: Double? = null,
        noiseDb: Double = Double.NaN,
    ): TuningDiagnosis? {
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

        val notch = notchDepthDb(split)
        val resolved = noiseDb.isNaN() || notch >= requiredNotchDb(noiseDb)
        val absorber = if (resolved) absorber(baselinePeak.freq, split.lower.freq, split.upper.freq, damperMassGrams?.let { it / 1000 }) else null
        val withheld = when {
            !resolved -> "The %.1f dB notch is within this spectrum's noise (%.1f dB needed), so the absorber is not measured. Longer runs, or shorter segments, would settle whether the split is real.".format(
                java.util.Locale.US, notch, requiredNotchDb(noiseDb),
            )
            absorber == null -> "The reference's peak (%.2f Hz) is not between the two split peaks, as it would be for one mode with an absorber, so the absorber is not measured.".format(java.util.Locale.US, baselinePeak.freq)
            else -> null
        }
        // Without a measured absorber, fall back to assuming it is tuned to the split's centre.
        val mu = (absorber?.massRatio ?: split.impliedMassRatio).coerceAtLeast(1e-4)
        val optRatio = optimalTuningRatio(mu)
        // The measured frequencies, quoted only when they agree with the peak heights about which way to go.
        fun tuningNote(low: Boolean): String {
            val a = absorber ?: return ""
            if ((a.tuningRatio < a.optimalTuningRatio) != low)
                return ""
            val target = a.optimalTuningRatio * baselinePeak.freq
            val mass = if (a.optimalMass.isFinite()) "; about %.1f g on this spring".format(java.util.Locale.US, a.optimalMass * 1000) else ""
            return " The absorber rings at about %.1f Hz; %.1f Hz is optimal%s.".format(java.util.Locale.US, a.freq, target, mass)
        }
        val optZeta = optimalDampingRatio(mu)
        val optDb = optimalReductionDb(mu, zetaS)
        val balance = 10 * log10(split.lower.power / split.upper.power)
        val shortfall = if (optDb.isNaN()) Double.NaN else achieved - optDb

        val verdict: DampingVerdict
        val advice: String
        when {
            abs(balance) > 4 && balance < 0 -> {
                verdict = DampingVerdict.DETUNED_LOW
                advice = "Upper peak is %.1f dB taller than the lower one: the absorber frequency sits below optimal. Remove a little mass or stiffen its spring.".format(java.util.Locale.US, -balance) + tuningNote(low = true)
            }
            abs(balance) > 4 -> {
                verdict = DampingVerdict.DETUNED_HIGH
                advice = "Lower peak is %.1f dB taller than the upper one: the absorber frequency sits above optimal. Add a little mass or soften its spring.".format(java.util.Locale.US, balance) + tuningNote(low = false)
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
                // Probably under-damped (see above), but road input or a misread mass ratio can also leave a shortfall.
                advice = "Peaks are balanced but %.1f dB short of optimum. A visible split usually means the absorber needs more damping: try a lossier mount, targeting zeta about %.3f. If that does not close the gap, check that both runs had the same road input.".format(java.util.Locale.US, shortfall, optZeta)
            }
        }
        return TuningDiagnosis(mu, optRatio, optZeta, optDb, achieved, balance, notch, verdict, advice, absorber, withheld)
    }
}
