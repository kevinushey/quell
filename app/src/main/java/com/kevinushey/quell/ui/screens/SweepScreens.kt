package com.kevinushey.quell.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevinushey.quell.data.DatasetMeta
import com.kevinushey.quell.data.ExperimentKind
import com.kevinushey.quell.data.Sweep
import com.kevinushey.quell.data.SweepParameter
import com.kevinushey.quell.export.Exporter
import com.kevinushey.quell.ui.MainViewModel
import com.kevinushey.quell.ui.SweepAnalysis
import com.kevinushey.quell.ui.SweepPoint
import com.kevinushey.quell.ui.SweepSummary
import com.kevinushey.quell.ui.chart.ChartBand
import com.kevinushey.quell.ui.chart.ChartSeries
import com.kevinushey.quell.ui.chart.ErrorBar
import com.kevinushey.quell.ui.chart.LineChart
import com.kevinushey.quell.ui.chart.PointMarker
import com.kevinushey.quell.ui.chart.VerticalMarker
import com.kevinushey.quell.ui.components.ChartCard
import com.kevinushey.quell.ui.components.EmptyState
import com.kevinushey.quell.ui.components.MenuChip
import com.kevinushey.quell.ui.components.parseDecimal
import com.kevinushey.quell.ui.theme.seriesColor
import kotlinx.coroutines.launch
import java.util.Locale

/** All sweeps, with a way to start a new one. */
@Composable
fun SweepsScreen(vm: MainViewModel, onOpen: (String) -> Unit) {
    val sweeps by vm.sweeps.collectAsStateWithLifecycle()
    val datasets by vm.datasets.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        if (sweeps.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Timeline,
                title = "No sweeps",
                message = "A sweep steps one damper parameter (mass, stiffness, position) across several runs and fits the optimum. Pick a baseline run, then record a run at each value.",
                action = { Button(onClick = { creating = true }) { Text("New sweep") } },
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(sweeps, key = { it.id }) { sweep ->
                    val count = datasets.count { it.sweepId == sweep.id }
                    Card(Modifier.fillMaxWidth().clickable { onOpen(sweep.id) }) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(sweep.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${sweep.parameter.label}  |  ${sweep.kind.label}  |  $count run${if (count == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        FloatingActionButton(onClick = { creating = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Default.Add, contentDescription = "New sweep")
        }
    }

    if (creating) {
        NewSweepDialog(onDismiss = { creating = false }) { name, parameter, kind ->
            creating = false
            scope.launch {
                vm.createSweep(name, parameter, kind)?.let { onOpen(it.id) }
            }
        }
    }
}

@Composable
private fun NewSweepDialog(onDismiss: () -> Unit, onCreate: (String, SweepParameter, ExperimentKind) -> Unit) {
    var name by remember { mutableStateOf("") }
    var parameter by remember { mutableStateOf(SweepParameter.MASS) }
    var kind by remember { mutableStateOf(ExperimentKind.DRIVE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New sweep") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Parameter being stepped", style = MaterialTheme.typography.labelMedium)
                for (p in SweepParameter.entries) {
                    Row(Modifier.fillMaxWidth().clickable { parameter = p }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = p == parameter, onClick = { parameter = p })
                        Text(if (p.unit.isEmpty()) p.label else "${p.label} (${p.unit})")
                    }
                }
                Text("Runs are", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in listOf(ExperimentKind.DRIVE, ExperimentKind.TAP))
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) })
                }
                Text(
                    if (kind == ExperimentKind.TAP) "Tap sweeps maximize the damping ratio measured from ring-downs."
                    else "Drive sweeps minimize the in-band peak against the baseline run.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name.ifBlank { "${parameter.label} sweep" }, parameter, kind) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private sealed interface SweepDialog {
    data class Value(val meta: DatasetMeta) : SweepDialog
    data object AddRuns : SweepDialog
    data object Rename : SweepDialog
    data object Delete : SweepDialog
}

/** One sweep: its runs against the parameter, the fitted optimum, and the way to the next run. */
@Composable
fun SweepScreen(vm: MainViewModel, sweepId: String, onRecord: (String) -> Unit, onClosed: () -> Unit) {
    val sweeps by vm.sweeps.collectAsStateWithLifecycle()
    val datasets by vm.datasets.collectAsStateWithLifecycle()
    val settings by vm.analysis.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val sweep = sweeps.firstOrNull { it.id == sweepId }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<SweepDialog?>(null) }
    var menu by remember { mutableStateOf(false) }

    // Deleted: there is nothing to show here. Before the index has loaded, every sweep is still missing.
    LaunchedEffect(sweep == null, ready) {
        if (ready && sweep == null)
            onClosed()
    }
    if (sweep == null)
        return

    val members = datasets.filter { it.sweepId == sweep.id }.sortedWith(compareBy<DatasetMeta, Double?>(nullsLast()) { sweep.valueOf(it) })
    val baseline = datasets.firstOrNull { it.id == sweep.referenceId }
    val lo = settings.targetBandLow
    val hi = settings.targetBandHigh
    val isTap = sweep.kind == ExperimentKind.TAP

    // Analysis runs off the visible-datasets flow on purpose: hidden members still count here.
    val analysisKey = listOf(
        members.map { Triple(it.id, it.trimStart to it.trimEnd, sweep.valueOf(it)) },
        baseline?.let { Triple(it.id, it.trimStart, it.trimEnd) },
        settings.channel, settings.segmentSize, settings.window, settings.overlap, settings.normalization,
        settings.refBandLow, settings.refBandHigh, lo, hi, settings.tapThreshold, sweep.kind, sweep.parameter,
    )
    val summary by produceState<SweepSummary?>(null, analysisKey) {
        value = null
        val points = if (isTap) {
            val states = members.map { m ->
                val s = vm.tapSummary(m, settings.tapThreshold)
                m to (if (s != null) Result.success(s) else Result.failure<com.kevinushey.quell.ui.TapSummary>(IllegalStateException("analysis failed")))
            }
            SweepAnalysis.tapPoints(sweep, states)
        } else {
            val base = baseline?.let { vm.spectrumResult(it, settings) }
            val results = members.mapNotNull { vm.spectrumResult(it, settings) }
            SweepAnalysis.drivePoints(sweep, results, base, lo, hi, baselineFailed = baseline != null && base == null)
        }
        value = SweepAnalysis.summarize(sweep, points)
    }
    // Keep the recommendation on the sweep so the Record screen can pre-fill it; null clears a stale one.
    LaunchedEffect(summary) {
        val s = summary ?: return@LaunchedEffect
        if (s.nextValue != sweep.nextValue)
            vm.updateSweep(sweep.id) { it.copy(nextValue = s.nextValue) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Any run of the same kind can be the baseline, including one recorded without the damper inside this sweep.
            val candidates = listOf<String?>(null) + datasets.filter { it.kind == sweep.kind }.map { it.id }
            MenuChip(
                label = "Baseline: ${baseline?.name ?: "none"}",
                options = candidates,
                selected = sweep.referenceId,
                onSelect = { id -> vm.updateSweep(sweep.id) { it.copy(referenceId = id) } },
                optionLabel = { id -> if (id == null) "none" else datasets.first { it.id == id }.name },
            )
            Box(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Sweep actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Add existing runs") }, onClick = { menu = false; dialog = SweepDialog.AddRuns })
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; dialog = SweepDialog.Rename })
                    DropdownMenuItem(
                        text = { Text("Export CSV") },
                        enabled = summary != null,
                        onClick = {
                            menu = false
                            val s = summary ?: return@DropdownMenuItem
                            scope.launch {
                                runCatching { Exporter.shareSweepCsv(context, sweep, s) }.onFailure { vm.message("Could not export CSV: ${it.message}") }
                            }
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Delete sweep") }, onClick = { menu = false; dialog = SweepDialog.Delete })
                }
            }
        }

        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(sweep.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${sweep.parameter.label} sweep over ${sweep.kind.label.lowercase(Locale.US)} runs; " +
                                if (isTap) "higher damping ratio is better." else "lower in-band peak (%.1f-%.1f Hz) against the baseline is better.".format(Locale.US, lo, hi),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val next = summary?.nextValue ?: sweep.suggestedNext
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { onRecord(sweep.id) }) {
                                Icon(Icons.Default.Sensors, contentDescription = null)
                                Text(
                                    when {
                                        next != null -> "  Record at ${sweep.parameter.format(next)}"
                                        members.isEmpty() -> "  Record first run"
                                        else -> "  Record next run"
                                    }
                                )
                            }
                            if (!isTap && baseline == null)
                                Text("Pick a baseline to compare against.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            val s = summary
            if (s != null && s.pooled.size >= 2) {
                item {
                    SweepChart(sweep, s, isTap)
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Reading", style = MaterialTheme.typography.labelLarge)
                        Text(
                            when {
                                members.isEmpty() -> "No runs yet. Record the first run, or add existing ones from the menu."
                                s == null -> "Analyzing..."
                                else -> s.note
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (members.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            val unitLabel = sweep.parameter.unit.ifEmpty { "value" }
                            SweepHeaderRow(listOf("Run", unitLabel, if (isTap) "zeta" else "peak dB", if (isTap) "fn" else "peak", "+/-"))
                            for (m in members) {
                                val point = s?.points?.firstOrNull { it.meta.id == m.id }
                                SweepTableRow(
                                    sweep = sweep,
                                    meta = m,
                                    point = point,
                                    analyzing = s == null,
                                    isTap = isTap,
                                    onSetValue = { dialog = SweepDialog.Value(m) },
                                    onRemove = { vm.removeFromSweep(m.id) },
                                    onBaseline = { vm.updateSweep(sweep.id) { it.copy(referenceId = m.id) } },
                                )
                            }
                            Text(
                                (if (isTap) "Damping ratio from the ring-down fits; +/- is the standard error over the taps in each run."
                                else "Peak dB is the tallest in-band peak relative to the baseline's; +/- is the one-sigma random error of the pair of PSD estimates.") +
                                    " Repeated runs at one value are pooled on the chart.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        is SweepDialog.Value -> ValueDialog(sweep, sweep.valueOf(d.meta), onDismiss = { dialog = null }) {
            vm.setSweepValue(sweep, d.meta.id, it)
            dialog = null
        }
        SweepDialog.AddRuns -> AddRunsDialog(
            candidates = datasets.filter { it.kind == sweep.kind && it.sweepId != sweep.id },
            onDismiss = { dialog = null },
        ) { ids ->
            for (id in ids)
                vm.addToSweep(sweep, id, null)
            dialog = null
        }
        SweepDialog.Rename -> {
            var text by remember { mutableStateOf(sweep.name) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Rename sweep") },
                text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
                confirmButton = {
                    TextButton(onClick = {
                        vm.updateSweep(sweep.id) { it.copy(name = text.trim().ifEmpty { it.name }) }
                        dialog = null
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
        SweepDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete \"${sweep.name}\"?") },
            text = { Text("The runs stay in your data; only the grouping and its baseline choice are removed.") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    vm.deleteSweep(sweep.id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SweepChart(sweep: Sweep, s: SweepSummary, isTap: Boolean) {
    // Pooled points: repeats at one value are one point with a tighter error bar.
    val pooled = s.pooled
    val xs = DoubleArray(pooled.size) { pooled[it].value }
    val ys = DoubleArray(pooled.size) { pooled[it].metric }
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val series = ArrayList<ChartSeries>()
    series.add(ChartSeries("runs", "Runs", primary, xs, ys, strokeWidthDp = 2.0f))
    val bands = ArrayList<ChartBand>()
    val fit = s.fit
    // Without an optimum the curve would only invite a conclusion the reading declines to draw.
    if (fit != null && s.optimum != null) {
        // Draw the curve a little past the data so an optimum just outside is visible.
        val span = (xs.last() - xs.first()).coerceAtLeast(1e-9)
        val from = xs.first() - 0.1 * span
        val to = xs.last() + 0.1 * span
        val fx = DoubleArray(60) { from + (to - from) * it / 59 }
        val fy = DoubleArray(fx.size) { fit.at(fx[it]) }
        series.add(ChartSeries("fit", "Parabola fit", tertiary, fx, fy, dashed = true))
        // One sigma around the curve: differences inside the shading are not resolved.
        if (s.sigma.isFinite() && s.sigma > 0)
            bands.add(ChartBand("sigma", tertiary, fx, DoubleArray(fx.size) { fy[it] - s.sigma }, DoubleArray(fx.size) { fy[it] + s.sigma }))
    }
    // Value-to-value comparison: the baseline's share of the error is common to every point and left out.
    val errorBars = pooled.map { ErrorBar(it.value, it.metric, it.ownError, primary) }
    val markers = ArrayList<VerticalMarker>()
    s.optimum?.let { markers.add(VerticalMarker(it, "optimum", tertiary)) }
    ChartCard(
        legend = emptyList(),
        exportHost = null,
        modifier = Modifier.fillMaxWidth().height(240.dp),
        title = if (isTap) "Damping ratio vs ${sweep.parameter.label.lowercase(Locale.US)}" else "Peak vs baseline against ${sweep.parameter.label.lowercase(Locale.US)}",
    ) {
        LineChart(
            series = series,
            xLabel = if (sweep.parameter.unit.isEmpty()) sweep.parameter.label else "${sweep.parameter.label} (${sweep.parameter.unit})",
            yLabel = if (isTap) "Damping ratio" else "Peak (dB vs baseline)",
            verticalMarkers = markers,
            bands = bands,
            errorBars = errorBars,
            pointMarkers = pooled.map { p ->
                val label = sweep.parameter.format(p.value) + if (p.repeats > 1) " x${p.repeats}" else ""
                PointMarker(p.value, p.metric, label, seriesColor(p.runs.first().meta.colorIndex))
            },
            showZeroLine = !isTap,
            viewportKey = pooled.map { it.value to it.repeats },
            xValueFormatter = { sweep.parameter.format(it) },
            yValueFormatter = { if (isTap) "%.4f".format(Locale.US, it) else "%+.1f dB".format(Locale.US, it) },
        )
    }
}

private val SWEEP_WEIGHTS = listOf(0.34f, 0.17f, 0.19f, 0.17f, 0.13f)

@Composable
private fun SweepHeaderRow(labels: List<String>) {
    Row(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, l ->
            Text(l, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(SWEEP_WEIGHTS[i]))
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}

@Composable
private fun SweepTableRow(
    sweep: Sweep,
    meta: DatasetMeta,
    point: SweepPoint?,
    analyzing: Boolean,
    isTap: Boolean,
    onSetValue: () -> Unit,
    onRemove: () -> Unit,
    onBaseline: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val value = sweep.valueOf(meta)
    val isBaseline = meta.id == sweep.referenceId
    Row(Modifier.fillMaxWidth().clickable { menu = true }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            (if (isBaseline) "* " else "") + meta.name,
            style = MaterialTheme.typography.bodySmall,
            color = seriesColor(meta.colorIndex),
            fontWeight = if (isBaseline) FontWeight.Bold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(SWEEP_WEIGHTS[0]),
        )
        Text(
            value?.let { sweep.parameter.formatNumber(it) } ?: "set...",
            style = MaterialTheme.typography.bodySmall,
            color = if (value == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(SWEEP_WEIGHTS[1]),
        )
        when {
            point == null && analyzing -> Text("...", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(SWEEP_WEIGHTS[2] + SWEEP_WEIGHTS[3] + SWEEP_WEIGHTS[4]))
            point == null || point.problem != null -> Text(
                point?.problem ?: "not analyzed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(SWEEP_WEIGHTS[2] + SWEEP_WEIGHTS[3] + SWEEP_WEIGHTS[4]),
            )
            else -> {
                Text(
                    if (isTap) "%.4f".format(Locale.US, point.metric) else "%+.1f".format(Locale.US, point.metric),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(SWEEP_WEIGHTS[2]),
                )
                Text(
                    if (point.peakFreq.isFinite()) "%.1f Hz".format(Locale.US, point.peakFreq) else "--",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(SWEEP_WEIGHTS[3]),
                )
                Text(
                    if (point.error.isFinite()) (if (isTap) "%.4f" else "%.1f").format(Locale.US, point.error) else "--",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(SWEEP_WEIGHTS[4]),
                )
            }
        }
    }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(text = { Text("Set ${sweep.parameter.label.lowercase(Locale.US)}") }, onClick = { menu = false; onSetValue() })
        DropdownMenuItem(text = { Text("Use as baseline") }, enabled = !isBaseline, onClick = { menu = false; onBaseline() })
        DropdownMenuItem(text = { Text("Remove from sweep") }, onClick = { menu = false; onRemove() })
    }
}

@Composable
private fun ValueDialog(sweep: Sweep, current: Double?, onDismiss: () -> Unit, onConfirm: (Double?) -> Unit) {
    val initial = remember { current?.let { sweep.parameter.formatNumber(it) } ?: "" }
    var text by remember { mutableStateOf(initial) }
    val parsed = parseDecimal(text)?.takeIf { it.isFinite() }
    val valid = text.isBlank() || parsed != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(sweep.parameter.label) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                isError = !valid,
                label = { Text(sweep.parameter.unit.ifEmpty { "value" }) },
                supportingText = if (valid) null else ({ Text("Enter a number") }),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        // Untouched text keeps the stored value, which the field shows rounded to two decimals.
        confirmButton = { TextButton(enabled = valid, onClick = { onConfirm(if (text == initial) current else parsed) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddRunsDialog(candidates: List<DatasetMeta>, onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    val chosen = remember { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add runs to sweep") },
        text = {
            if (candidates.isEmpty()) {
                Text("No runs of this type are available. Record one, or set a dataset's experiment type on the Data tab.")
            } else {
                LazyColumn(Modifier.height(320.dp)) {
                    items(candidates, key = { it.id }) { m ->
                        val checked = m.id in chosen.value
                        Row(
                            Modifier.fillMaxWidth().clickable { chosen.value = if (checked) chosen.value - m.id else chosen.value + m.id },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { chosen.value = if (it) chosen.value + m.id else chosen.value - m.id })
                            Column {
                                Text(m.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (m.sweepId != null)
                                    Text("in another sweep", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = chosen.value.isNotEmpty(), onClick = { onAdd(chosen.value.toList()) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
