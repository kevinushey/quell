package com.kevinushey.quell.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.kevinushey.quell.ui.ChartExportHost
import androidx.compose.ui.unit.dp

data class LegendItem(val label: String, val color: Color, val dashed: Boolean = false)

/**
 * Hosts a chart plus its legend. "Share as image" screen-captures this
 * card's window bounds, so the export matches the screen exactly without
 * touching the draw path.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartCard(
    legend: List<LegendItem>,
    exportHost: ChartExportHost?,
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: (@Composable () -> Unit)? = null,
    chart: @Composable () -> Unit,
) {
    val bg = MaterialTheme.colorScheme.surface
    // Clear the registration when this card leaves composition, but only if it is still ours:
    // another card on the same screen may have taken over in the meantime.
    val mine = remember { arrayOfNulls<Rect>(1) }
    DisposableEffect(exportHost) {
        onDispose {
            if (exportHost != null && exportHost.bounds == mine[0])
                exportHost.bounds = null
        }
    }
    Column(
        modifier = modifier
            .then(
                if (exportHost != null) Modifier.onGloballyPositioned {
                    val b = it.boundsInWindow()
                    mine[0] = b
                    exportHost.bounds = b
                } else Modifier
            )
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        }
        if (legend.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (item in legend) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(item.color))
                        Text(
                            item.label,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 4.dp),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) { chart() }
        footer?.invoke()
    }
}
