package com.kevinushey.quell.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.kevinushey.quell.dsp.Channel
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevinushey.quell.dsp.Spectrogram
import com.kevinushey.quell.dsp.SpectrogramData
import com.kevinushey.quell.ui.ChartExportHost
import com.kevinushey.quell.ui.MainViewModel
import com.kevinushey.quell.ui.chart.SpectrogramChart
import com.kevinushey.quell.ui.components.AnalysisBar
import com.kevinushey.quell.ui.components.AnalysisOptionsSheet
import com.kevinushey.quell.ui.components.ChartCard
import com.kevinushey.quell.ui.components.EmptyState
import com.kevinushey.quell.ui.components.MenuChip
import com.kevinushey.quell.ui.components.QuickChips
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SpectrogramScreen(vm: MainViewModel, exportHost: ChartExportHost) {
    val datasets by vm.datasets.collectAsStateWithLifecycle()
    val settings by vm.analysis.collectAsStateWithLifecycle()
    val appSettings by vm.appSettings.collectAsStateWithLifecycle()
    var showOptions by remember { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var dynamicRange by rememberSaveable { mutableStateOf(60f) }

    DisposableEffect(Unit) {
        exportHost.name = "spectrogram"
        onDispose { exportHost.bounds = null }
    }

    val candidates = datasets.filter { it.visible }.ifEmpty { datasets }
    if (candidates.isEmpty()) {
        EmptyState(Icons.Default.GridOn, "No datasets", "Import or record a run to see how its vibration changes over time.")
        return
    }
    val selected = candidates.firstOrNull { it.id == selectedId } ?: candidates.first()

    // null = computing; a failed Result is terminal and must not look like a spinner forever.
    val data by produceState<Result<SpectrogramData>?>(null, selected.id, selected.trimStart, selected.trimEnd, settings.channel, settings.segmentSize, settings.window) {
        value = null
        val r = vm.resampled(selected)
        if (r == null) {
            value = Result.failure(IllegalStateException("This run could not be analyzed. Adjust its analysis window on the Data tab to exclude any gap."))
            return@produceState
        }
        value = runCatching {
            withContext(Dispatchers.Default) {
                // Shorter segments than the PSD view: time resolution matters more here.
                val seg = (settings.segmentSize / 2).coerceAtLeast(256)
                val d = if (settings.channel == Channel.TOTAL)
                    Spectrogram.computeTotal(r.x, r.y, r.z, r.sampleRate, seg, settings.window)
                else
                    Spectrogram.compute(r.channel(settings.channel), r.sampleRate, seg, settings.window)
                // Recording time, as on the waveform and in the trim dialog, not time since the trim start.
                d.shifted(r.startTime)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        QuickChips(settings = settings, onChange = vm::updateAnalysis, showNormalization = false, showScale = false)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MenuChip(
                label = selected.name,
                options = candidates.map { it.id },
                selected = selected.id,
                onSelect = { selectedId = it },
                optionLabel = { id -> candidates.first { it.id == id }.name },
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Range ${dynamicRange.toInt()} dB", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
            Slider(value = dynamicRange, onValueChange = { dynamicRange = it }, valueRange = 20f..100f, modifier = Modifier.weight(1f))
        }

        ChartCard(legend = emptyList(), exportHost = exportHost, modifier = Modifier.weight(1f).fillMaxWidth(), title = selected.name) {
            val result = data
            val d = result?.getOrNull()
            if (result == null) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (d == null) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        result.exceptionOrNull()?.message ?: "Analysis failed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                val maxF = if (settings.maxFreq > 0) settings.maxFreq else d.freq.last()
                SpectrogramChart(data = d, maxFreq = maxF, dynamicRangeDb = dynamicRange.toDouble())
            }
        }
        AnalysisBar(
            settings = settings,
            sampleRateHint = selected.sampleRate,
            onChange = vm::updateAnalysis,
            onOpenOptions = { showOptions = true },
        )
    }

    if (showOptions) {
        AnalysisOptionsSheet(
            settings = settings,
            sampleRateHint = selected.sampleRate,
            useMph = appSettings.useMph,
            onChange = vm::updateAnalysis,
            onDismiss = { showOptions = false },
        )
    }
}
