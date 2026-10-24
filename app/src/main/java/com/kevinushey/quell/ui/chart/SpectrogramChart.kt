package com.kevinushey.quell.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevinushey.quell.dsp.SpectrogramData
import kotlin.math.roundToInt

/** Time-frequency heat map. The bitmap is built once per data/frequency range and scaled on draw. */
@Composable
fun SpectrogramChart(
    data: SpectrogramData,
    maxFreq: Double,
    modifier: Modifier = Modifier,
    dynamicRangeDb: Double = 60.0,
) {
    val textMeasurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val labelStyle = TextStyle(color = colors.onSurfaceVariant, fontSize = 10.sp)
    val titleStyle = TextStyle(color = colors.onSurface, fontSize = 11.sp)

    val binLimit = remember(data, maxFreq) {
        data.freq.indexOfLast { it <= maxFreq }.coerceAtLeast(1)
    }
    val bitmap: ImageBitmap? = remember(data, binLimit, dynamicRangeDb) { buildBitmap(data, binLimit, dynamicRangeDb) }

    Canvas(modifier = modifier.fillMaxSize()) {
        val leftPad = 52.dp.toPx()
        val bottomPad = 36.dp.toPx()
        val topPad = 10.dp.toPx()
        val rightPad = 44.dp.toPx()
        val rect = Rect(leftPad, topPad, size.width - rightPad, size.height - bottomPad)

        if (bitmap == null || data.times.isEmpty()) {
            val layout = textMeasurer.measure("Recording too short for a spectrogram", titleStyle)
            drawText(layout, topLeft = Offset((size.width - layout.size.width) / 2, (size.height - layout.size.height) / 2))
            return@Canvas
        }

        drawImage(
            image = bitmap,
            srcSize = IntSize(bitmap.width, bitmap.height),
            dstOffset = androidx.compose.ui.unit.IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
            dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
        )
        drawRect(colors.onSurfaceVariant.copy(alpha = 0.6f), topLeft = rect.topLeft, size = Size(rect.width, rect.height), style = Stroke(1f))

        // Time axis. Each bitmap column is centred on its frame time and spans one
        // frame spacing, so the axis runs from half a spacing before the first
        // centre to half a spacing after the last.
        val tMin = data.times.first() - data.frameSpacing / 2
        val tMax = data.times.last() + data.frameSpacing / 2
        for (t in Axis.linearTicks(tMin, tMax, (rect.width / 70.dp.toPx()).toInt().coerceIn(2, 10))) {
            val px = rect.left + ((t.value - tMin) / (tMax - tMin) * rect.width).toFloat()
            drawLine(colors.onSurfaceVariant.copy(alpha = 0.5f), Offset(px, rect.bottom), Offset(px, rect.bottom + 4.dp.toPx()), 1f)
            val layout = textMeasurer.measure(t.label ?: "", labelStyle)
            drawText(layout, topLeft = Offset(px - layout.size.width / 2, rect.bottom + 5.dp.toPx()))
        }
        // Frequency axis.
        val fMax = data.freq[binLimit]
        for (t in Axis.linearTicks(0.0, fMax, (rect.height / 40.dp.toPx()).toInt().coerceIn(2, 10))) {
            val py = rect.bottom - (t.value / fMax * rect.height).toFloat()
            drawLine(colors.onSurfaceVariant.copy(alpha = 0.5f), Offset(rect.left - 4.dp.toPx(), py), Offset(rect.left, py), 1f)
            val layout = textMeasurer.measure(t.label ?: "", labelStyle)
            drawText(layout, topLeft = Offset(rect.left - layout.size.width - 6.dp.toPx(), py - layout.size.height / 2))
        }

        // Color bar.
        val barLeft = rect.right + 10.dp.toPx()
        val barW = 10.dp.toPx()
        val steps = 64
        for (i in 0 until steps) {
            val frac = 1.0 - i.toDouble() / steps
            val y0 = rect.top + rect.height * i / steps
            drawRect(viridis(frac), topLeft = Offset(barLeft, y0), size = Size(barW, rect.height / steps + 1f))
        }
        val top = textMeasurer.measure("%.0f".format(java.util.Locale.US, data.maxDb), labelStyle)
        val bottom = textMeasurer.measure("%.0f".format(java.util.Locale.US, data.maxDb - dynamicRangeDb), labelStyle)
        drawText(top, topLeft = Offset(barLeft + barW + 2.dp.toPx(), rect.top))
        drawText(bottom, topLeft = Offset(barLeft + barW + 2.dp.toPx(), rect.bottom - bottom.size.height))

        val xl = textMeasurer.measure("Time (s)", titleStyle)
        drawText(xl, topLeft = Offset(rect.center.x - xl.size.width / 2, size.height - xl.size.height - 1.dp.toPx()))
        val yl = textMeasurer.measure("Frequency (Hz)", titleStyle)
        rotate(-90f, pivot = Offset(0f, 0f)) {
            drawText(yl, topLeft = Offset(-rect.center.y - yl.size.width / 2, 2.dp.toPx()))
        }
    }
}

private fun buildBitmap(data: SpectrogramData, binLimit: Int, dynamicRangeDb: Double): ImageBitmap? {
    val frames = data.times.size
    if (frames == 0)
        return null

    val w = frames
    val h = binLimit
    val pixels = IntArray(w * h)
    val hi = data.maxDb
    val lo = hi - dynamicRangeDb
    for (x in 0 until w) {
        val row = data.db[x]
        for (k in 1..binLimit) {
            val v = ((row[k] - lo) / (hi - lo)).coerceIn(0.0, 1.0)
            val y = binLimit - k
            pixels[y * w + x] = viridisArgb(v)
        }
    }
    val bmp = android.graphics.Bitmap.createBitmap(pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888)
    return bmp.asImageBitmap()
}

private val VIRIDIS_STOPS = arrayOf(
    intArrayOf(68, 1, 84),
    intArrayOf(72, 40, 120),
    intArrayOf(62, 74, 137),
    intArrayOf(49, 104, 142),
    intArrayOf(38, 130, 142),
    intArrayOf(31, 158, 137),
    intArrayOf(53, 183, 121),
    intArrayOf(109, 205, 89),
    intArrayOf(180, 222, 44),
    intArrayOf(253, 231, 37),
)

private fun viridisArgb(t: Double): Int {
    val pos = t.coerceIn(0.0, 1.0) * (VIRIDIS_STOPS.size - 1)
    val i = pos.toInt().coerceAtMost(VIRIDIS_STOPS.size - 2)
    val f = pos - i
    val a = VIRIDIS_STOPS[i]
    val b = VIRIDIS_STOPS[i + 1]
    val r = (a[0] + (b[0] - a[0]) * f).roundToInt()
    val g = (a[1] + (b[1] - a[1]) * f).roundToInt()
    val bl = (a[2] + (b[2] - a[2]) * f).roundToInt()
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
}

private fun viridis(t: Double): Color = Color(viridisArgb(t))
