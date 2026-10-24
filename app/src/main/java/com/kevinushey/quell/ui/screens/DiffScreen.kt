package com.kevinushey.quell.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevinushey.quell.dsp.DiffResult
import com.kevinushey.quell.dsp.SpectrumDiff
import com.kevinushey.quell.ui.ChartExportHost
import com.kevinushey.quell.ui.MainViewModel
import com.kevinushey.quell.ui.SpectrumResult
import com.kevinushey.quell.ui.chart.AxisScale
import com.kevinushey.quell.ui.chart.ChartSeries
import com.kevinushey.quell.ui.chart.LineChart
import com.kevinushey.quell.ui.chart.PointMarker
import com.kevinushey.quell.ui.components.AnalysisBar
import com.kevinushey.quell.ui.components.AnalysisOptionsSheet
import com.kevinushey.quell.ui.components.ChartCard
import com.kevinushey.quell.ui.components.EmptyState
import com.kevinushey.quell.ui.components.LegendItem
import com.kevinushey.quell.ui.components.MenuChip
import com.kevinushey.quell.ui.components.QuickChips
import com.kevinushey.quell.ui.formatHz
import com.kevinushey.quell.ui.formatSigned
import com.kevinushey.quell.ui.theme.seriesColor
import java.util.Locale

private class Comparison(val other: SpectrumResult, val diff: DiffResult)

@Composable
fun DiffScreen(vm: MainViewModel, exportHost: ChartExportHost) {
    val state by vm.spectra.collectAsStateWithLifecycle()
    val settings by vm.analysis.collectAsStateWithLifecycle()
    val appSettings by vm.appSettings.collectAsStateWithLifecycle()
    var showOptions by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        exportHost.name = "diff"
        onDispose { exportHost.bounds = null }
    }

    // A run that could not be reference-band normalized is not on the same scale; keep it out.
    val results = state.results.filter { !it.referenceBandMissing }
    val excluded = state.results.filter { it.referenceBandMissing }
    if (showOptions) {
        AnalysisOptionsSheet(
            settings = settings,
            sampleRateHint = state.results.firstOrNull()?.sampleRate,
            useMph = appSettings.useMph,
            onChange = vm::updateAnalysis,
            onDismiss = { showOptions = false },
        )
    }

    if (results.size < 2) {
        // Keep the normalization and band controls reachable: they are usually how the user gets out of this state.
        Column(Modifier.fillMaxSize()) {
            QuickChips(settings = settings, onChange = vm::updateAnalysis, showScale = false)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                EmptyState(
                    icon = Icons.Default.Compare,
                    title = "Need two or more comparable datasets",
                    message = "Enable at least two datasets on the Datasets tab. The diff view subtracts a reference run from each of the others, in dB, so a flat line at zero means identical spectra." +
                        if (excluded.isEmpty()) "" else "\n\nNot comparable (reference band not covered): ${excluded.joinToString { it.meta.name }}. Change the normalization or the reference band below.",
                )
            }
            AnalysisBar(
                settings = settings,
                sampleRateHint = state.results.firstOrNull()?.sampleRate,
                onChange = vm::updateAnalysis,
                onOpenOptions = { showOptions = true },
            )
        }
        return
    }

    val reference = results.firstOrNull { it.meta.id == settings.referenceId } ?: results.first()
    val unit = if (settings.normalization.isDecibelDomain) "z" else "dB"
    val comparisons = remember(results, reference.meta.id, settings.normalization) {
        results.filter { it.meta.id != reference.meta.id }.map { other ->
            Comparison(other, SpectrumDiff.compute(reference.normalized, other.normalized, settings.normalization.isDecibelDomain))
        }
    }

    Column(Modifier.fillMaxSize()) {
        QuickChips(settings = settings, onChange = vm::updateAnalysis, showScale = false)
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Reference:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
            MenuChip(
                label = reference.meta.name,
                options = results.map { it.meta.id },
                selected = reference.meta.id,
                onSelect = { vm.setReference(it) },
                optionLabel = { id -> results.first { it.meta.id == id }.meta.name },
            )
        }
        if (excluded.isNotEmpty()) {
            Text(
                "Not comparable (reference band not covered): ${excluded.joinToString { it.meta.name }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        val series = comparisons.map { c ->
            ChartSeries(c.other.meta.id, c.other.meta.name, seriesColor(c.other.meta.colorIndex), c.diff.freq, c.diff.delta)
        }
        val markers = comparisons.flatMap { c ->
            val color = seriesColor(c.other.meta.colorIndex)
            c.diff.topBands.take(3).map { b -> PointMarker(b.freq, b.delta, formatHz(b.freq), color) }
        }

        ChartCard(
            legend = comparisons.map { LegendItem("${it.other.meta.name} - ${reference.meta.name}", seriesColor(it.other.meta.colorIndex)) },
            exportHost = exportHost,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LineChart(
                series = series,
                xLabel = "Frequency (Hz)",
                yLabel = "Difference vs reference ($unit)",
                xScale = if (settings.logFrequency) AxisScale.LOG else AxisScale.LINEAR,
                pointMarkers = markers,
                showZeroLine = true,
                viewportKey = listOf(settings.channel, settings.normalization, settings.logFrequency, settings.minFreq, settings.maxFreq, reference.meta.id),
                xValueFormatter = { formatHz(it) },
                yValueFormatter = { "%+.1f %s".format(Locale.US, it, unit) },
            )
        }

        AnalysisBar(
            settings = settings,
            sampleRateHint = results.firstOrNull()?.sampleRate,
            onChange = vm::updateAnalysis,
            onOpenOptions = { showOptions = true },
        )
        LazyColumn(Modifier.fillMaxWidth().weight(0.7f), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(comparisons, key = { it.other.meta.id }) { c -> ComparisonCard(c, reference.meta.name, unit) }
        }
    }
}

@Composable
private fun ComparisonCard(c: Comparison, referenceName: String, unit: String) {
    val color = seriesColor(c.other.meta.colorIndex)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(c.other.meta.name, style = MaterialTheme.typography.titleSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (c.diff.freq.isEmpty()) {
                Text(
                    "No frequency range shared with $referenceName in the displayed band.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }
            Text(
                "vs $referenceName  |  RMS %.1f %s  |  mean |d| %.1f %s  |  r = %.2f".format(Locale.US, c.diff.rms, unit, c.diff.meanAbs, unit, c.diff.correlation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (c.diff.topBands.isNotEmpty()) {
                Text("Largest divergences", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                for (b in c.diff.topBands) {
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                        Text(formatHz(b.freq), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.3f))
                        Text("${formatSigned(b.delta)} $unit", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.3f))
                        Text(
                            if (b.delta > 0) "this run higher" else "reference higher",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(0.4f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
