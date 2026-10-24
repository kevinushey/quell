package com.kevinushey.quell.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevinushey.quell.data.AnalysisSettings
import com.kevinushey.quell.data.DatasetMeta
import com.kevinushey.quell.data.ExperimentKind
import com.kevinushey.quell.dsp.Modal
import com.kevinushey.quell.dsp.ModalPeak
import com.kevinushey.quell.dsp.Normalization
import com.kevinushey.quell.dsp.Spectrum
import com.kevinushey.quell.dsp.SplitPeak
import com.kevinushey.quell.dsp.Tuning
import com.kevinushey.quell.dsp.TuningDiagnosis
import com.kevinushey.quell.dsp.DampingVerdict
import com.kevinushey.quell.ui.ChartExportHost
import com.kevinushey.quell.ui.MainViewModel
import com.kevinushey.quell.ui.SpectrumResult
import com.kevinushey.quell.ui.TapSummary
import com.kevinushey.quell.ui.chart.AxisScale
import com.kevinushey.quell.ui.chart.ChartSeries
import com.kevinushey.quell.ui.chart.LineChart
import com.kevinushey.quell.ui.chart.VerticalMarker
import com.kevinushey.quell.ui.components.AnalysisBar
import com.kevinushey.quell.ui.components.AnalysisOptionsSheet
import com.kevinushey.quell.ui.components.ChartCard
import com.kevinushey.quell.ui.components.EmptyState
import com.kevinushey.quell.ui.components.LegendItem
import com.kevinushey.quell.ui.components.MenuChip
import com.kevinushey.quell.ui.formatHz
import com.kevinushey.quell.ui.theme.seriesColor
import java.util.Locale

/** Per-run numbers for a drive test inside the target band. */
private class DriveRow(
    val result: SpectrumResult,
    /** Absolute PSD scaled by the reference-band factor when that normalization is active. */
    val spectrum: Spectrum,
    val bandRms: Double,
    val peak: ModalPeak?,
    val split: SplitPeak?,
)

@Composable
fun DamperScreen(vm: MainViewModel, exportHost: ChartExportHost) {
    val datasets by vm.datasets.collectAsStateWithLifecycle()
    val state by vm.spectra.collectAsStateWithLifecycle()
    val settings by vm.analysis.collectAsStateWithLifecycle()
    val appSettings by vm.appSettings.collectAsStateWithLifecycle()
    var showOptions by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        exportHost.name = "damper"
        onDispose { exportHost.bounds = null }
    }

    val lo = settings.targetBandLow
    val hi = settings.targetBandHigh
    val driveResults = state.results.filter { it.meta.kind == ExperimentKind.DRIVE }
    val tapMetas = datasets.filter { it.visible && it.kind == ExperimentKind.TAP }

    if (driveResults.isEmpty() && tapMetas.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Tune,
            title = "No visible runs",
            message = "Enable drive runs or tap-test recordings on the Data tab. Set a dataset's experiment type from its menu.",
        )
        return
    }

    val rows = remember(driveResults, lo, hi) {
        driveResults.map { r ->
            val s = if (r.referenceScale != 1.0) r.raw.map { _, p -> p * r.referenceScale } else r.raw
            // NaN marks a run whose sample rate does not reach the band; it is shown but not compared.
            val rms = if (r.referenceBandMissing) Double.NaN else s.bandRms(lo, hi)
            DriveRow(r, s, rms, Modal.dominantPeak(s, lo, hi), Modal.splitPeak(s, lo, hi))
        }
    }
    val driveRef = rows.firstOrNull { it.result.meta.id == settings.referenceId } ?: rows.firstOrNull()

    val tapSummaries by produceState<Map<String, TapSummary>>(emptyMap(), tapMetas.map { Triple(it.id, it.trimStart, it.trimEnd) }, settings.tapThreshold) {
        val out = LinkedHashMap<String, TapSummary>()
        for (m in tapMetas)
            vm.tapSummary(m, settings.tapThreshold)?.let { out[m.id] = it }
        value = out
    }
    val tapRef = tapMetas.firstOrNull { it.id == settings.referenceId } ?: tapMetas.firstOrNull()

    val unit = if (settings.normalization == Normalization.REFERENCE_BAND) "rel." else "m/s^2"
    val bandLabel = "%.1f-%.1f Hz".format(Locale.US, lo, hi)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Only runs this tab actually compares can be the reference, and the label
            // names the reference really in use, which may be a fallback.
            val refCandidates = rows.map { it.result.meta } + tapMetas
            val refLabel = listOfNotNull(driveRef?.result?.meta?.name, tapRef?.name).distinct().joinToString(" / ")
            MenuChip(
                label = "Ref: ${refLabel.ifEmpty { "none" }}",
                options = refCandidates.map { it.id },
                selected = settings.referenceId ?: "",
                onSelect = { vm.setReference(it) },
                optionLabel = { id -> refCandidates.first { it.id == id }.name },
            )
            TextButton(onClick = {
                // Centre the band on the reference run's tallest peak in the displayed range.
                val ref = driveRef?.result ?: return@TextButton
                val peakIdx = ref.display.indices.maxByOrNull { ref.display[it] } ?: return@TextButton
                val f = ref.normalized.freq[peakIdx]
                val half = maxOf(2.0, 0.15 * f)
                vm.updateAnalysis { it.copy(targetBandLow = (f - half).coerceAtLeast(0.0), targetBandHigh = f + half) }
            }) { Text("Auto band") }
        }

        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (rows.isNotEmpty()) {
                item {
                val chartSeries = rows.map { r ->
                    val span = hi - lo
                    val sub = r.spectrum.restrictTo((lo - span).coerceAtLeast(0.0), hi + span)
                    ChartSeries(r.result.meta.id, r.result.meta.name, seriesColor(r.result.meta.colorIndex), sub.freq, DoubleArray(sub.size) { Normalization.toDb(sub.power[it]) })
                }
                val edgeColor = MaterialTheme.colorScheme.tertiary
                ChartCard(
                    legend = rows.map { LegendItem(it.result.meta.name, seriesColor(it.result.meta.colorIndex)) },
                    exportHost = exportHost,
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    title = "Target band $bandLabel",
                ) {
                    LineChart(
                        series = chartSeries,
                        xLabel = "Frequency (Hz)",
                        yLabel = if (unit == "rel.") "PSD (dB re ref. band)" else "PSD (dB)",
                        xScale = AxisScale.LINEAR,
                        verticalMarkers = listOf(VerticalMarker(lo, "", edgeColor), VerticalMarker(hi, "", edgeColor)),
                        viewportKey = listOf(lo, hi, settings.channel, settings.normalization),
                        xValueFormatter = { formatHz(it) },
                        yValueFormatter = { "%.1f dB".format(Locale.US, it) },
                    )
                }
                }
            }
            if (rows.isNotEmpty()) {
                item { SectionTitle("Drive runs: band RMS in $bandLabel") }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            HeaderRow(listOf("Run", "RMS ($unit)", "vs ref", "Peak", "zeta"))
                            for (r in rows)
                                DriveTableRow(r, driveRef, settings)
                        }
                    }
                }
                // Diagnoses need both runs on the same scale and covering the band (bandRms is NaN otherwise).
                if (driveRef != null && driveRef.peak != null && !driveRef.bandRms.isNaN() && rows.size > 1) {
                    item { SectionTitle("Tuning diagnosis vs ${driveRef.result.meta.name}") }
                    for (r in rows) {
                        if (r.result.meta.id == driveRef.result.meta.id || r.bandRms.isNaN())
                            continue
                        val d = Tuning.diagnose(driveRef.peak, r.spectrum, lo, hi, r.split) ?: continue
                        item { DiagnosisCard(r, d) }
                    }
                }
                val splits = rows.filter { it.split != null }
                if (splits.isNotEmpty()) {
                    item { SectionTitle("Split peaks (absorber signature)") }
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (r in splits) {
                                    val sp = r.split!!
                                    val mass = r.result.meta.damperMassGrams
                                    Text(r.result.meta.name, style = MaterialTheme.typography.labelLarge, color = seriesColor(r.result.meta.colorIndex), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "peaks %.2f / %.2f Hz, notch %.2f Hz  ->  fn %.2f Hz, mass ratio %.3f%s".format(
                                            Locale.US, sp.lower.freq, sp.upper.freq, sp.notchFreq, sp.impliedNaturalFreq, sp.impliedMassRatio,
                                            if (mass != null && sp.impliedMassRatio > 0) ", modal mass ~%.0f g".format(Locale.US, mass / sp.impliedMassRatio) else "",
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Text(
                                    "Mass ratio from the undamped-absorber relation f1^2 + f2^2 = fn^2 (2 + mu). Treat it as an order of magnitude.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                val withMass = rows.filter { it.result.meta.damperMassGrams != null && !it.bandRms.isNaN() }.sortedBy { it.result.meta.damperMassGrams }
                if (withMass.size >= 2) {
                    item { SectionTitle("Band RMS vs damper mass") }
                    item {
                        Card(Modifier.fillMaxWidth().height(200.dp)) {
                            val xs = DoubleArray(withMass.size) { withMass[it].result.meta.damperMassGrams!! }
                            val ys = DoubleArray(withMass.size) { withMass[it].bandRms }
                            LineChart(
                                series = listOf(ChartSeries("mass", "Band RMS", MaterialTheme.colorScheme.primary, xs, ys, strokeWidthDp = 2.5f)),
                                xLabel = "Damper mass (g)",
                                yLabel = "RMS ($unit)",
                                pointMarkers = withMass.map { com.kevinushey.quell.ui.chart.PointMarker(it.result.meta.damperMassGrams!!, it.bandRms, "%.0f g".format(Locale.US, it.result.meta.damperMassGrams!!), seriesColor(it.result.meta.colorIndex)) },
                                viewportKey = withMass.map { it.result.meta.id },
                                xValueFormatter = { "%.1f g".format(Locale.US, it) },
                                yValueFormatter = { "%.3f".format(Locale.US, it) },
                            )
                        }
                    }
                }
            }

            if (tapMetas.isNotEmpty()) {
                item { SectionTitle("Tap tests: ring-down") }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            HeaderRow(listOf("Run", "Taps", "fn", "zeta", "vs ref"))
                            val refSummary = tapRef?.let { tapSummaries[it.id] }
                            for (m in tapMetas) {
                                val s = tapSummaries[m.id]
                                TapTableRow(m, s, refSummary, isRef = m.id == tapRef?.id)
                                if (s != null && s.beatingTaps > 0) {
                                    val (f1, f2) = s.beatFrequencies!!
                                    Text(
                                        "  beating in %d of %d taps: %.2f / %.2f Hz, implied mass ratio %.3f. Strong beating with slow decay means the absorber is under-damped; a single faster decay means it is well damped or over-damped.".format(
                                            Locale.US, s.beatingTaps, s.count, f1, f2, s.impliedMassRatio
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                            }
                            Text(
                                "Damping from the exponential decay of the dominant axis after each impulse. A working damper raises zeta or splits fn in two.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        AnalysisBar(
            settings = settings,
            sampleRateHint = state.results.firstOrNull()?.sampleRate,
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
private fun DiagnosisCard(r: DriveRow, d: TuningDiagnosis) {
    val accent = when (d.verdict) {
        DampingVerdict.NEAR_OPTIMAL -> MaterialTheme.colorScheme.primary
        DampingVerdict.INCONCLUSIVE, DampingVerdict.NO_EFFECT -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.tertiary
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(r.result.meta.name, style = MaterialTheme.typography.labelLarge, color = seriesColor(r.result.meta.colorIndex), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(d.verdict.label, style = MaterialTheme.typography.titleSmall, color = accent)
            Text(d.advice, style = MaterialTheme.typography.bodySmall)
            val facts = buildList {
                add("peak %+.1f dB vs baseline".format(Locale.US, d.achievedReductionDb))
                if (!d.optimalReductionDb.isNaN())
                    add("optimum %+.1f dB".format(Locale.US, d.optimalReductionDb))
                if (!d.massRatio.isNaN())
                    add("mu %.3f".format(Locale.US, d.massRatio))
                if (!d.optimalDampingRatio.isNaN())
                    add("zeta_opt %.3f".format(Locale.US, d.optimalDampingRatio))
                if (!d.notchDepthDb.isNaN())
                    add("notch %.1f dB".format(Locale.US, d.notchDepthDb))
                if (!d.peakBalanceDb.isNaN())
                    add("balance %+.1f dB".format(Locale.US, d.peakBalanceDb))
            }
            Text(facts.joinToString("  |  "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
}

private val COLUMN_WEIGHTS = listOf(0.34f, 0.18f, 0.18f, 0.15f, 0.15f)

@Composable
private fun HeaderRow(labels: List<String>) {
    Row(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, l ->
            Text(l, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(COLUMN_WEIGHTS[i]))
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}

@Composable
private fun DriveTableRow(r: DriveRow, ref: DriveRow?, settings: AnalysisSettings) {
    val isRef = ref != null && r.result.meta.id == ref.result.meta.id
    val comparable = ref != null && !isRef && ref.bandRms > 0 && !r.bandRms.isNaN()
    val delta = if (comparable) (r.bandRms / ref!!.bandRms - 1) * 100 else null
    val deltaDb = if (comparable && r.bandRms > 0) 20 * Math.log10(r.bandRms / ref!!.bandRms) else null
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            (if (isRef) "* " else "") + r.result.meta.name,
            style = MaterialTheme.typography.bodySmall,
            color = seriesColor(r.result.meta.colorIndex),
            fontWeight = if (isRef) FontWeight.Bold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(COLUMN_WEIGHTS[0]),
        )
        Text(
            if (r.bandRms.isNaN()) "n/a" else "%.4f".format(Locale.US, r.bandRms),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(COLUMN_WEIGHTS[1]),
        )
        Text(
            when {
                isRef -> "ref"
                delta == null -> "--"
                deltaDb == null -> "%+.0f%%".format(Locale.US, delta)
                else -> "%+.0f%%\n%+.1f dB".format(Locale.US, delta, deltaDb)
            },
            style = MaterialTheme.typography.bodySmall,
            color = when {
                delta == null -> MaterialTheme.colorScheme.onSurface
                delta < -10 -> MaterialTheme.colorScheme.primary
                delta > 10 -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(COLUMN_WEIGHTS[2]),
        )
        Text(r.peak?.let { "%.1f Hz".format(Locale.US, it.freq) } ?: "--", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(COLUMN_WEIGHTS[3]))
        Text(
            r.peak?.dampingRatio?.takeIf { !it.isNaN() }?.let { "%.3f".format(Locale.US, it) } ?: "--",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(COLUMN_WEIGHTS[4]),
        )
    }
}

@Composable
private fun TapTableRow(m: DatasetMeta, s: TapSummary?, ref: TapSummary?, isRef: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            (if (isRef) "* " else "") + m.name,
            style = MaterialTheme.typography.bodySmall,
            color = seriesColor(m.colorIndex),
            fontWeight = if (isRef) FontWeight.Bold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(COLUMN_WEIGHTS[0]),
        )
        if (s == null) {
            Text("...", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(COLUMN_WEIGHTS[1] + COLUMN_WEIGHTS[2] + COLUMN_WEIGHTS[3] + COLUMN_WEIGHTS[4]))
            return@Row
        }
        Text(s.count.toString(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(COLUMN_WEIGHTS[1]))
        Text(if (s.count > 0) "%.2f Hz".format(Locale.US, s.meanFreq) else "--", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(COLUMN_WEIGHTS[2]))
        Text(if (s.count > 0) "%.4f".format(Locale.US, s.meanDamping) else "--", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(COLUMN_WEIGHTS[3]))
        val vs = if (isRef) "ref" else if (ref != null && ref.count > 0 && s.count > 0)
            "zeta x%.2f\n%+.2f Hz".format(Locale.US, s.meanDamping / ref.meanDamping, s.meanFreq - ref.meanFreq)
        else "--"
        Text(vs, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(COLUMN_WEIGHTS[4]))
    }
}
