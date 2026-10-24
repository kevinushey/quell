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

    @Test
    fun denHartogOptimaMatchTextbookValues() {
        // mu = 0.05: f ratio 0.952, zeta_opt = sqrt(3*0.05 / (8*1.05^3)) = 0.1273
        assertEquals(0.9524, com.kevinushey.quell.dsp.Tuning.optimalTuningRatio(0.05), 1e-3)
        assertEquals(0.1273, com.kevinushey.quell.dsp.Tuning.optimalDampingRatio(0.05), 1e-3)
        // Baseline magnification 1/(2*0.02) = 25 -> 28 dB; optimum sqrt(1 + 40) = 6.4 -> 16.1 dB; reduction ~ -11.9 dB.
        assertEquals(-11.84, com.kevinushey.quell.dsp.Tuning.optimalReductionDb(0.05, 0.02), 0.05)
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
