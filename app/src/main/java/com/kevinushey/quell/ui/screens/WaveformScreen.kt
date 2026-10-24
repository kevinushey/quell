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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.delay
import com.kevinushey.quell.ui.chart.computeBounds
import com.kevinushey.quell.ui.chart.AxisScale
import com.kevinushey.quell.ui.chart.Viewport
import androidx.compose.runtime.remember
import com.kevinushey.quell.dsp.Decimate
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevinushey.quell.data.DatasetMeta
import com.kevinushey.quell.dsp.Channel
import com.kevinushey.quell.ui.ChartExportHost
import com.kevinushey.quell.ui.MainViewModel
import com.kevinushey.quell.ui.chart.ChartSeries
import com.kevinushey.quell.ui.chart.LineChart
import com.kevinushey.quell.ui.chart.VerticalMarker
import com.kevinushey.quell.ui.components.ChartCard
import com.kevinushey.quell.ui.components.EmptyState
import com.kevinushey.quell.ui.components.LegendItem
import com.kevinushey.quell.ui.theme.seriesColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private val WAVEFORM_CHANNELS = listOf(Channel.X, Channel.Y, Channel.Z, Channel.MAGNITUDE)

@Composable
fun WaveformScreen(vm: MainViewModel, exportHost: ChartExportHost) {
    val datasets by vm.datasets.collectAsStateWithLifecycle()
    var channel by rememberSaveable { mutableStateOf(Channel.MAGNITUDE) }
    var removeMean by rememberSaveable { mutableStateOf(true) }

    DisposableEffect(Unit) {
        exportHost.name = "waveform"
        onDispose { exportHost.bounds = null }
    }

    val visible = datasets.filter { it.visible }
    if (visible.isEmpty()) {
        EmptyState(Icons.Default.GraphicEq, "No visible datasets", "Enable datasets on the Datasets tab to see their raw acceleration over time.")
        return
    }

    // The chart holds a bounded envelope of whatever time range is on screen. Zooming narrows
    // the window and the envelope is rebuilt from the underlying samples at finer resolution.
    val visibleKey = visible.map { it.id to it.colorIndex to it.name }
    var window by remember(visibleKey, channel, removeMean) { mutableStateOf<Viewport?>(null) }
    var fullBounds by remember(visibleKey, channel, removeMean) { mutableStateOf<Viewport?>(null) }
    val series by produceState<List<ChartSeries>>(emptyList(), visibleKey, channel, removeMean, window) {
        val w = window
        if (w != null)
            delay(120)
        val built = withContext(Dispatchers.Default) { buildSeries(vm, visible, channel, removeMean, w) }
        if (w == null)
            fullBounds = computeBounds(built, AxisScale.LINEAR)
        value = built
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (c in WAVEFORM_CHANNELS)
                FilterChip(selected = channel == c, onClick = { channel = c }, label = { Text(c.short) })
            FilterChip(selected = removeMean, onClick = { removeMean = !removeMean }, label = { Text("Remove mean") })
        }
        ChartCard(
            legend = visible.map { LegendItem(it.name, seriesColor(it.colorIndex)) },
            exportHost = exportHost,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            footer = {
                Text(
                    "Pinch horizontally to zoom time only, vertically for amplitude only. Drag to pan, tap for values, double-tap to reset. Dashed lines mark a run's analysis window.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
            },
        ) {
            val trimMarkers = visible.filter { it.isTrimmed }.flatMap { m ->
                val c = seriesColor(m.colorIndex)
                listOf(VerticalMarker(m.trimStart, "start", c), VerticalMarker(m.effectiveTrimEnd, "end", c))
            }
            LineChart(
                series = series,
                xLabel = "Time (s)",
                yLabel = if (removeMean) "Acceleration minus mean (m/s^2)" else "Acceleration (m/s^2)",
                verticalMarkers = trimMarkers,
                viewportKey = listOf(channel, removeMean, visible.map { it.id }),
                fixedBounds = fullBounds,
                onViewportChange = { vp -> window = if (fullBounds == null || vp == fullBounds) null else vp },
                xValueFormatter = { "%.3f s".format(Locale.US, it) },
                yValueFormatter = { "%.3f".format(Locale.US, it) },
            )
        }
    }
}

private suspend fun buildSeries(
    vm: MainViewModel,
    visible: List<DatasetMeta>,
    channel: Channel,
    removeMean: Boolean,
    window: Viewport?,
): List<ChartSeries> {
    val out = ArrayList<ChartSeries>()
    for (meta in visible) {
        val s = vm.series(meta.id) ?: continue
        val raw = when (channel) {
            Channel.X -> s.x
            Channel.Y -> s.y
            Channel.Z -> s.z
            else -> s.magnitude()
        }
        val t0 = s.time.firstOrNull() ?: 0.0
        // The mean is a property of the whole run, not of the window on screen.
        val mean = if (removeMean) raw.average() else 0.0

        // Only the visible span (plus a margin for panning) is materialized, then bounded by decimation.
        var from = 0
        var to = s.size
        if (window != null) {
            val margin = window.width * 0.25
            from = lowerBound(s.time, t0 + window.xMin - margin)
            to = lowerBound(s.time, t0 + window.xMax + margin).coerceAtMost(s.size)
            if (to - from < 2) {
                from = (from - 1).coerceAtLeast(0)
                to = (to + 1).coerceAtMost(s.size)
            }
        }
        val time = DoubleArray(to - from) { s.time[from + it] - t0 }
        val values = DoubleArray(to - from) { raw[from + it] - mean }
        val d = Decimate.minMax(time, values)
        out.add(ChartSeries(meta.id, meta.name, seriesColor(meta.colorIndex), d.time, d.values, strokeWidthDp = 1.2f))
    }
    return out
}

/** First index whose time is >= target. */
private fun lowerBound(arr: DoubleArray, target: Double): Int {
    var lo = 0
    var hi = arr.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (arr[mid] < target)
            lo = mid + 1
        else
            hi = mid
    }
    return lo
}
