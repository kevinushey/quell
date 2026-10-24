package com.kevinushey.quell.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kevinushey.quell.data.AnalysisSettings
import com.kevinushey.quell.dsp.Channel
import com.kevinushey.quell.dsp.Normalization

/** One-tap access to the settings people change most while comparing runs. */
@Composable
fun QuickChips(
    settings: AnalysisSettings,
    onChange: ((AnalysisSettings) -> AnalysisSettings) -> Unit,
    showNormalization: Boolean = true,
    showScale: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MenuChip(
            label = settings.channel.short,
            options = Channel.entries,
            selected = settings.channel,
            onSelect = { c -> onChange { it.copy(channel = c) } },
            optionLabel = { it.label },
            optionDescription = { it.description },
        )
        if (showNormalization) {
            MenuChip(
                label = "Norm: ${settings.normalization.label}",
                options = Normalization.entries.filter { it.recommendedForDampers || it == settings.normalization },
                selected = settings.normalization,
                onSelect = { n -> onChange { it.copy(normalization = n) } },
                optionLabel = { it.label },
                optionDescription = { it.description },
            )
        }
        if (showScale) {
            FilterChip(
                selected = settings.decibels,
                enabled = !settings.normalization.isDecibelDomain,
                onClick = { onChange { it.copy(decibels = !it.decibels) } },
                label = { Text("dB") },
            )
            FilterChip(
                selected = settings.logFrequency,
                onClick = { onChange { it.copy(logFrequency = !it.logFrequency) } },
                label = { Text("log f") },
            )
            FilterChip(
                selected = settings.showPeaks,
                onClick = { onChange { it.copy(showPeaks = !it.showPeaks) } },
                label = { Text("Peaks") },
            )
        }
    }
}
