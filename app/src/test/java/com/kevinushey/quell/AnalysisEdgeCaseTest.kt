package com.kevinushey.quell

import com.kevinushey.quell.dsp.Decimate
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
    fun shortSignalWindowIsSizedToItsSamples() {
        // 512 samples under a 1024-point segment: the taper must reach zero at the last real sample.
        val fs = 400.0
        val x = DoubleArray(512) { sin(2 * PI * 50.0 * it / fs) }
        val psd = Welch.psd(x, fs, 1024)
        val peak = psd.power.indices.maxByOrNull { psd.power[it] }!!
        assertEquals(50.0, psd.freq[peak], 2 * psd.binWidth)
        val far = psd.power.indices.filter { kotlin.math.abs(psd.freq[it] - 50.0) > 20 }.maxOf { psd.power[it] }
        assertTrue("leakage too high: ${far / psd.power[peak]}", far < 1e-4 * psd.power[peak])
    }

    @Test
    fun fullBandIntegralRecoversSignalPower() {
        // Alternating +1/-1 puts all power in the Nyquist bin, which Welch scales one-sided.
        val fs = 400.0
        val x = DoubleArray(4096) { if (it % 2 == 0) 1.0 else -1.0 }
        val psd = Welch.psd(x, fs, 1024, WindowType.RECTANGULAR, 0.5)
        assertEquals(1.0, psd.bandRms(0.0, 200.0), 0.02)

        val tone = DoubleArray(16000) { 2.0 * sin(2 * PI * 37.5 * it / fs) }
        val tonePsd = Welch.psd(tone, fs, 1024, WindowType.HANN)
        assertEquals(sqrt(2.0), tonePsd.bandRms(0.0, 200.0), 0.05)
    }

    @Test
    fun nanOutsideBandDoesNotPoisonIntegration() {
        val narrow = Spectrum(DoubleArray(51) { it.toDouble() }, DoubleArray(51) { 1.0 })
        val wide = narrow.onGrid(DoubleArray(201) { it.toDouble() })
        assertTrue(wide.power[100].isNaN())
        assertEquals(sqrt(10.0), wide.bandRms(10.0, 20.0), 1e-9)
        assertEquals(1.0, wide.meanPower(10.0, 20.0), 1e-9)
    }

    @Test
    fun resampledRefusesWindowsBeyondTheBudget() {
        // 100 s at 400 Hz, a 20,000 s gap, then a slow tail: about 8M samples at the 400 Hz median,
        // beyond the hard cap on every device, so the rejection is asserted unconditionally.
        val time = ArrayList<Double>()
        var t = 0.0
        for (i in 0 until 40_000) { time.add(t); t += 1.0 / 400 }
        t += 20_000.0
        for (i in 0 until 2_000) { time.add(t); t += 1.0 / 50 }
        val arr = time.toDoubleArray()
        val series = com.kevinushey.quell.data.TimeSeries(arr, DoubleArray(arr.size), DoubleArray(arr.size), DoubleArray(arr.size))
        assertTrue(Resampler.resampledLength(arr, Resampler.estimateSampleRate(arr)) > Resampler.MAX_SAMPLES)
        try {
            com.kevinushey.quell.ui.Resampled.from(series)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Trim"))
        }
    }

    @Test
    fun twoSampleSignalHasNoNaNPower() {
        // A two-point Hann window is all zeros; the PSD must stay finite.
        val psd = Welch.psd(doubleArrayOf(1.0, 2.0), 400.0, 1024)
        for (p in psd.power)
            assertTrue("non-finite power $p", p.isFinite())
    }

    @Test
    fun resampledRefusesWindowsTooShortToAnalyze() {
        // A trim window catching two samples around a gap.
        val series = com.kevinushey.quell.data.TimeSeries(
            doubleArrayOf(1.0, 1.0025),
            doubleArrayOf(1.0, 2.0),
            DoubleArray(2),
            DoubleArray(2),
        )
        try {
            com.kevinushey.quell.ui.Resampled.from(series)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Widen"))
        }
    }

    @Test
    fun croppedSpectrumEdgesAreOrdinaryBins() {
        val full = Spectrum(DoubleArray(201) { it.toDouble() }, DoubleArray(201) { 1.0 })
        assertEquals(sqrt(201.0), full.bandRms(0.0, 200.0), 1e-9)
        val cropped = full.restrictTo(10.0, 20.0)
        assertEquals(sqrt(10.0), cropped.bandRms(10.0, 20.0), 1e-9)
        assertEquals(sqrt(10.0), cropped.smoothed(3).bandRms(10.0, 20.0), 1e-9)
        // Cropping that keeps the DC bin keeps its full width.
        assertEquals(sqrt(20.5), full.restrictTo(0.0, 20.0).bandRms(0.0, 20.0), 1e-9)
    }

    @Test
    fun bandRmsIsIndependentOfFrequencyGrid() {
        // Identical flat PSDs on 200 Hz and 400 Hz grids (256-point FFT) must integrate to the same band power.
        val coarse = Spectrum(DoubleArray(129) { it * 400.0 / 256 }, DoubleArray(129) { 1.0 })
        val fine = Spectrum(DoubleArray(129) { it * 200.0 / 256 }, DoubleArray(129) { 1.0 })
        assertEquals(1.0, coarse.bandRms(24.5, 25.5), 1e-9)
        assertEquals(1.0, fine.bandRms(24.5, 25.5), 1e-9)
        assertEquals(sqrt(10.0), coarse.bandRms(20.0, 30.0), 1e-9)
        assertEquals(1.0, coarse.meanPower(24.5, 25.5), 1e-9)
    }

    @Test
    fun singleBinRestrictionKeepsSpacing() {
        val s = Spectrum(DoubleArray(513) { it * 400.0 / 1024 }, DoubleArray(513) { 2.0 })
        val one = s.restrictTo(24.9, 25.1)
        assertEquals(1, one.size)
        assertEquals(s.binWidth, one.binWidth, 1e-12)
        val total = Normalization.TOTAL_POWER.apply(one)
        assertEquals(1.0 / one.binWidth, total.power[0], 1e-9)
        assertEquals(s.binWidth, one.smoothed(3).binWidth, 1e-12)
    }

    @Test
    fun repeatedTimestampsDoNotHideTheRate() {
        // Every other timestamp repeats; a fixed stride of 2 would see only zero gaps.
        val time = DoubleArray(160_001) { (it / 2) / 400.0 }
        assertEquals(400.0, Resampler.estimateSampleRate(time), 1e-6)
    }

    @Test
    fun analyzablePrefixRevalidatesAfterTruncation() {
        // A huge startup gap, then a fast burst, then a slow tail: cutting the tail raises the median rate.
        val time = ArrayList<Double>()
        time.add(0.0)
        var t = 19_999.92
        for (i in 0 until 21) { time.add(t); t += 1.0 / 400 }
        for (i in 0 until 40) { time.add(t); t += 1.0 / 200 }
        val arr = time.toDoubleArray()
        val max = 4_000_000L
        val kept = Resampler.analyzablePrefix(arr, max)
        val prefix = arr.copyOf(kept)
        assertTrue(kept < arr.size)
        assertTrue(kept < 2 || Resampler.resampledLength(prefix, Resampler.estimateSampleRate(prefix)) <= max)

        val fine = DoubleArray(1000) { it / 400.0 }
        assertEquals(1000, Resampler.analyzablePrefix(fine, max))

        // Dropping the fast tail lowers the median to 200 Hz, at which the long prefix fits.
        val mixed = ArrayList<Double>()
        mixed.add(0.0)
        var u = 10_001.0
        for (i in 0 until 20) { mixed.add(u); u += 0.005 }
        for (i in 0 until 22) { mixed.add(u); u += 0.0025 }
        val keptMixed = Resampler.analyzablePrefix(mixed.toDoubleArray(), max)
        assertTrue("kept only $keptMixed", keptMixed >= 42)

        // A longer fast tail: the full-capture median is 400 Hz, but the first 42 samples fit at 200 Hz.
        val longer = ArrayList<Double>()
        longer.add(0.0)
        var v = 10_001.0
        for (i in 0 until 20) { longer.add(v); v += 0.005 }
        for (i in 0 until 30) { longer.add(v); v += 0.0025 }
        val keptLonger = Resampler.analyzablePrefix(longer.toDoubleArray(), max)
        assertTrue("kept only $keptLonger", keptLonger >= 42)
        val keptPrefix = longer.toDoubleArray().copyOf(keptLonger)
        assertTrue(Resampler.resampledLength(keptPrefix, Resampler.estimateSampleRate(keptPrefix)) <= max)
    }

    @Test
    fun sampleRateEstimateSurvivesSubsampling() {
        val n = 300_000
        val time = DoubleArray(n) { it / 400.0 + if (it % 7 == 0) 0.0003 else 0.0 }
        assertEquals(400.0, Resampler.estimateSampleRate(time), 2.0)
    }

    @Test
    fun bandWithoutBinsIsNotCovered() {
        val s = Spectrum(DoubleArray(129) { it * 400.0 / 256 }, DoubleArray(129) { 1.0 })
        assertTrue(s.covers(24.0, 26.0))
        assertTrue(!s.covers(24.1, 24.3))
        assertTrue(!s.covers(30.0, 20.0))
        assertTrue(s.bandRms(24.1, 24.3).isNaN())
        assertTrue(Normalization.REFERENCE_BAND.referenceFactor(s, 24.1, 24.3).isNaN())
    }

    @Test
    fun emptyDiffIntersectionIsUnavailable() {
        val ref = Spectrum(DoubleArray(41) { 60.0 + it }, DoubleArray(41) { 1.0 })
        val other = Spectrum(DoubleArray(51) { it.toDouble() }, DoubleArray(51) { 1.0 })
        val diff = SpectrumDiff.compute(ref, other, decibelDomain = false)
        assertEquals(0, diff.freq.size)
        assertTrue(diff.rms.isNaN() && diff.meanAbs.isNaN())
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
        assertEquals(sqrt(10.0), s.bandRms(10.0, 20.0), 1e-9)
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
        // Heavy zero padding correlates neighbouring bins, so the same smoothing is worth less.
        val padded = Welch.effectiveSmoothingBins(15, 8192, WindowType.RECTANGULAR, samples = 256)
        assertTrue("got $padded", padded < 2.0)
        assertEquals(256, Welch.psd(DoubleArray(256) { sin(it * 0.3) }, 400.0, 8192).windowLength)
    }

    @Test
    fun decimationKeepsExtremesAndTimeOrder() {
        val n = 100_000
        val time = DoubleArray(n) { it * 0.0025 }
        val values = DoubleArray(n) { sin(it * 0.01) }
        values[54_321] = 50.0
        values[12_345] = -50.0
        val d = Decimate.minMax(time, values, maxPoints = 1000)
        assertTrue(d.time.size <= 1000)
        assertEquals(50.0, d.values.max(), 0.0)
        assertEquals(-50.0, d.values.min(), 0.0)
        for (i in 1 until d.time.size)
            assertTrue(d.time[i] >= d.time[i - 1])
        val small = Decimate.minMax(time.copyOf(10), values.copyOf(10), maxPoints = 1000)
        assertEquals(10, small.time.size)
    }

    @Test
    fun totalChannelKeepsWindowLength() {
        val r = com.kevinushey.quell.ui.Resampled.from(com.kevinushey.quell.data.TimeSeries(DoubleArray(256) { it / 400.0 }, DoubleArray(256) { sin(it * 0.3) }, DoubleArray(256), DoubleArray(256) { 9.81 }))
        val total = com.kevinushey.quell.ui.AnalysisEngine.rawPsd(r, com.kevinushey.quell.dsp.Channel.TOTAL, 8192, WindowType.RECTANGULAR, 0.5)
        assertEquals(256, total.windowLength)
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
