package com.kevinushey.quell.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevinushey.quell.ui.AnalysisEngine
import com.kevinushey.quell.ui.ChartExportHost
import com.kevinushey.quell.ui.MainViewModel
import com.kevinushey.quell.ui.chart.AxisScale
import com.kevinushey.quell.ui.chart.ChartSeries
import com.kevinushey.quell.ui.chart.LineChart
import com.kevinushey.quell.ui.chart.PointMarker
import com.kevinushey.quell.ui.chart.VerticalMarker
import com.kevinushey.quell.ui.components.AnalysisBar
import com.kevinushey.quell.ui.components.AnalysisOptionsSheet
import com.kevinushey.quell.ui.components.ChartCard
import com.kevinushey.quell.ui.components.EmptyState
import com.kevinushey.quell.ui.components.LegendItem
import com.kevinushey.quell.ui.components.QuickChips
import com.kevinushey.quell.ui.components.wheelMarkerFrequencies
import com.kevinushey.quell.ui.formatHz
import com.kevinushey.quell.ui.theme.seriesColor
import java.util.Locale

@Composable
fun SpectrumScreen(vm: MainViewModel, exportHost: ChartExportHost, onImport: () -> Unit) {
    val datasets by vm.datasets.collectAsStateWithLifecycle()
    val state by vm.spectra.collectAsStateWithLifecycle()
    val settings by vm.analysis.collectAsStateWithLifecycle()
    val appSettings by vm.appSettings.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    var showOptions by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        exportHost.name = "spectrum"
        onDispose { exportHost.bounds = null }
    }

    if (ready && datasets.isEmpty()) {
        EmptyState(
            icon = Icons.AutoMirrored.Filled.ShowChart,
            title = "No datasets yet",
            message = "Import phyphox \"Acceleration\" exports (.zip) or CSV files with time and x/y/z columns, or record a run with this phone's accelerometer.",
            action = { Button(onClick = onImport) { Text("Import files") } },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        QuickChips(settings = settings, onChange = vm::updateAnalysis)

        val results = state.results
        val series = remember(results) {
            results.map { r -> ChartSeries(r.meta.id, r.meta.name, seriesColor(r.meta.colorIndex), r.normalized.freq, r.display) }
        }
        val peaks = remember(results) {
            results.flatMap { r ->
                val c = seriesColor(r.meta.colorIndex)
                r.peaks.map { p -> PointMarker(p.freq, p.value, formatHz(p.freq), c) }
            }
        }
        val markerColor = MaterialTheme.colorScheme.tertiary
        val markers = if (settings.wheelMarkers.enabled)
            wheelMarkerFrequencies(settings.wheelMarkers).map { (n, f) -> VerticalMarker(f, "${n}x", markerColor) }
        else
            emptyList()
        val legend = results.map { LegendItem(it.meta.name, seriesColor(it.meta.colorIndex)) }
        val unit = AnalysisEngine.valueUnit(settings)

        ChartCard(
            legend = legend,
            exportHost = exportHost,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            footer = {
                val visibleCount = datasets.count { it.visible }
                val first = results.firstOrNull()
                val info = buildString {
                    append("$visibleCount of ${datasets.size} shown")
                    if (first != null) {
                        append("  |  bin ")
                        append("%.2f".format(Locale.US, first.sampleRate / settings.segmentSize))
                        append(" Hz  |  ")
                        // Fewest averages across the visible runs bounds the comparison's uncertainty.
                        val worst = results.minByOrNull { it.effectiveAverages } ?: first
                        append("${worst.effectiveAverages} avg, +/-")
                        append("%.1f".format(Locale.US, worst.randomErrorDb))
                        append(" dB  |  ${settings.window.label} ${(settings.overlap * 100).toInt()}%")
                    }
                }
                Column {
                    Text(
                        info,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val uncovered = results.filter { it.referenceBandMissing }
                    if (uncovered.isNotEmpty()) {
                        Text(
                            "Reference band %.0f-%.0f Hz not covered by: %s (shown unnormalized)".format(
                                Locale.US,
                                settings.refBandLow,
                                settings.refBandHigh,
                                uncovered.joinToString { it.meta.name },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 4.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
        ) {
            if (series.isEmpty()) {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.ShowChart,
                    title = if (state.computing) "Computing..." else "Nothing to show",
                    message = if (state.computing) "" else "Enable at least one dataset on the Datasets tab.",
                )
            } else {
                LineChart(
                    series = series,
                    xLabel = "Frequency (Hz)",
                    yLabel = AnalysisEngine.yAxisLabel(settings),
                    xScale = if (settings.logFrequency) AxisScale.LOG else AxisScale.LINEAR,
                    verticalMarkers = markers,
                    pointMarkers = peaks,
                    viewportKey = listOf(settings.channel, settings.normalization, settings.decibels, settings.logFrequency, settings.minFreq, settings.maxFreq),
                    xValueFormatter = { formatHz(it) },
                    yValueFormatter = { v -> if (unit.isEmpty()) com.kevinushey.quell.ui.chart.Axis.formatValue(v) else "%.1f %s".format(Locale.US, v, unit) },
                )
            }
        }

        if (settings.showPeaks && results.isNotEmpty()) {
            PeakSummary(results.map { r -> Triple(r.meta.name, seriesColor(r.meta.colorIndex), r.peaks.map { formatHz(it.freq) }) })
        }
        AnalysisBar(
            settings = settings,
            sampleRateHint = results.firstOrNull()?.sampleRate,
            onChange = vm::updateAnalysis,
            onOpenOptions = { showOptions = true },
        )
    }

    if (showOptions) {
        AnalysisOptionsSheet(
            settings = settings,
            sampleRateHint = state.results.firstOrNull()?.sampleRate,
            useMph = appSettings.useMph,
            onChange = vm::updateAnalysis,
            onDismiss = { showOptions = false },
        )
    }
}

@Composable
private fun PeakSummary(rows: List<Triple<String, androidx.compose.ui.graphics.Color, List<String>>>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        for ((name, color, peaks) in rows.take(6)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    modifier = Modifier.weight(0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (peaks.isEmpty()) "--" else "peaks: " + peaks.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
