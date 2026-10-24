package com.kevinushey.quell.ui

import com.kevinushey.quell.data.AnalysisSettings
import com.kevinushey.quell.data.DatasetMeta
import com.kevinushey.quell.data.TimeSeries
import com.kevinushey.quell.dsp.Channel
import com.kevinushey.quell.dsp.Normalization
import com.kevinushey.quell.dsp.Peak
import com.kevinushey.quell.dsp.Peaks
import com.kevinushey.quell.dsp.Resampler
import com.kevinushey.quell.dsp.Spectrum
import com.kevinushey.quell.dsp.Welch
import com.kevinushey.quell.dsp.WindowType

/** Uniformly resampled copy of a dataset, computed once per dataset. */
class Resampled(val sampleRate: Double, val x: DoubleArray, val y: DoubleArray, val z: DoubleArray, val magnitude: DoubleArray) {
    val size: Int get() = x.size

    fun channel(c: Channel): DoubleArray = when (c) {
        Channel.X -> x
        Channel.Y -> y
        Channel.Z -> z
        Channel.MAGNITUDE, Channel.TOTAL -> magnitude
    }

    companion object {
        fun from(s: TimeSeries): Resampled {
            val fs = Resampler.estimateSampleRate(s.time)
            val x = Resampler.resample(s.time, s.x, fs)
            val y = Resampler.resample(s.time, s.y, fs)
            val z = Resampler.resample(s.time, s.z, fs)
            val m = DoubleArray(x.size) { Math.sqrt(x[it] * x[it] + y[it] * y[it] + z[it] * z[it]) }
            return Resampled(fs, x, y, z, m)
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
) {
    /** Welch segments times smoothing bins: what the random error depends on. */
    val effectiveAverages: Int get() = raw.segments * maxOf(1, smoothingBinsApplied)
    var smoothingBinsApplied: Int = 1
        internal set

    /** One-sigma random error of the PSD estimate in dB: 10*log10(e) / sqrt(n). */
    val randomErrorDb: Double get() = if (effectiveAverages > 0) 4.343 / Math.sqrt(effectiveAverages.toDouble()) else Double.NaN
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
        return Spectrum(px.freq, DoubleArray(px.size) { px.power[it] + py.power[it] + pz.power[it] }, px.segments)
    }

    fun finish(meta: DatasetMeta, raw: Spectrum, sampleRate: Double, s: AnalysisSettings): SpectrumResult {
        val maxF = if (s.maxFreq > 0) minOf(s.maxFreq, raw.maxFreq) else raw.maxFreq
        val restricted = raw.restrictTo(s.minFreq, maxF).smoothed(s.smoothingBins)
        val refScale = if (s.normalization == Normalization.REFERENCE_BAND)
            s.normalization.referenceFactor(raw, s.refBandLow, s.refBandHigh)
        else
            1.0
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
        return SpectrumResult(meta, raw, normalized, display, peaks, sampleRate, refScale).also { it.smoothingBinsApplied = s.smoothingBins }
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
