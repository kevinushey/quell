package com.kevinushey.quell.data

import com.kevinushey.quell.dsp.Channel
import com.kevinushey.quell.dsp.Normalization
import com.kevinushey.quell.dsp.WindowType
import kotlinx.serialization.Serializable

@Serializable
data class AnalysisSettings(
    val channel: Channel = Channel.TOTAL,
    val normalization: Normalization = Normalization.NONE,
    val segmentSize: Int = 1024,
    val window: WindowType = WindowType.HANN,
    val overlap: Double = 0.5,
    val decibels: Boolean = true,
    val logFrequency: Boolean = false,
    val minFreq: Double = 0.5,
    /** 0 means "up to Nyquist". */
    val maxFreq: Double = 0.0,
    val smoothingBins: Int = 1,
    val showPeaks: Boolean = true,
    /** Shade +/- one sigma of random error around each spectrum (dB views only). */
    val showUncertainty: Boolean = false,
    val peakCount: Int = 3,
    val referenceId: String? = null,
    val wheelMarkers: WheelMarkers = WheelMarkers(),
    /** Band used by the reference-band normalization; should be away from the mode under test. */
    val refBandLow: Double = 60.0,
    val refBandHigh: Double = 150.0,
    /** Band around the mode the damper targets, used by the Damper tab. */
    val targetBandLow: Double = 20.0,
    val targetBandHigh: Double = 30.0,
    /** Tap-test impulse detection threshold in m/s^2 above the resting level. */
    val tapThreshold: Double = 2.0,
) {
    companion object {
        val SEGMENT_SIZES = listOf(256, 512, 1024, 2048, 4096, 8192)
        val SMOOTHING = listOf(1, 3, 5, 9, 15)
    }
}

/**
 * Vertical markers at the wheel rotation frequency and its harmonics, derived
 * from vehicle speed and tire size. Handy when chasing wheel-balance vibration.
 */
@Serializable
data class WheelMarkers(
    val enabled: Boolean = false,
    val speedKph: Double = 100.0,
    val tireWidthMm: Double = 255.0,
    val aspectRatio: Double = 45.0,
    val rimInches: Double = 20.0,
    val harmonics: Int = 3,
) {
    val circumferenceM: Double
        get() {
            val sidewall = tireWidthMm * aspectRatio / 100.0
            val diameterMm = rimInches * 25.4 + 2 * sidewall
            return Math.PI * diameterMm / 1000.0
        }

    val rotationHz: Double
        get() = (speedKph / 3.6) / circumferenceM
}

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val useMph: Boolean = false,
    /** Folder (document tree uri) the dataset store is mirrored into, and whether to. */
    val syncTreeUri: String? = null,
    val syncEnabled: Boolean = false,
)
