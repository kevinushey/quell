package com.kevinushey.quell.ui

import com.kevinushey.quell.data.AnalysisSettings
import com.kevinushey.quell.data.DatasetMeta
import com.kevinushey.quell.data.MemoryBudget
import com.kevinushey.quell.data.TimeSeries
import com.kevinushey.quell.dsp.Channel
import com.kevinushey.quell.dsp.Normalization
import com.kevinushey.quell.dsp.Peak
import com.kevinushey.quell.dsp.Peaks
import com.kevinushey.quell.dsp.Resampler
import com.kevinushey.quell.dsp.Spectrum
import com.kevinushey.quell.dsp.Welch
import com.kevinushey.quell.dsp.WindowType

/**
 * Uniformly resampled copy of a dataset, computed once per dataset.
 * [startTime] is where its first sample sits in the recording, in seconds:
 * nonzero for a trimmed window, so time axes can match the waveform.
 */
class Resampled(
    val sampleRate: Double,
    val x: DoubleArray,
    val y: DoubleArray,
    val z: DoubleArray,
    val magnitude: DoubleArray,
    val startTime: Double = 0.0,
) {
    val size: Int get() = x.size

    fun channel(c: Channel): DoubleArray = when (c) {
        Channel.X -> x
        Channel.Y -> y
        Channel.Z -> z
        Channel.MAGNITUDE, Channel.TOTAL -> magnitude
    }

    companion object {
        fun from(s: TimeSeries, startTime: Double = 0.0): Resampled {
            val fs = Resampler.estimateSampleRate(s.time)
            // A trim window can span a gap the import never had to resample; refuse before allocating.
            val length = Resampler.resampledLength(s.time, fs)
            val limit = MemoryBudget.maxSamples()
            // A window of a couple of samples around a gap has nothing to window or average.
            if (length < Resampler.MIN_SAMPLES)
                throw IllegalArgumentException(
                    "The analysis window holds only %d samples; at least %d are needed. Widen the window.".format(
                        java.util.Locale.US,
                        length,
                        Resampler.MIN_SAMPLES,
                    )
                )
            if (s.size >= 2 && length > limit)
                throw IllegalArgumentException(
                    "The analysis window spans %.0f s at %.0f Hz (%d samples), more than this device can analyze (%d). Trim the window to exclude the gap.".format(
                        java.util.Locale.US,
                        s.time.last() - s.time.first(),
                        fs,
                        length,
                        limit,
                    )
                )
            val x = Resampler.resample(s.time, s.x, fs)
            val y = Resampler.resample(s.time, s.y, fs)
            val z = Resampler.resample(s.time, s.z, fs)
            val m = DoubleArray(x.size) { Math.sqrt(x[it] * x[it] + y[it] * y[it] + z[it] * z[it]) }
            return Resampled(fs, x, y, z, m, startTime)
        }
    }
}

/** Display-ready spectrum for one dataset under the current settings. */
class SpectrumResult(
    val meta: DatasetMeta,
    /** Full-range absolute PSD before any normalization or restriction. */
    val raw: Spectrum,
    /** Normalized, frequency-restricted, smoothed spectrum in linear power (or z units). */
    val normalized: Spectrum,
    /** Values as plotted: dB, linear or z. */
    val display: DoubleArray,
    val peaks: List<Peak>,
    val sampleRate: Double,
    /** Scale applied by reference-band normalization (1.0 otherwise); lets the Damper tab honour it. */
    val referenceScale: Double,
    /** Set when reference-band normalization was requested but this run does not cover the band. */
    val referenceBandMissing: Boolean = false,
) {
    /**
     * Independent averages behind the estimate: effective Welch segments (after
     * overlap correlation) times effective smoothing bins (after window
     * correlation). This is what the random error depends on.
     */
    var effectiveAverages: Double = raw.segments.toDouble().coerceAtLeast(1.0)
        internal set

    /** One-sigma random error of the displayed (smoothed) PSD in dB: 10*log10(e) / sqrt(n). */
    val randomErrorDb: Double get() = if (effectiveAverages > 0) 4.343 / Math.sqrt(effectiveAverages) else Double.NaN

    /** Effective Welch segments alone, without display smoothing: what a reading of the raw spectrum rests on. */
    var effectiveSegments: Double = raw.segments.toDouble().coerceAtLeast(1.0)
        internal set

    /** One-sigma random error of a single raw bin in dB; the sweep's peak metric carries this one. */
    val segmentErrorDb: Double get() = if (effectiveSegments > 0) 4.343 / Math.sqrt(effectiveSegments) else Double.NaN
}

data class PsdKey(
    val id: String,
    val channel: Channel,
    val segmentSize: Int,
    val window: WindowType,
    val overlap: Double,
    val trimStart: Double,
    val trimEnd: Double,
)

object AnalysisEngine {

    fun rawPsd(r: Resampled, channel: Channel, segmentSize: Int, window: WindowType, overlap: Double): Spectrum {
        if (channel != Channel.TOTAL)
            return Welch.psd(r.channel(channel), r.sampleRate, segmentSize, window, overlap)

        val px = Welch.psd(r.x, r.sampleRate, segmentSize, window, overlap)
        val py = Welch.psd(r.y, r.sampleRate, segmentSize, window, overlap)
        val pz = Welch.psd(r.z, r.sampleRate, segmentSize, window, overlap)
        return Spectrum(px.freq, DoubleArray(px.size) { px.power[it] + py.power[it] + pz.power[it] }, px.segments, px.windowLength)
    }

    fun finish(meta: DatasetMeta, raw: Spectrum, sampleRate: Double, s: AnalysisSettings): SpectrumResult {
        val maxF = if (s.maxFreq > 0) minOf(s.maxFreq, raw.maxFreq) else raw.maxFreq
        val restricted = raw.restrictTo(s.minFreq, maxF).smoothed(s.smoothingBins)
        val refFactor = if (s.normalization == Normalization.REFERENCE_BAND)
            s.normalization.referenceFactor(raw, s.refBandLow, s.refBandHigh)
        else
            1.0
        // An uncovered band cannot be normalized; keep the run visible but flag it.
        val refMissing = refFactor.isNaN()
        val refScale = if (refMissing) 1.0 else refFactor
        val normalized = if (s.normalization == Normalization.REFERENCE_BAND)
            restricted.map { _, p -> p * refScale }
        else
            s.normalization.apply(restricted)
        val display = DoubleArray(normalized.size) { i ->
            when {
                s.normalization.isDecibelDomain -> normalized.power[i]
                s.decibels -> Normalization.toDb(normalized.power[i])
                else -> normalized.power[i]
            }
        }
        // Keep labelled peaks at least ~2% of the span apart so labels stay legible.
        val span = (normalized.freq.lastOrNull() ?: 0.0) - (normalized.freq.firstOrNull() ?: 0.0)
        val separation = if (normalized.binWidth > 0) Math.ceil(0.02 * span / normalized.binWidth).toInt().coerceAtLeast(4) else 4
        val peaks = if (s.showPeaks) Peaks.find(normalized.freq, display, s.peakCount, minSeparationBins = separation) else emptyList()
        val binsCombined = Spectrum.effectiveSmoothingBins(restricted.size, s.smoothingBins)
        val segments = Welch.effectiveSegments(raw.segments, s.segmentSize, s.window, s.overlap)
        val averages = segments *
            Welch.effectiveSmoothingBins(binsCombined, s.segmentSize, s.window, raw.windowLength.takeIf { it > 0 } ?: s.segmentSize)
        return SpectrumResult(meta, raw, normalized, display, peaks, sampleRate, refScale, refMissing)
            .also {
                it.effectiveAverages = averages.coerceAtLeast(1.0)
                it.effectiveSegments = segments.coerceAtLeast(1.0)
            }
    }

    fun yAxisLabel(s: AnalysisSettings): String = when {
        s.normalization == Normalization.ZSCORE_DB -> "Z-score of dB power"
        s.decibels -> when (s.normalization) {
            Normalization.NONE -> "PSD (dB re 1 (m/s^2)^2/Hz)"
            Normalization.REFERENCE_BAND -> "PSD (dB re reference band)"
            Normalization.PEAK -> "PSD (dB re peak)"
            Normalization.TOTAL_POWER -> "PSD (dB re total power)"
            Normalization.MEDIAN -> "PSD (dB re median)"
            Normalization.ZSCORE_DB -> "z"
        }
        else -> when (s.normalization) {
            Normalization.NONE -> "PSD ((m/s^2)^2/Hz)"
            Normalization.REFERENCE_BAND -> "PSD (ratio to reference band)"
            Normalization.PEAK -> "PSD (fraction of peak)"
            Normalization.TOTAL_POWER -> "PSD (1/Hz, unit area)"
            Normalization.MEDIAN -> "PSD (multiple of median)"
            Normalization.ZSCORE_DB -> "z"
        }
    }

    fun valueUnit(s: AnalysisSettings): String = when {
        s.normalization == Normalization.ZSCORE_DB -> "z"
        s.decibels -> "dB"
        else -> ""
    }
}
