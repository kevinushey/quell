package com.kevinushey.quell.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kevinushey.quell.data.AnalysisSettings
import com.kevinushey.quell.data.WheelMarkers
import com.kevinushey.quell.dsp.Normalization
import com.kevinushey.quell.dsp.WindowType
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisOptionsSheet(
    settings: AnalysisSettings,
    sampleRateHint: Double?,
    useMph: Boolean,
    onChange: ((AnalysisSettings) -> AnalysisSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Analysis options", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { onChange { AnalysisSettings(referenceId = it.referenceId) } }) { Text("Reset") }
            }

            SectionLabel("FFT segment size")
            val resolutionHint = sampleRateHint?.let { fs -> " (%.2f Hz bins at %.0f Hz)".format(Locale.US, fs / settings.segmentSize, fs) } ?: ""
            Text(
                "Larger segments give finer frequency resolution but average fewer segments$resolutionHint.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChipRow(AnalysisSettings.SEGMENT_SIZES, settings.segmentSize, { it.toString() }) { v -> onChange { it.copy(segmentSize = v) } }

            SectionLabel("Window")
            ChipRow(WindowType.entries, settings.window, { it.label }) { v -> onChange { it.copy(window = v) } }

            SectionLabel("Overlap: ${(settings.overlap * 100).toInt()}%")
            Slider(
                value = settings.overlap.toFloat(),
                onValueChange = { v -> onChange { it.copy(overlap = (v * 20).roundToInt() / 20.0) } },
                valueRange = 0f..0.9f,
                steps = 17,
            )

            SectionLabel("Smoothing (bins)")
            ChipRow(AnalysisSettings.SMOOTHING, settings.smoothingBins, { if (it == 1) "Off" else it.toString() }) { v -> onChange { it.copy(smoothingBins = v) } }

            SectionLabel("Frequency range (Hz)")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(
                    label = "Min",
                    value = settings.minFreq,
                    modifier = Modifier.weight(1f),
                ) { v -> onChange { it.copy(minFreq = v.coerceAtLeast(0.0)) } }
                NumberField(
                    label = "Max (0 = Nyquist)",
                    value = settings.maxFreq,
                    modifier = Modifier.weight(1f),
                ) { v -> onChange { it.copy(maxFreq = v.coerceAtLeast(0.0)) } }
            }

            SectionLabel("Normalization")
            ChipRow(Normalization.entries, settings.normalization, { it.label }) { v -> onChange { it.copy(normalization = v) } }
            Text(settings.normalization.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (settings.normalization == Normalization.REFERENCE_BAND) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField("Ref. band low (Hz)", settings.refBandLow, Modifier.weight(1f)) { v -> onChange { it.copy(refBandLow = v) } }
                    NumberField("Ref. band high (Hz)", settings.refBandHigh, Modifier.weight(1f)) { v -> onChange { it.copy(refBandHigh = v) } }
                }
            }

            SectionLabel("Damper target band (Hz)")
            Text(
                "The mode the damper is tuned to. Used by the Damper tab for band RMS, damping and split-peak detection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField("Low", settings.targetBandLow, Modifier.weight(1f)) { v -> onChange { it.copy(targetBandLow = v) } }
                NumberField("High", settings.targetBandHigh, Modifier.weight(1f)) { v -> onChange { it.copy(targetBandHigh = v) } }
            }

            SectionLabel("Tap test impulse threshold (m/s^2)")
            NumberField("Threshold", settings.tapThreshold, Modifier.fillMaxWidth()) { v -> onChange { it.copy(tapThreshold = v.coerceAtLeast(0.1)) } }

            SectionLabel("Peak labels: ${settings.peakCount} per run")
            Slider(
                value = settings.peakCount.toFloat(),
                onValueChange = { v -> onChange { it.copy(peakCount = v.toInt()) } },
                valueRange = 1f..8f,
                steps = 6,
            )

            HorizontalDivider()

            val wm = settings.wheelMarkers
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Wheel order markers", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Marks the wheel rotation frequency and harmonics for a given speed and tire size. Vibration that tracks these lines points to wheel balance or tire issues.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = wm.enabled, onCheckedChange = { on -> onChange { it.copy(wheelMarkers = it.wheelMarkers.copy(enabled = on)) } })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Uncertainty band", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Shades +/- one sigma of random error around each spectrum in dB views. Differences inside the shading are not measured; longer runs narrow it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.showUncertainty, onCheckedChange = { on -> onChange { it.copy(showUncertainty = on) } })
            }
            if (wm.enabled) {
                val speedLabel = if (useMph) "Speed (mph)" else "Speed (km/h)"
                val speedValue = if (useMph) wm.speedKph / 1.609344 else wm.speedKph
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(speedLabel, speedValue, Modifier.weight(1f)) { v ->
                        onChange { it.copy(wheelMarkers = it.wheelMarkers.copy(speedKph = if (useMph) v * 1.609344 else v)) }
                    }
                    NumberField("Harmonics", wm.harmonics.toDouble(), Modifier.weight(1f)) { v ->
                        onChange { it.copy(wheelMarkers = it.wheelMarkers.copy(harmonics = v.toInt().coerceIn(1, 8))) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField("Width (mm)", wm.tireWidthMm, Modifier.weight(1f)) { v -> onChange { it.copy(wheelMarkers = it.wheelMarkers.copy(tireWidthMm = v)) } }
                    NumberField("Aspect", wm.aspectRatio, Modifier.weight(1f)) { v -> onChange { it.copy(wheelMarkers = it.wheelMarkers.copy(aspectRatio = v)) } }
                    NumberField("Rim (in)", wm.rimInches, Modifier.weight(1f)) { v -> onChange { it.copy(wheelMarkers = it.wheelMarkers.copy(rimInches = v)) } }
                }
                Text(
                    "Tire %.0f/%.0fR%.0f: circumference %.3f m, 1st order %.2f Hz".format(
                        Locale.US, wm.tireWidthMm, wm.aspectRatio, wm.rimInches, wm.circumferenceM, wm.rotationHz
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (o in options)
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) })
    }
}

@Composable
private fun NumberField(label: String, value: Double, modifier: Modifier = Modifier, onCommit: (Double) -> Unit) {
    // Keep the raw text local so partially typed numbers ("0.") are not rewritten under the cursor.
    var text by remember { mutableStateOf(formatNumber(value)) }
    LaunchedEffect(value) {
        if (parseDecimal(text) != value)
            text = formatNumber(value)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            // Infinity/NaN would make the settings unserializable.
            parseDecimal(t)?.takeIf { it.isFinite() }?.let(onCommit)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/** Decimal keyboards offer ',' in many locales; accept it as the separator too. */
internal fun parseDecimal(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

private fun formatNumber(v: Double): String =
    if (v == Math.floor(v) && Math.abs(v) < 1e9) v.toLong().toString() else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

internal fun wheelMarkerFrequencies(wm: WheelMarkers): List<Pair<Int, Double>> =
    (1..wm.harmonics).map { it to it * wm.rotationHz }
