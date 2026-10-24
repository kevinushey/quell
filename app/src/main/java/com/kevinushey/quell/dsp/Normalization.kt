package com.kevinushey.quell.dsp

import kotlin.math.log10
import kotlin.math.sqrt

enum class Normalization(val label: String, val description: String) {
    NONE("None", "Absolute PSD (m/s^2)^2/Hz. Use this to see whether a damper lowered the level"),
    REFERENCE_BAND("Reference band", "Scale by mean power in a band the damper does not affect, to correct for different road input between runs"),
    TOTAL_POWER("Total power", "Scale so the spectrum integrates to 1"),
    MEDIAN("Median", "Scale by the median bin; highlights peaks over the noise floor"),
    PEAK("Peak", "Scale so the strongest bin equals 1 (0 dB). Hides level changes; for shape comparison only"),
    ZSCORE_DB("Z-score (dB)", "Standardize dB values to zero mean, unit variance");

    val isDecibelDomain: Boolean get() = this == ZSCORE_DB

    /** Normalizations that keep levels comparable between runs; offered on the quick chip. */
    val recommendedForDampers: Boolean get() = this == NONE || this == REFERENCE_BAND || this == TOTAL_POWER || this == MEDIAN

    /**
     * Scale factor for REFERENCE_BAND, computed on the full-range spectrum so
     * the band need not be on screen. NaN when the recording does not cover
     * the band, so callers can flag the run instead of silently comparing it.
     */
    fun referenceFactor(full: Spectrum, lo: Double, hi: Double): Double {
        if (!full.covers(lo, hi))
            return Double.NaN
        val mean = full.meanPower(lo, hi)
        return if (mean.isNaN() || mean <= 0) Double.NaN else 1.0 / mean
    }

    /** Applies the normalization to the (already frequency-restricted) spectrum. */
    fun apply(s: Spectrum): Spectrum {
        if (s.size == 0)
            return s

        return when (this) {
            NONE, REFERENCE_BAND -> s
            PEAK -> {
                val max = s.power.max()
                if (max > 0) s.map { _, p -> p / max } else s
            }
            TOTAL_POWER -> {
                val total = s.power.sum() * s.binWidth
                if (total > 0) s.map { _, p -> p / total } else s
            }
            MEDIAN -> {
                val median = s.power.sorted()[s.size / 2]
                if (median > 0) s.map { _, p -> p / median } else s
            }
            ZSCORE_DB -> {
                val db = DoubleArray(s.size) { toDb(s.power[it]) }
                val mean = db.average()
                val sd = sqrt(db.sumOf { (it - mean) * (it - mean) } / db.size)
                val z = if (sd > 0) DoubleArray(s.size) { (db[it] - mean) / sd } else DoubleArray(s.size)
                Spectrum(s.freq, z, s.segments, s.windowLength, s.binWidth, s.physicalMin, s.physicalMax)
            }
        }
    }

    companion object {
        const val DB_FLOOR = 1e-12

        fun toDb(p: Double): Double = 10.0 * log10(p.coerceAtLeast(DB_FLOOR))
    }
}
