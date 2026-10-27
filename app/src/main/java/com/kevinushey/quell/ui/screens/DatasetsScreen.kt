package com.kevinushey.quell.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevinushey.quell.data.DatasetMeta
import com.kevinushey.quell.data.ExperimentKind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.height
import com.kevinushey.quell.export.Exporter
import com.kevinushey.quell.ui.MainViewModel
import com.kevinushey.quell.ui.components.EmptyState
import com.kevinushey.quell.ui.formatCount
import com.kevinushey.quell.ui.formatDuration
import com.kevinushey.quell.ui.formatHz
import com.kevinushey.quell.ui.theme.SeriesPalette
import com.kevinushey.quell.ui.theme.seriesColor
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private sealed interface Dialog {
    data class Rename(val meta: DatasetMeta) : Dialog
    data class Notes(val meta: DatasetMeta) : Dialog
    data class Color(val meta: DatasetMeta) : Dialog
    data class Details(val meta: DatasetMeta) : Dialog
    data class Delete(val meta: DatasetMeta) : Dialog
    data class Mass(val meta: DatasetMeta) : Dialog
    data class Trim(val meta: DatasetMeta) : Dialog
    data class Taps(val meta: DatasetMeta) : Dialog
    data class Locate(val meta: DatasetMeta) : Dialog
    data class Kind(val meta: DatasetMeta) : Dialog
}

@Composable
fun DatasetsScreen(vm: MainViewModel, onImport: () -> Unit, onRecord: () -> Unit) {
    val datasets by vm.datasets.collectAsStateWithLifecycle()
    val sweeps by vm.sweeps.collectAsStateWithLifecycle()
    val settings by vm.analysis.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        if (ready && datasets.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Storage,
                title = "No datasets",
                message = "Import phyphox exports or CSV files, or record directly with this phone. Files can also be shared to this app from Drive, Files or phyphox.",
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onImport) { Text("Import files") }
                        OutlinedButton(onClick = onRecord) { Text("Record") }
                    }
                },
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${datasets.count { it.visible }} of ${datasets.size} visible",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.setAllVisible(true) }) { Text("Show all") }
                        TextButton(onClick = { vm.setAllVisible(false) }) { Text("Hide all") }
                    }
                }
                items(datasets, key = { it.id }) { meta ->
                    DatasetRow(
                        meta = meta,
                        sweepName = meta.sweepId?.let { id -> sweeps.firstOrNull { it.id == id }?.name },
                        isReference = meta.id == settings.referenceId,
                        isFirst = datasets.first().id == meta.id,
                        isLast = datasets.last().id == meta.id,
                        onToggle = { vm.setVisible(meta.id, !meta.visible) },
                        onColor = { dialog = Dialog.Color(meta) },
                        onAction = { action ->
                            when (action) {
                                RowAction.RENAME -> dialog = Dialog.Rename(meta)
                                RowAction.NOTES -> dialog = Dialog.Notes(meta)
                                RowAction.KIND -> dialog = Dialog.Kind(meta)
                                RowAction.MASS -> dialog = Dialog.Mass(meta)
                                RowAction.TRIM -> dialog = Dialog.Trim(meta)
                                RowAction.TAPS -> dialog = Dialog.Taps(meta)
                                RowAction.LOCATE -> dialog = Dialog.Locate(meta)
                                RowAction.REFERENCE -> vm.setReference(if (meta.id == settings.referenceId) null else meta.id)
                                RowAction.SOLO -> vm.soloVisible(meta.id)
                                RowAction.UP -> vm.move(meta.id, -1)
                                RowAction.DOWN -> vm.move(meta.id, +1)
                                RowAction.DETAILS -> dialog = Dialog.Details(meta)
                                RowAction.EXPORT -> scope.launch {
                                    val s = vm.series(meta.id)
                                    if (s != null)
                                        runCatching { Exporter.shareRawCsv(context, meta, vm.sweeps.value.firstOrNull { it.id == meta.sweepId }, s) }.onFailure { vm.message("Export failed: ${it.message}") }
                                }
                                RowAction.DELETE -> dialog = Dialog.Delete(meta)
                            }
                        },
                    )
                }
            }
        }

        FloatingActionButton(onClick = onImport, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Default.Add, contentDescription = "Import")
        }
    }

    when (val d = dialog) {
        null -> Unit
        is Dialog.Rename -> TextDialog("Rename dataset", d.meta.name, singleLine = true, onDismiss = { dialog = null }) {
            vm.rename(d.meta.id, it)
            dialog = null
        }
        is Dialog.Notes -> TextDialog("Notes", d.meta.notes, singleLine = false, onDismiss = { dialog = null }) {
            vm.setNotes(d.meta.id, it)
            dialog = null
        }
        is Dialog.Color -> ColorDialog(d.meta.colorIndex, onDismiss = { dialog = null }) {
            vm.setColor(d.meta.id, it)
            dialog = null
        }
        is Dialog.Details -> DetailsDialog(d.meta) { dialog = null }
        is Dialog.Mass -> MassDialog(d.meta, onDismiss = { dialog = null }) {
            vm.setDamperMass(d.meta.id, it)
            dialog = null
        }
        is Dialog.Trim -> TrimDialog(vm, d.meta, onDismiss = { dialog = null }) { start, end ->
            vm.setTrim(d.meta.id, start, end)
            dialog = null
        }
        is Dialog.Taps -> TapResultsDialog(vm, d.meta, settings.tapThreshold) { dialog = null }
        is Dialog.Locate -> LocateResultsDialog(vm, d.meta, settings.tapThreshold) { dialog = null }
        is Dialog.Kind -> KindDialog(d.meta.kind, onDismiss = { dialog = null }) {
            vm.setKind(d.meta.id, it)
            dialog = null
        }
        is Dialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete \"${d.meta.name}\"?") },
            text = { Text("The imported samples are removed from this app. Your original file is not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(d.meta.id)
                    dialog = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }
}

private enum class RowAction(val label: String) {
    RENAME("Rename"),
    NOTES("Edit notes"),
    KIND("Set experiment type"),
    MASS("Set damper mass"),
    TRIM("Trim time range"),
    TAPS("Analyze taps (ring-down)"),
    LOCATE("Antinode results"),
    REFERENCE("Use as reference"),
    SOLO("Show only this"),
    UP("Move up"),
    DOWN("Move down"),
    DETAILS("Details"),
    EXPORT("Export raw CSV"),
    DELETE("Delete"),
}

@Composable
private fun DatasetRow(
    meta: DatasetMeta,
    sweepName: String?,
    isReference: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onToggle: () -> Unit,
    onColor: () -> Unit,
    onAction: (RowAction) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = meta.visible, onCheckedChange = { onToggle() })
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(seriesColor(meta.colorIndex)).clickable(onClick = onColor),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isReference)
                        Icon(Icons.Default.Star, contentDescription = "Reference", modifier = Modifier.size(14.dp).padding(end = 2.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(meta.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "${formatDuration(meta.durationSec)}  |  ${formatHz(meta.sampleRate)}  |  ${formatCount(meta.sampleCount)} samples",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val tags = buildList {
                    if (meta.kind != ExperimentKind.GENERAL)
                        add(meta.kind.label)
                    meta.damperMassGrams?.let { add("%.1f g".format(java.util.Locale.US, it)) }
                    if (sweepName != null)
                        add("sweep: $sweepName")
                    if (meta.locationMarks.isNotEmpty())
                        add("antinode search, ${meta.locationMarks.size + 1} locations")
                    if (meta.isTrimmed)
                        add("trimmed %.1f-%.1f s".format(java.util.Locale.US, meta.trimStart, meta.effectiveTrimEnd))
                }
                Text(
                    tags.joinToString("  |  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (meta.kind == ExperimentKind.TAP) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                )
                if (meta.notes.isNotBlank())
                    Text(meta.notes, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (a in RowAction.entries) {
                        if (a == RowAction.UP && isFirst || a == RowAction.DOWN && isLast)
                            continue
                        if (a == RowAction.LOCATE && meta.locationMarks.isEmpty())
                            continue
                        if (a == RowAction.DELETE)
                            HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(if (a == RowAction.REFERENCE && isReference) "Clear reference" else a.label) },
                            onClick = {
                                menu = false
                                onAction(a)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TextDialog(title: String, initial: String, singleLine: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = singleLine, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorDialog(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Series color") },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SeriesPalette.forEachIndexed { i, c ->
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(c).clickable { onPick(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (i == Math.floorMod(current, SeriesPalette.size))
                            Icon(Icons.Default.Check, contentDescription = "Selected", tint = Color.White)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun KindDialog(current: ExperimentKind, onDismiss: () -> Unit, onPick: (ExperimentKind) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Experiment type") },
        text = {
            Column {
                for (k in ExperimentKind.entries) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(k) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = k == current, onClick = { onPick(k) })
                        Text(k.label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DetailsDialog(meta: DatasetMeta, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(meta.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DetailLine("Source", meta.sourceName)
                DetailLine("Imported", DateFormat.getDateTimeInstance().format(Date(meta.importedAtEpochMs)))
                meta.recordedAt?.let { DetailLine("Recorded", it) }
                DetailLine("Type", meta.kind.label)
                meta.damperMassGrams?.let { DetailLine("Damper mass", "%.1f g".format(java.util.Locale.US, it)) }
                if (meta.isTrimmed)
                    DetailLine("Analysis window", "%.1f s to %.1f s".format(java.util.Locale.US, meta.trimStart, meta.effectiveTrimEnd))
                DetailLine("Duration", formatDuration(meta.durationSec))
                DetailLine("Sample rate", formatHz(meta.sampleRate))
                DetailLine("Samples", formatCount(meta.sampleCount))
                meta.deviceModel?.let { DetailLine("Device", it) }
                meta.sensorName?.let { DetailLine("Sensor", it) }
                for ((k, v) in meta.extraFields) {
                    if (k.startsWith("accelerometer ") && k != "accelerometer Name")
                        DetailLine(k.removePrefix("accelerometer "), v)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}


@Composable
private fun MassDialog(meta: DatasetMeta, onDismiss: () -> Unit, onConfirm: (Double?) -> Unit) {
    val initial = remember { meta.damperMassGrams?.let { com.kevinushey.quell.data.SweepParameter.MASS.formatNumber(it) } ?: "" }
    var text by remember { mutableStateOf(initial) }
    // Empty clears the mass; anything else must parse, so a typo cannot silently clear it.
    val parsed = com.kevinushey.quell.ui.components.parseDecimal(text)?.takeIf { it.isFinite() }
    val valid = text.isBlank() || parsed != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Damper mass") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mass fitted during this run, in grams. Leave empty for no damper.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = !valid,
                    label = { Text("grams") },
                    supportingText = if (valid) null else ({ Text("Enter a number") }),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                )
            }
        },
        // Untouched text keeps the stored value: the field shows it rounded, and a sweep pools runs by exact value.
        confirmButton = { TextButton(enabled = valid, onClick = { onConfirm(if (text == initial) meta.damperMassGrams else parsed) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TrimDialog(vm: MainViewModel, meta: DatasetMeta, onDismiss: () -> Unit, onConfirm: (Double, Double) -> Unit) {
    val duration = meta.durationSec.coerceAtLeast(0.01)
    var range by remember { mutableStateOf(meta.trimStart.toFloat()..meta.effectiveTrimEnd.toFloat()) }
    val preview by androidx.compose.runtime.produceState<FloatArray?>(null, meta.id) {
        val s = vm.series(meta.id) ?: return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            // Peak |a - mean| per column, enough to see where the car was moving. Columns are
            // elapsed-time buckets so they line up with the slider even when the rate varied.
            val m = s.magnitude()
            val mean = m.average()
            val cols = 240
            val t0 = s.time.first()
            val span = (s.time.last() - t0).coerceAtLeast(1e-9)
            val peaks = FloatArray(cols)
            for (i in 0 until s.size) {
                val c = ((s.time[i] - t0) / span * cols).toInt().coerceIn(0, cols - 1)
                val v = Math.abs(m[i] - mean).toFloat()
                if (v > peaks[c])
                    peaks[c] = v
            }
            peaks
        }
    }
    // null while computing; a failure (empty result) is a Result too, so the button never says "..." forever.
    val suggestion by androidx.compose.runtime.produceState<Result<com.kevinushey.quell.dsp.SteadyWindow?>?>(null, meta.id) {
        val s = vm.series(meta.id)
        value = if (s == null) Result.failure(IllegalStateException("no samples")) else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { com.kevinushey.quell.dsp.AutoTrim.steadyWindow(s.time, s.magnitude()) }
        }
    }
    val lineColor = seriesColor(meta.colorIndex)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.tertiary
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Analysis window") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Drag to keep only the steady part of the run. Spectra, the Damper tab and exports use this window.", style = MaterialTheme.typography.bodySmall)
                val steady = suggestion?.getOrNull()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = steady != null,
                        onClick = {
                            val st = steady ?: return@OutlinedButton
                            // "The whole run looks steady" means the whole run, not the whole run minus a cell.
                            range = if (st.coversAll(duration)) 0f..duration.toFloat() else st.start.toFloat()..st.end.toFloat()
                        },
                    ) { Text("Keep the steady part") }
                    Text(
                        when {
                            suggestion == null -> "Looking for the steady stretch..."
                            steady == null -> "No steady stretch stands out; drag by hand."
                            steady.coversAll(duration) -> "The whole run looks steady."
                            else -> "Steady from %.1f s to %.1f s (level within %.0f dB).".format(java.util.Locale.US, steady.start, steady.end, steady.rangeDb)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(80.dp)) {
                    val p = preview ?: return@Canvas
                    val max = (p.maxOrNull() ?: 1f).coerceAtLeast(1e-6f)
                    val colW = size.width / p.size
                    val startX = (range.start / duration * size.width).toFloat()
                    val endX = (range.endInclusive / duration * size.width).toFloat()
                    drawRect(dim.copy(alpha = 0.12f), topLeft = Offset.Zero, size = androidx.compose.ui.geometry.Size(startX, size.height))
                    drawRect(dim.copy(alpha = 0.12f), topLeft = Offset(endX, 0f), size = androidx.compose.ui.geometry.Size(size.width - endX, size.height))
                    for (c in p.indices) {
                        val h = p[c] / max * size.height
                        val x = c * colW
                        val inside = x >= startX && x <= endX
                        drawLine(if (inside) lineColor else dim.copy(alpha = 0.5f), Offset(x, size.height), Offset(x, size.height - h), colW.coerceAtLeast(1f))
                    }
                    drawLine(dim, Offset(startX, 0f), Offset(startX, size.height), 2f)
                    drawLine(dim, Offset(endX, 0f), Offset(endX, size.height), 2f)
                    // The suggested steady stretch, as a bracket under the envelope.
                    val st = suggestion?.getOrNull()
                    if (st != null && !st.coversAll(duration)) {
                        val a = (st.start / duration * size.width).toFloat()
                        val b = (st.end / duration * size.width).toFloat()
                        drawLine(accent, Offset(a, size.height - 2f), Offset(b, size.height - 2f), 4f)
                    }
                }
                androidx.compose.material3.RangeSlider(
                    value = range,
                    onValueChange = { r ->
                        val minSpan = (1f).coerceAtMost(duration.toFloat())
                        if (r.endInclusive - r.start >= minSpan)
                            range = r
                    },
                    valueRange = 0f..duration.toFloat(),
                )
                Text(
                    "%.1f s to %.1f s  (%.1f s kept of %.1f s)".format(java.util.Locale.US, range.start, range.endInclusive, range.endInclusive - range.start, duration),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val end = if (range.endInclusive >= duration.toFloat() - 0.05f) 0.0 else range.endInclusive.toDouble()
                onConfirm(range.start.toDouble(), end)
            }) { Text("Apply") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onConfirm(0.0, 0.0) }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun LocateResultsDialog(vm: MainViewModel, meta: DatasetMeta, threshold: Double, onDismiss: () -> Unit) {
    val summary by androidx.compose.runtime.produceState<Result<com.kevinushey.quell.ui.LocateSummary>?>(null, meta.id, meta.trimStart, meta.trimEnd, threshold) {
        val s = vm.tapSummary(meta, threshold)
        value = if (s != null) Result.success(com.kevinushey.quell.ui.LocateAnalysis.summarize(s.taps, meta.locationMarks)) else Result.failure(IllegalStateException("analysis failed"))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Antinode search") },
        text = {
            val s = summary?.getOrNull()
            when {
                summary == null -> Text("Analyzing...")
                s == null -> Text("This recording could not be analyzed. Adjust its analysis window to exclude any gap, then try again.")
                s.ranked.isEmpty() -> Text("No clean tap above ${"%.1f".format(java.util.Locale.US, threshold)} m/s^2 was found in this recording. Lower the tap threshold in analysis options, or check that the taps rang long enough to fit.")
                else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(s.note, style = MaterialTheme.typography.bodySmall)
                    Text("Per location (strongest first)", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                    for (l in s.ranked)
                        Text(
                            "%s: %d taps, shape %.2f, ring %.2f m/s^2, fn %.1f Hz".format(java.util.Locale.US, l.label, l.taps, s.shape(l), l.meanAmplitude, l.meanFreq),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    val silent = s.locations.filter { it.taps == 0 }
                    if (silent.isNotEmpty())
                        Text("No clean tap at ${silent.joinToString { it.label.lowercase(java.util.Locale.US) }}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun TapResultsDialog(vm: MainViewModel, meta: DatasetMeta, threshold: Double, onDismiss: () -> Unit) {
    // null = analyzing; a null summary from the view model means the analysis failed, not that it is still running.
    val summary by androidx.compose.runtime.produceState<Result<com.kevinushey.quell.ui.TapSummary>?>(null, meta.id, meta.trimStart, meta.trimEnd, threshold) {
        val s = vm.tapSummary(meta, threshold)
        value = if (s != null) Result.success(s) else Result.failure(IllegalStateException("analysis failed"))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ring-down analysis") },
        text = {
            val s = summary?.getOrNull()
            when {
                summary == null -> Text("Analyzing...")
                s == null -> Text("This recording could not be analyzed. Adjust its analysis window to exclude any gap, then try again.")
                s.count == 0 -> Text("No impulse above ${"%.1f".format(java.util.Locale.US, threshold)} m/s^2 was found. Lower the tap threshold in analysis options, or check that this is a tap-test recording.")
                else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DetailLine("Taps found", s.count.toString())
                    DetailLine("Natural freq.", "%.2f Hz".format(java.util.Locale.US, s.meanFreq))
                    DetailLine("Damping ratio", if (s.sdDamping.isNaN()) "%.4f".format(java.util.Locale.US, s.meanDamping) else "%.4f +/- %.4f".format(java.util.Locale.US, s.meanDamping, s.sdDamping))
                    DetailLine("Q factor", "%.1f".format(java.util.Locale.US, s.meanQ))
                    DetailLine("Half-power BW", "%.2f Hz".format(java.util.Locale.US, 2 * s.meanDamping * s.meanFreq))
                    Text("Per tap", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                    for (t in s.taps)
                        Text(
                            "t=%.1fs  %.2f Hz  zeta %.4f  r2 %.2f".format(java.util.Locale.US, t.impulseTime, t.naturalFreq, t.dampingRatio, t.fitR2),
                            style = MaterialTheme.typography.bodySmall,
                        )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
