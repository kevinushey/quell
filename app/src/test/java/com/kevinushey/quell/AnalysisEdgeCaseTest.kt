package com.kevinushey.quell

import com.kevinushey.quell.dsp.Normalization
import com.kevinushey.quell.dsp.Resampler
import com.kevinushey.quell.dsp.Spectrogram
import com.kevinushey.quell.dsp.Spectrum
import com.kevinushey.quell.dsp.SpectrumDiff
import com.kevinushey.quell.dsp.Welch
import com.kevinushey.quell.dsp.WindowType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class AnalysisEdgeCaseTest {

    @Test
    fun shortConstantSignalHasNoSpuriousPower() {
        // Gravity on a stationary phone, shorter than one FFT segment.
        val x = DoubleArray(300) { 9.81 }
        val psd = Welch.psd(x, 400.0, 1024)
        assertEquals(1, psd.segments)
        assertTrue("padding step leaked power: ${psd.power.sum()}", psd.power.sum() < 1e-20)
    }

    @Test
    fun shortSineKeepsItsPower() {
        val fs = 400.0
        val amp = 2.0
        val x = DoubleArray(600) { 9.81 + amp * sin(2 * PI * 25.0 * it / fs) }
        val psd = Welch.psd(x, fs, 1024)
        val variance = psd.power.sum() * psd.binWidth
        assertEquals(amp * amp / 2, variance, 0.15 * amp * amp / 2)
    }

    @Test
    fun resamplerRefusesAbsurdSpans() {
        val time = DoubleArray(50) { it * 0.01 } + 1.7e9
        try {
            Resampler.resample(time, DoubleArray(51), 100.0)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("samples"))
        }
        assertTrue(Resampler.resampledLength(time, 100.0) > Resampler.MAX_SAMPLES)
    }

    @Test
    fun diffUsesOnlySharedFrequencies() {
        val ref = Spectrum(DoubleArray(201) { it.toDouble() }, DoubleArray(201) { 1.0 })
        val other = Spectrum(DoubleArray(51) { it.toDouble() }, DoubleArray(51) { 1.0 })
        val diff = SpectrumDiff.compute(ref, other, decibelDomain = false)
        assertEquals(51, diff.freq.size)
        assertEquals(50.0, diff.freq.last(), 0.0)
        assertEquals(0.0, diff.rms, 1e-9)

        val aligned = other.onGrid(ref.freq)
        assertTrue(aligned.power[100].isNaN())
    }

    @Test
    fun uncoveredBandsAreNotMeasured() {
        val s = Spectrum(DoubleArray(51) { it.toDouble() }, DoubleArray(51) { 1.0 })
        assertTrue(s.bandRms(60.0, 150.0).isNaN())
        assertTrue(Normalization.REFERENCE_BAND.referenceFactor(s, 60.0, 150.0).isNaN())
        assertEquals(sqrt(11.0), s.bandRms(10.0, 20.0), 1e-9)
        assertEquals(1.0, Normalization.REFERENCE_BAND.referenceFactor(s, 10.0, 20.0), 1e-9)
    }

    @Test
    fun effectiveSmoothingReflectsBinsActuallyCombined() {
        assertEquals(2, Spectrum.effectiveSmoothingBins(2, 3))
        assertEquals(2, Spectrum.effectiveSmoothingBins(2, 15))
        assertTrue(Spectrum.effectiveSmoothingBins(1000, 15) in 14..15)
        assertEquals(1, Spectrum.effectiveSmoothingBins(1000, 1))
    }

    @Test
    fun overlapAndWindowCorrelationReduceEffectiveAverages() {
        val none = Welch.effectiveSegments(20, 1024, WindowType.HANN, 0.0)
        val half = Welch.effectiveSegments(20, 1024, WindowType.HANN, 0.5)
        val heavy = Welch.effectiveSegments(20, 1024, WindowType.HANN, 0.9)
        assertEquals(20.0, none, 1e-9)
        assertTrue(half < none && half > 0.9 * none)
        assertTrue(heavy < half)
        assertEquals(1.0, Welch.effectiveSegments(1, 1024, WindowType.HANN, 0.5), 0.0)

        val bins = Welch.effectiveSmoothingBins(3, 1024, WindowType.HANN)
        assertTrue("got $bins", bins > 1.0 && bins < 3.0)
        assertEquals(1.0, Welch.effectiveSmoothingBins(1, 1024, WindowType.HANN), 0.0)
        // A rectangular window leaves bins uncorrelated, so smoothing counts in full.
        assertEquals(3.0, Welch.effectiveSmoothingBins(3, 1024, WindowType.RECTANGULAR), 1e-6)
    }

    @Test
    fun totalSpectrogramSumsAxesInsteadOfMagnitude() {
        val fs = 400.0
        val n = 4096
        val x = DoubleArray(n) { 0.5 * sin(2 * PI * 20.0 * it / fs) }
        val y = DoubleArray(n)
        val z = DoubleArray(n) { 9.81 }
        val total = Spectrogram.computeTotal(x, y, z, fs, 512)
        val magnitude = Spectrogram.compute(DoubleArray(n) { sqrt(x[it] * x[it] + z[it] * z[it]) }, fs, 512)

        val bin20 = (20.0 / total.freq[1]).toInt()
        val bin40 = (40.0 / total.freq[1]).toInt()
        val frame = total.db.size / 2
        // |a| modulates at twice the vibration frequency; the per-axis sum must not.
        assertTrue(total.db[frame][bin20] - total.db[frame][bin40] > 40)
        assertTrue(magnitude.db[frame][bin40] > total.db[frame][bin40] + 20)
        assertTrue(total.frameSpacing > 0)
        assertEquals(total.times[1] - total.times[0], total.frameSpacing, 1e-9)
    }
}
