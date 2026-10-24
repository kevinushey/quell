package com.kevinushey.quell.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kevinushey.quell.data.AnalysisSettings
import com.kevinushey.quell.dsp.WindowType
import java.util.Locale

/**
 * Sits under the chart: the spectral-estimation knobs people tweak most,
 * plus a gear for everything else. The gear stays pinned on the right even
 * when the chips need to scroll.
 */
@Composable
fun AnalysisBar(
    settings: AnalysisSettings,
    sampleRateHint: Double?,
    onChange: ((AnalysisSettings) -> AnalysisSettings) -> Unit,
    onOpenOptions: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MenuChip(
                label = "${settings.segmentSize} pt",
                options = AnalysisSettings.SEGMENT_SIZES,
                selected = settings.segmentSize,
                onSelect = { v -> onChange { it.copy(segmentSize = v) } },
                optionLabel = { "$it points" },
                optionDescription = sampleRateHint?.let { fs -> { n: Int -> "%.2f Hz bins".format(Locale.US, fs / n) } },
            )
            MenuChip(
                label = settings.window.label,
                options = WindowType.entries,
                selected = settings.window,
                onSelect = { v -> onChange { it.copy(window = v) } },
                optionLabel = { it.label },
            )
            MenuChip(
                label = if (settings.smoothingBins <= 1) "Smooth: off" else "Smooth: ${settings.smoothingBins}",
                options = AnalysisSettings.SMOOTHING,
                selected = settings.smoothingBins,
                onSelect = { v -> onChange { it.copy(smoothingBins = v) } },
                optionLabel = { if (it <= 1) "Off" else "$it bins" },
            )
        }
        IconButton(onClick = onOpenOptions) {
            Icon(Icons.Default.Settings, contentDescription = "All analysis options")
        }
    }
}
