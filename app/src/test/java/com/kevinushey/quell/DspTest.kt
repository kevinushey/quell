package com.kevinushey.quell

import com.kevinushey.quell.dsp.FFT
import com.kevinushey.quell.dsp.Normalization
import com.kevinushey.quell.dsp.Peaks
import com.kevinushey.quell.dsp.Resampler
import com.kevinushey.quell.dsp.Spectrum
import com.kevinushey.quell.dsp.SpectrumDiff
import com.kevinushey.quell.dsp.Welch
import com.kevinushey.quell.dsp.WindowType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class DspTest {

    @Test
    fun fftMatchesNaiveDft() {
        val n = 64
        val re = DoubleArray(n) { sin(0.3 * it) + 0.1 * it }
        val im = DoubleArray(n)
        val expRe = DoubleArray(n)
        val expIm = DoubleArray(n)
        for (k in 0 until n) {
            for (t in 0 until n) {
                val ang = -2 * PI * k * t / n
                expRe[k] += re[t] * Math.cos(ang)
                expIm[k] += re[t] * Math.sin(ang)
            }
        }
        FFT.transform(re, im)
        for (k in 0 until n) {
            assertEquals(expRe[k], re[k], 1e-6)
            assertEquals(expIm[k], im[k], 1e-6)
        }
    }

    @Test
    fun welchFindsSinePeakAndPreservesPower() {
        val fs = 400.0
        val f0 = 37.5
        val amp = 2.0
        val n = 16000
        val x = DoubleArray(n) { amp * sin(2 * PI * f0 * it / fs) }

        val psd = Welch.psd(x, fs, 1024, WindowType.HANN)
        val peak = psd.power.indices.maxByOrNull { psd.power[it] }!!
        assertEquals(f0, psd.freq[peak], psd.binWidth)

        // Parseval: integrating the one-sided PSD recovers the signal variance (amp^2 / 2).
        val variance = psd.power.sum() * psd.binWidth
        assertEquals(amp * amp / 2, variance, 0.05 * amp * amp / 2)
    }

    @Test
    fun segmentCountSurvivesRestrictSmoothAndNormalize() {
        val x = DoubleArray(8192) { Math.sin(it * 0.3) }
        val psd = Welch.psd(x, 400.0, 1024)
        assertEquals(15, psd.segments)
        val processed = Normalization.PEAK.apply(psd.restrictTo(1.0, 100.0).smoothed(3))
        assertEquals(15, processed.segments)
        assertEquals(15, Normalization.ZSCORE_DB.apply(processed).segments)
    }

    @Test
    fun resamplerHandlesDuplicateTimestamps() {
        val time = doubleArrayOf(0.0, 0.0, 0.0, 0.01, 0.02, 0.03, 0.04)
        val values = doubleArrayOf(1.0, 1.0, 1.0, 2.0, 3.0, 4.0, 5.0)
        val fs = Resampler.estimateSampleRate(time)
        assertEquals(100.0, fs, 1e-9)
        val out = Resampler.resample(time, values, fs)
        assertEquals(5, out.size)
        assertEquals(1.0, out[0], 1e-9)
        assertEquals(5.0, out[4], 1e-9)
    }

    @Test
    fun normalizationsBehave() {
        val s = Spectrum(doubleArrayOf(0.0, 1.0, 2.0, 3.0), doubleArrayOf(1.0, 4.0, 2.0, 1.0))
        assertEquals(1.0, Normalization.PEAK.apply(s).power.max(), 1e-12)
        val total = Normalization.TOTAL_POWER.apply(s)
        assertEquals(1.0, total.power.sum() * total.binWidth, 1e-12)
        val z = Normalization.ZSCORE_DB.apply(s)
        assertEquals(0.0, z.power.average(), 1e-9)
        assertEquals(1.0, sqrt(z.power.sumOf { it * it } / z.size), 1e-9)
    }

    @Test
    fun peaksRespectSeparation() {
        val f = DoubleArray(100) { it.toDouble() }
        val v = DoubleArray(100) { if (it == 50) 10.0 else if (it == 51) 9.5 else if (it == 20) 5.0 else 0.0 }
        val peaks = Peaks.find(f, v, 2, minSeparationBins = 3)
        assertEquals(listOf(20.0, 50.0), peaks.map { it.freq })
    }

    @Test
    fun diffIsZeroForIdenticalSpectraAndFindsDivergence() {
        val f = DoubleArray(200) { it * 0.5 }
        val a = Spectrum(f, DoubleArray(200) { 1.0 })
        val b = Spectrum(f, DoubleArray(200) { if (it == 80) 100.0 else 1.0 })
        val same = SpectrumDiff.compute(a, a, decibelDomain = false)
        assertEquals(0.0, same.rms, 1e-12)
        val diff = SpectrumDiff.compute(a, b, decibelDomain = false)
        assertEquals(40.0, diff.topBands.first().freq, 1e-9)
        assertEquals(20.0, diff.topBands.first().delta, 1e-9)
        assertTrue(abs(diff.delta[0]) < 1e-12)
    }
}

class DamperDspTest {

    private fun lorentzianSpectrum(fn: Double, zeta: Double, df: Double = 0.1, fmax: Double = 100.0, floor: Double = 1e-4): com.kevinushey.quell.dsp.Spectrum {
        val n = (fmax / df).toInt() + 1
        val f = DoubleArray(n) { it * df }
        // Single-degree-of-freedom response magnitude squared, plus a flat floor.
        val p = DoubleArray(n) { i ->
            val r = f[i] / fn
            1.0 / ((1 - r * r) * (1 - r * r) + (2 * zeta * r) * (2 * zeta * r)) + floor
        }
        return com.kevinushey.quell.dsp.Spectrum(f, p)
    }

    @Test
    fun halfPowerBandwidthRecoversDampingRatio() {
        val s = lorentzianSpectrum(fn = 25.0, zeta = 0.03)
        val peak = com.kevinushey.quell.dsp.Modal.dominantPeak(s, 10.0, 40.0)!!
        assertEquals(25.0, peak.freq, 0.1)
        assertEquals(0.03, peak.dampingRatio, 0.004)
    }

    @Test
    fun splitPeakImpliesMassRatio() {
        // Two SDOF-like peaks at the undamped-absorber frequencies for mu = 0.05 around fn = 25 Hz.
        val mu = 0.05
        val fn = 25.0
        val a = 1 + mu / 2
        val b = Math.sqrt(mu + mu * mu / 4)
        val f1 = fn * Math.sqrt(a - b)
        val f2 = fn * Math.sqrt(a + b)
        val df = 0.1
        val n = (60 / df).toInt()
        val f = DoubleArray(n) { it * df }
        val p = DoubleArray(n) { i ->
            fun lor(fc: Double) = 1.0 / ((1 - (f[i] / fc) * (f[i] / fc)).let { it * it } + 0.004)
            lor(f1) + lor(f2) + 1e-3
        }
        val s = com.kevinushey.quell.dsp.Spectrum(f, p)
        val split = com.kevinushey.quell.dsp.Modal.splitPeak(s, 15.0, 35.0)!!
        assertEquals(f1, split.lower.freq, 0.15)
        assertEquals(f2, split.upper.freq, 0.15)
        assertEquals(mu, split.impliedMassRatio, 0.01)
        assertEquals(fn, split.impliedNaturalFreq, 0.2)
    }

    @Test
    fun bandRmsMatchesSignalRms() {
        val fs = 400.0
        val x = DoubleArray(40000) { 1.5 * Math.sin(2 * Math.PI * 30 * it / fs) }
        val psd = com.kevinushey.quell.dsp.Welch.psd(x, fs, 1024)
        // RMS of a 1.5 amplitude sine is 1.5/sqrt(2).
        assertEquals(1.5 / Math.sqrt(2.0), psd.bandRms(20.0, 40.0), 0.03)
    }

    @Test
    fun ringDownRecoversFrequencyAndDamping() {
        val fs = 400.0
        val fn = 22.0
        val zeta = 0.025
        val n = 2000
        val impulseAt = 300
        val x = DoubleArray(n) { i ->
            if (i < impulseAt) 0.01 * Math.sin(i * 0.1)
            else {
                val t = (i - impulseAt) / fs
                5.0 * Math.exp(-zeta * 2 * Math.PI * fn * t) * Math.sin(2 * Math.PI * fn * t)
            }
        }
        val r = com.kevinushey.quell.dsp.RingDown.analyze(x, fs, impulseThreshold = 1.0)!!
        assertEquals(fn, r.naturalFreq, 0.4)
        assertEquals(zeta, r.dampingRatio, 0.004)
        assertTrue("r2=${r.fitR2}", r.fitR2 > 0.98)
    }
}


class TuningTest {

    private val fn = 25.0

    /**
     * Acceleration PSD of a structure (fn, zetaS) under force excitation,
     * carrying an absorber with mass ratio mu, tuning r and damping zetaA
     * (mu = 0: no absorber), sampled on a 1024-point grid at 419 Hz that is
     * offset so no peak lands on a bin centre.
     */
    private fun absorberSpectrum(mu: Double, r: Double, zetaA: Double, zetaS: Double = 0.02): Spectrum {
        val df = 419.0 / 1024
        val f = DoubleArray((60 / df).toInt()) { 0.13 + it * df }
        val p = DoubleArray(f.size) { i ->
            val g = f[i] / fn
            val x = if (mu == 0.0) {
                1 / sqrt((1 - g * g) * (1 - g * g) + (2 * zetaS * g) * (2 * zetaS * g))
            } else {
                // x = d / (a d - b^2) for the two-mass system, normalized by the structure's stiffness.
                val aRe = 1 + mu * r * r - g * g
                val aIm = 2 * g * (zetaS + zetaA * mu * r)
                val bRe = mu * r * r
                val bIm = 2 * g * zetaA * mu * r
                val dRe = mu * r * r - mu * g * g
                val dIm = bIm
                val denRe = aRe * dRe - aIm * dIm - (bRe * bRe - bIm * bIm)
                val denIm = aRe * dIm + aIm * dRe - 2 * bRe * bIm
                sqrt(dRe * dRe + dIm * dIm) / sqrt(denRe * denRe + denIm * denIm)
            }
            (x * g * g) * (x * g * g)
        }
        return Spectrum(f, p)
    }

    @Test
    fun optimaMatchTextbookValues() {
        // mu = 0.05: equal acceleration peaks at 1/sqrt(1.05) = 0.976 (Den Hartog's displacement optimum is 0.952).
        assertEquals(0.9759, com.kevinushey.quell.dsp.Tuning.optimalTuningRatio(0.05), 1e-3)
        // zeta_opt = sqrt(3*0.05 / (8*1.05^3)) = 0.1273
        assertEquals(0.1273, com.kevinushey.quell.dsp.Tuning.optimalDampingRatio(0.05), 1e-3)
        // Baseline magnification 1/(2*0.02) = 25 -> 28 dB; optimum sqrt(1 + 40) = 6.4 -> 16.1 dB; reduction ~ -11.9 dB.
        assertEquals(-11.84, com.kevinushey.quell.dsp.Tuning.optimalReductionDb(0.05, 0.02), 0.05)
    }

    @Test
    fun peakFrequenciesAreReadBetweenBins() {
        // The baseline resonance sits at 25 Hz on a grid with bins at 24.68 and 25.09 Hz.
        val peak = com.kevinushey.quell.dsp.Modal.dominantPeak(absorberSpectrum(0.0, 1.0, 0.0), 15.0, 35.0)!!
        assertEquals(fn, peak.freq, 0.05)
    }

    @Test
    fun theAbsorbersFrequencyMassAndSpringAreRecovered() {
        // mu = 0.05 tuned 12% low (r = 0.86), lightly damped: a clear split, much like a first try with too much mass.
        val mu = 0.05
        val r = 0.86
        val zetaA = 0.4 * com.kevinushey.quell.dsp.Tuning.optimalDampingRatio(mu)
        val basePeak = com.kevinushey.quell.dsp.Modal.dominantPeak(absorberSpectrum(0.0, 1.0, 0.0), 15.0, 35.0)!!
        val damped = absorberSpectrum(mu, r, zetaA)
        val split = com.kevinushey.quell.dsp.Modal.splitPeak(damped, 15.0, 35.0)!!
        val a = com.kevinushey.quell.dsp.Tuning.absorber(basePeak.freq, split.lower.freq, split.upper.freq, 0.030)!!
        assertEquals(r * fn, a.freq, 0.015 * r * fn)
        assertEquals(mu, a.massRatio, 0.15 * mu)
        // 30 g as the moving mass: k = m (2 pi fa)^2 and the mode's mass m / mu.
        assertEquals(0.030 * (2 * PI * a.freq) * (2 * PI * a.freq), a.stiffness, 1e-9)
        assertEquals(0.030 / a.massRatio, a.modalMass, 1e-9)
        // The suggested mass on this spring does tune it: sqrt(k / m) lands on fn / sqrt(1 + m / M), with fn as measured.
        val measuredFn = a.freq / a.tuningRatio
        val fAtOptimum = sqrt(a.stiffness / a.optimalMass) / (2 * PI)
        assertEquals(measuredFn / sqrt(1 + a.optimalMass / a.modalMass), fAtOptimum, 1e-6)
        assertTrue("tuned low wants less mass: ${a.optimalMass}", a.optimalMass < 0.030)
        assertEquals(a.optimalTuningRatio * measuredFn, sqrt(a.optimalStiffness / 0.030) / (2 * PI), 1e-6)

        // The diagnosis reads the same story from the peak heights and quotes the numbers.
        val d = com.kevinushey.quell.dsp.Tuning.diagnose(basePeak, damped, 15.0, 35.0, split, 30.0)!!
        assertEquals(com.kevinushey.quell.dsp.DampingVerdict.DETUNED_LOW, d.verdict)
        assertTrue(d.advice, d.advice.contains("rings at about"))
        assertEquals(a.massRatio, d.massRatio, 1e-12)
    }

    @Test
    fun aWellTunedAbsorberIsNotCalledDetuned() {
        // Equal acceleration peaks at 1/sqrt(1 + mu): with the old displacement target (1/(1 + mu)) the upper peak of a
        // mu = 0.1 absorber stands about 6 dB taller and the 4 dB balance rule called it tuned low.
        val mu = 0.1
        val zetaA = 0.5 * com.kevinushey.quell.dsp.Tuning.optimalDampingRatio(mu)
        val basePeak = com.kevinushey.quell.dsp.Modal.dominantPeak(absorberSpectrum(0.0, 1.0, 0.0), 15.0, 35.0)!!
        val damped = absorberSpectrum(mu, com.kevinushey.quell.dsp.Tuning.optimalTuningRatio(mu), zetaA)
        val split = com.kevinushey.quell.dsp.Modal.splitPeak(damped, 15.0, 35.0)!!
        val d = com.kevinushey.quell.dsp.Tuning.diagnose(basePeak, damped, 15.0, 35.0, split)!!
        // About -2 dB at half the optimal damping: well inside the 4 dB rule.
        assertTrue("balance ${d.peakBalanceDb}", abs(d.peakBalanceDb) < 3)
        assertTrue(d.verdict.toString(), d.verdict != com.kevinushey.quell.dsp.DampingVerdict.DETUNED_LOW && d.verdict != com.kevinushey.quell.dsp.DampingVerdict.DETUNED_HIGH)
        assertEquals(1.0, d.absorber!!.tuningRatio / d.absorber!!.optimalTuningRatio, 0.015)
    }

    @Test
    fun aSplitWithinTheSpectrumsNoiseGivesNoAbsorberEstimate() {
        // The same clear split as above, then judged against a noisy spectrum (about 6 averages: 1.8 dB per bin).
        val basePeak = com.kevinushey.quell.dsp.Modal.dominantPeak(absorberSpectrum(0.0, 1.0, 0.0), 15.0, 35.0)!!
        val damped = absorberSpectrum(0.05, 0.86, 0.4 * com.kevinushey.quell.dsp.Tuning.optimalDampingRatio(0.05))
        val split = com.kevinushey.quell.dsp.Modal.splitPeak(damped, 15.0, 35.0)!!
        val notch = com.kevinushey.quell.dsp.Tuning.notchDepthDb(split)
        val clear = com.kevinushey.quell.dsp.Tuning.diagnose(basePeak, damped, 15.0, 35.0, split, noiseDb = 1.3)!!
        assertTrue("notch $notch", clear.absorber != null && clear.absorberWithheld == null)
        // A noise level that makes this notch insignificant withholds the estimate, and says why.
        val noisy = com.kevinushey.quell.dsp.Tuning.diagnose(basePeak, damped, 15.0, 35.0, split, noiseDb = notch / 2)!!
        org.junit.Assert.assertNull(noisy.absorber)
        assertTrue(noisy.absorberWithheld!!, noisy.absorberWithheld!!.contains("within this spectrum's noise"))
    }

    @Test
    fun aBaselineFrequencyOutsideTheSplitGivesNoAbsorberEstimate() {
        // A two-mass system always has fn between its two peaks; anything else is a different mode or a bad baseline.
        org.junit.Assert.assertNull(com.kevinushey.quell.dsp.Tuning.absorber(30.0, 22.0, 28.0))
        org.junit.Assert.assertNull(com.kevinushey.quell.dsp.Tuning.absorber(20.0, 22.0, 28.0))
        // Without a damper mass the frequencies still come out; the spring and masses do not.
        val a = com.kevinushey.quell.dsp.Tuning.absorber(25.0, 22.0, 28.0)!!
        assertTrue(a.stiffness.isNaN() && a.modalMass.isNaN() && a.optimalMass.isNaN())
        assertEquals(22.0 * 28.0 / 25.0, a.freq, 1e-12)
    }

    @Test
    fun diagnosisFlagsDetuning() {
        val df = 0.1
        val n = (60 / df).toInt()
        val f = DoubleArray(n) { it * df }
        fun lor(fc: Double, fi: Double, damp: Double) = 1.0 / ((1 - (fi / fc) * (fi / fc)).let { it * it } + damp)
        val baseline = com.kevinushey.quell.dsp.Spectrum(f, DoubleArray(n) { lor(25.0, f[it], 0.0016) + 1e-3 })
        val basePeak = com.kevinushey.quell.dsp.Modal.dominantPeak(baseline, 15.0, 35.0)!!
        // Upper peak 4x taller than lower, both sharp enough to leave a notch: absorber tuned too low.
        val damped = com.kevinushey.quell.dsp.Spectrum(f, DoubleArray(n) { 0.05 * lor(22.5, f[it], 0.001) + 0.2 * lor(27.5, f[it], 0.001) + 1e-3 })
        val split = com.kevinushey.quell.dsp.Modal.splitPeak(damped, 15.0, 35.0)
        val d = com.kevinushey.quell.dsp.Tuning.diagnose(basePeak, damped, 15.0, 35.0, split)!!
        assertEquals(com.kevinushey.quell.dsp.DampingVerdict.DETUNED_LOW, d.verdict)
    }

    @Test
    fun aShallowSplitShortOfOptimumIsNeverCalledOverDamped() {
        val df = 0.1
        val n = (60 / df).toInt()
        val f = DoubleArray(n) { it * df }
        fun lor(fc: Double, fi: Double, damp: Double) = 1.0 / ((1 - (fi / fc) * (fi / fc)).let { it * it } + damp)
        val baseline = com.kevinushey.quell.dsp.Spectrum(f, DoubleArray(n) { lor(25.0, f[it], 0.0016) + 1e-3 })
        val basePeak = com.kevinushey.quell.dsp.Modal.dominantPeak(baseline, 15.0, 35.0)!!
        // Balanced side peaks over a 4.5 dB notch: in the 2-DOF model that is roughly 0.6x optimal damping, not too much.
        val side = 0.5 * basePeak.power
        val split = com.kevinushey.quell.dsp.SplitPeak(
            com.kevinushey.quell.dsp.ModalPeak(22.5, side, 1.0),
            com.kevinushey.quell.dsp.ModalPeak(27.5, side, 1.0),
            25.0,
            side / Math.pow(10.0, 0.45),
        )
        // Quieter than baseline but about 8 dB short of the optimum for mu = 0.04, and louder than baseline.
        for (scale in doubleArrayOf(0.5, 2.0)) {
            val damped = com.kevinushey.quell.dsp.Spectrum(f, DoubleArray(n) { scale * baseline.power[it] })
            val d = com.kevinushey.quell.dsp.Tuning.diagnose(basePeak, damped, 15.0, 35.0, split)!!
            assertEquals(4.5, d.notchDepthDb, 1e-9)
            assertEquals(com.kevinushey.quell.dsp.DampingVerdict.INCONCLUSIVE, d.verdict)
        }
    }

    @Test
    fun diagnosisAgainstASilentBaselineIsNull() {
        val freq = DoubleArray(101) { it * 0.5 }
        val silent = com.kevinushey.quell.dsp.Spectrum(freq, DoubleArray(freq.size))
        val loud = com.kevinushey.quell.dsp.Spectrum(freq, DoubleArray(freq.size) { 1e-3 })
        val silentPeak = com.kevinushey.quell.dsp.Modal.dominantPeak(silent, 20.0, 30.0)!!
        org.junit.Assert.assertNull(com.kevinushey.quell.dsp.Tuning.diagnose(silentPeak, loud, 20.0, 30.0, null))
        org.junit.Assert.assertNull(com.kevinushey.quell.dsp.Tuning.diagnose(silentPeak, silent, 20.0, 30.0, null))
    }

    @Test
    fun diagnosisWithoutBaselineDampingIsInconclusive() {
        val df = 0.1
        val n = (60 / df).toInt()
        val f = DoubleArray(n) { it * df }
        fun lor(fc: Double, fi: Double, damp: Double) = 1.0 / ((1 - (fi / fc) * (fi / fc)).let { it * it } + damp)
        // Baseline peak whose half-power points were never found: bandwidth NaN, so no optimum exists.
        val basePeak = com.kevinushey.quell.dsp.ModalPeak(25.0, lor(25.0, 25.0, 0.0016), Double.NaN)
        val damped = com.kevinushey.quell.dsp.Spectrum(f, DoubleArray(n) { 0.1 * lor(22.5, f[it], 0.001) + 0.1 * lor(27.5, f[it], 0.001) + 1e-3 })
        val split = com.kevinushey.quell.dsp.Modal.splitPeak(damped, 15.0, 35.0)!!
        val d = com.kevinushey.quell.dsp.Tuning.diagnose(basePeak, damped, 15.0, 35.0, split)!!
        assertTrue(d.verdict != com.kevinushey.quell.dsp.DampingVerdict.NEAR_OPTIMAL)
        assertTrue(d.advice.contains("optimum is unknown") || d.advice.contains("above baseline") || d.advice.contains("notch"))
    }
}
