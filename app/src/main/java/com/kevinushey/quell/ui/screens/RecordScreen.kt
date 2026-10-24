package com.kevinushey.quell.ui.screens

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.SensorsOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevinushey.quell.data.ExperimentKind
import com.kevinushey.quell.data.ParsedRun
import com.kevinushey.quell.ui.TapAnalysis
import com.kevinushey.quell.ui.TapSummary
import androidx.compose.runtime.saveable.rememberSaveable
import com.kevinushey.quell.dsp.Channel
import com.kevinushey.quell.dsp.Normalization
import com.kevinushey.quell.dsp.Welch
import com.kevinushey.quell.record.Recorder
import com.kevinushey.quell.ui.MainViewModel
import com.kevinushey.quell.ui.Resampled
import com.kevinushey.quell.ui.chart.ChartSeries
import com.kevinushey.quell.ui.chart.LineChart
import com.kevinushey.quell.ui.components.ChartCard
import com.kevinushey.quell.ui.components.EmptyState
import com.kevinushey.quell.ui.formatCount
import com.kevinushey.quell.ui.formatDuration
import com.kevinushey.quell.ui.formatHz
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun RecordScreen(vm: MainViewModel, onSaved: () -> Unit) {
    val context = LocalContext.current
    val recorder = remember { Recorder(context.applicationContext) }
    val status by recorder.status.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<ParsedRun?>(null) }
    var liveSpectrum by remember { mutableStateOf<ChartSeries?>(null) }
    var tapMode by rememberSaveable { mutableStateOf(false) }
    var liveTaps by remember { mutableStateOf<TapSummary?>(null) }
    val settings by vm.analysis.collectAsStateWithLifecycle()
    val liveColor = MaterialTheme.colorScheme.primary

    DisposableEffect(Unit) {
        onDispose { if (recorder.status.value.recording) recorder.stop() }
    }

    // Keep the screen awake and the sensor stream alive while recording.
    val activity = context as? ComponentActivity
    DisposableEffect(status.recording) {
        if (status.recording)
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LaunchedEffect(status.recording, tapMode) {
        liveSpectrum = null
        liveTaps = null
        while (status.recording) {
            delay(500)
            if (tapMode) {
                val all = recorder.snapshot() ?: continue
                liveTaps = withContext(Dispatchers.Default) { TapAnalysis.analyze(all, settings.tapThreshold) }
                continue
            }
            val window = recorder.recentWindow(4.0) ?: continue
            liveSpectrum = withContext(Dispatchers.Default) {
                val r = Resampled.from(window)
                if (r.size < 256)
                    return@withContext null
                val seg = minOf(1024, Integer.highestOneBit(r.size))
                val px = Welch.psd(r.x, r.sampleRate, seg)
                val py = Welch.psd(r.y, r.sampleRate, seg)
                val pz = Welch.psd(r.z, r.sampleRate, seg)
                val total = DoubleArray(px.size) { Normalization.toDb(px.power[it] + py.power[it] + pz.power[it]) }
                val from = 1
                ChartSeries("live", "Last 4 s", liveColor, px.freq.copyOfRange(from, px.size), total.copyOfRange(from, px.size))
            }
        }
    }

    if (!status.sensorAvailable) {
        EmptyState(Icons.Default.SensorsOff, "No accelerometer", "This device does not report an accelerometer sensor.")
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !tapMode,
                onClick = { if (!status.recording) tapMode = false },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Drive") }
            SegmentedButton(
                selected = tapMode,
                onClick = { if (!status.recording) tapMode = true },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Tap test") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(status.sensorName ?: "Accelerometer", style = MaterialTheme.typography.titleSmall)
                val maxRate = if (recorder.minDelayMicros > 0) "up to %.0f Hz".format(Locale.US, 1e6 / recorder.minDelayMicros) else "rate unknown"
                Text("Hardware maximum: $maxRate", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Elapsed", formatDuration(status.elapsedSec))
                    Stat("Samples", formatCount(status.sampleCount))
                    Stat("Rate", if (status.sampleRate > 0) formatHz(status.sampleRate) else "--")
                }
                Text(
                    "x %+.2f   y %+.2f   z %+.2f  m/s^2".format(Locale.US, status.latest[0], status.latest[1], status.latest[2]),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (tapMode) {
            Card(Modifier.weight(1f).fillMaxWidth()) {
                val t = liveTaps
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Tap test", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Fix the phone to the structure, start, then strike it sharply several times, leaving about three seconds of silence after each tap. Each ring-down is analyzed as it happens.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (t != null && t.count > 0) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Stat("Taps", t.count.toString())
                            Stat("fn", "%.2f Hz".format(Locale.US, t.meanFreq))
                            Stat("zeta", "%.4f".format(Locale.US, t.meanDamping))
                            Stat("Q", "%.1f".format(Locale.US, t.meanQ))
                        }
                        val last = t.taps.last()
                        Text(
                            "last tap: %.2f Hz, zeta %.4f, fit r2 %.2f".format(Locale.US, last.naturalFreq, last.dampingRatio, last.fitR2),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        val env = ChartSeries("env", "Envelope", liveColor, last.envelopeTimes, last.envelopeAmps)
                        val fit = ChartSeries("fit", "Fit", MaterialTheme.colorScheme.tertiary, last.envelopeTimes, last.fittedAmps, dashed = true)
                        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().weight(1f)) {
                            LineChart(series = listOf(env, fit), xLabel = "Time after tap (s)", yLabel = "Amplitude (m/s^2)", viewportKey = last.impulseTime)
                        }
                    } else if (status.recording) {
                        Text("Waiting for a tap above %.1f m/s^2...".format(Locale.US, settings.tapThreshold), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } else {
            ChartCard(legend = emptyList(), exportHost = null, modifier = Modifier.weight(1f).fillMaxWidth(), title = "Live spectrum (X+Y+Z)") {
                val s = liveSpectrum
                if (s == null) {
                    EmptyState(
                        icon = Icons.Default.FiberManualRecord,
                        title = if (status.recording) "Collecting..." else "Ready",
                        message = if (status.recording) "" else "Place the phone firmly against the surface you want to measure (seat rail, console, floor) before starting.",
                    )
                } else {
                    LineChart(series = listOf(s), xLabel = "Frequency (Hz)", yLabel = "PSD (dB)", xValueFormatter = { formatHz(it) })
                }
            }
        }

        Button(
            onClick = {
                if (status.recording) {
                    val run = recorder.stop()
                    if (run == null)
                        vm.message("Recording too short to keep")
                    else
                        pending = if (tapMode) run.copy(kind = ExperimentKind.TAP) else run
                } else if (!recorder.start()) {
                    vm.message("Could not start the accelerometer")
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = if (status.recording) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
        ) {
            Icon(if (status.recording) Icons.Default.Stop else Icons.Default.FiberManualRecord, contentDescription = null)
            Text(if (status.recording) "  Stop" else "  Start recording", style = MaterialTheme.typography.titleMedium)
        }
    }

    pending?.let { run ->
        var name by remember { mutableStateOf((if (tapMode) "Tap " else "") + recorder.suggestedName()) }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Save recording") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${formatCount(run.series.size)} samples over ${formatDuration(run.series.duration)}")
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.addRecording(name.ifBlank { recorder.suggestedName() }, run)
                    pending = null
                    onSaved()
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Discard") } },
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
