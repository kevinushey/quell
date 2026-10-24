package com.kevinushey.quell.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Multi-series line chart with pinch zoom, drag pan, tap crosshair and
 * double-tap reset. Drawn entirely on a Canvas so exported images match the
 * screen exactly.
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    xLabel: String = "",
    yLabel: String = "",
    xScale: AxisScale = AxisScale.LINEAR,
    verticalMarkers: List<VerticalMarker> = emptyList(),
    pointMarkers: List<PointMarker> = emptyList(),
    showZeroLine: Boolean = false,
    /** Changing this key resets zoom and crosshair. */
    viewportKey: Any? = null,
    xValueFormatter: (Double) -> String = { Axis.formatValue(it) },
    yValueFormatter: (Double) -> String = { Axis.formatValue(it) },
) {
    val textMeasurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val labelStyle = TextStyle(color = colors.onSurfaceVariant, fontSize = 10.sp)
    val titleStyle = TextStyle(color = colors.onSurface, fontSize = 11.sp)
    val readoutStyle = TextStyle(color = colors.onSurface, fontSize = 11.sp)

    val dataBounds = remember(series, xScale) { computeBounds(series, xScale) }
    var viewport by remember(viewportKey, dataBounds) { mutableStateOf(dataBounds) }
    var crosshairX by remember(viewportKey) { mutableStateOf<Double?>(null) }
    var plotRect by remember { mutableStateOf(Rect.Zero) }

    // Zoom about the pinch centroid with independent x/y factors. The data edge may travel as far
    // as the plot centre in either axis; the empty part is hatched so it reads as "no data".
    val applyGesture: (Offset, Offset, Float, Float) -> Unit = { centroid, pan, zoomX, zoomY ->
        val vp = viewport
        val bounds = dataBounds
        val rect = plotRect
        if (vp != null && bounds != null && rect.width > 0 && rect.height > 0) {
            val fx = ((centroid.x - rect.left) / rect.width).coerceIn(0f, 1f).toDouble()
            val fy = ((rect.bottom - centroid.y) / rect.height).coerceIn(0f, 1f).toDouble()
            val zx = zoomX.toDouble().coerceIn(0.2, 5.0)
            val zy = zoomY.toDouble().coerceIn(0.2, 5.0)
            val newW = (vp.width / zx).coerceIn(bounds.width * 0.002, bounds.width * 2)
            val newH = (vp.height / zy).coerceIn(bounds.height * 0.002, bounds.height * 2)
            val anchorX = vp.xMin + fx * vp.width
            val anchorY = vp.yMin + fy * vp.height
            val dx = -pan.x / rect.width * newW
            val dy = pan.y / rect.height * newH
            val xMin = clampStart(anchorX - fx * newW + dx, bounds.xMin - newW / 2, bounds.xMax - newW / 2)
            val yMin = clampStart(anchorY - fy * newH + dy, bounds.yMin - newH / 2, bounds.yMax - newH / 2)
            viewport = Viewport(xMin, xMin + newW, yMin, yMin + newH)
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            // Keyed on viewportKey too: a new key replaces the viewport state, and the handlers must capture it.
            .pointerInput(dataBounds, viewportKey) {
                // Axis-aware pinch: the zoom factor is taken separately from the horizontal and
                // vertical finger separation, so a horizontal pinch rescales only the x axis.
                val slop = viewConfiguration.touchSlop
                val minSpan = 24.dp.toPx()
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var panning = false
                    var accumulated = Offset.Zero
                    var prevCentroid: Offset? = null
                    var prevSpanX = 0f
                    var prevSpanY = 0f
                    var prevCount = 0
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty())
                            break
                        val centroid = pressed.fold(Offset.Zero) { acc, c -> acc + c.position } / pressed.size.toFloat()
                        var spanX = 0f
                        var spanY = 0f
                        if (pressed.size >= 2) {
                            spanX = abs(pressed[0].position.x - pressed[1].position.x)
                            spanY = abs(pressed[0].position.y - pressed[1].position.y)
                        }
                        if (prevCentroid == null || prevCount != pressed.size) {
                            // Finger count changed: restart the reference frame without jumping.
                            prevCentroid = centroid
                            prevSpanX = spanX
                            prevSpanY = spanY
                            prevCount = pressed.size
                            continue
                        }
                        val pan = centroid - prevCentroid
                        if (!panning) {
                            accumulated += pan
                            if (pressed.size >= 2 || accumulated.getDistance() > slop)
                                panning = true
                        }
                        if (panning) {
                            val zx = if (pressed.size >= 2 && spanX > minSpan && prevSpanX > minSpan) spanX / prevSpanX else 1f
                            val zy = if (pressed.size >= 2 && spanY > minSpan && prevSpanY > minSpan) spanY / prevSpanY else 1f
                            applyGesture(centroid, pan, zx, zy)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                        prevCentroid = centroid
                        prevSpanX = spanX
                        prevSpanY = spanY
                    }
                }
            }
            .pointerInput(dataBounds, viewportKey) {
                detectTapGestures(
                    onDoubleTap = {
                        viewport = dataBounds
                        crosshairX = null
                    },
                    onTap = { pos ->
                        val vp = viewport ?: return@detectTapGestures
                        val rect = plotRect
                        if (!rect.contains(pos)) {
                            crosshairX = null
                            return@detectTapGestures
                        }
                        val xd = vp.xMin + (pos.x - rect.left) / rect.width * vp.width
                        crosshairX = if (crosshairX != null && abs(crosshairX!! - xd) < vp.width * 0.01) null else xd
                    },
                )
            }
    ) {
        val vp = viewport
        if (vp == null || series.isEmpty()) {
            val msg = "No data"
            val layout = textMeasurer.measure(msg, titleStyle)
            drawText(layout, topLeft = Offset((size.width - layout.size.width) / 2, (size.height - layout.size.height) / 2))
            return@Canvas
        }

        val leftPad = 52.dp.toPx()
        val bottomPad = 36.dp.toPx()
        val topPad = 10.dp.toPx()
        val rightPad = 12.dp.toPx()
        val rect = Rect(leftPad, topPad, size.width - rightPad, size.height - bottomPad)
        plotRect = rect

        drawAxesAndGrid(rect, vp, xScale, textMeasurer, labelStyle, titleStyle, xLabel, yLabel, colors.outlineVariant, colors.onSurfaceVariant)

        clipRect(rect.left, rect.top, rect.right, rect.bottom) {
            dataBounds?.let { drawOutOfRange(rect, vp, it, xScale, textMeasurer, labelStyle, colors.onSurfaceVariant, xValueFormatter) }

            if (showZeroLine && vp.yMin < 0 && vp.yMax > 0) {
                val y0 = rect.bottom - ((0.0 - vp.yMin) / vp.height * rect.height).toFloat()
                drawLine(colors.onSurfaceVariant, Offset(rect.left, y0), Offset(rect.right, y0), strokeWidth = 1.dp.toPx())
            }

            for (m in verticalMarkers) {
                val xv = toDomain(m.x, xScale)
                if (xv < vp.xMin || xv > vp.xMax)
                    continue
                val px = rect.left + ((xv - vp.xMin) / vp.width * rect.width).toFloat()
                drawLine(
                    m.color,
                    Offset(px, rect.top),
                    Offset(px, rect.bottom),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = if (m.dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null,
                )
                val layout = textMeasurer.measure(m.label, TextStyle(color = m.color, fontSize = 10.sp))
                drawText(layout, topLeft = Offset(px + 3.dp.toPx(), rect.top + 2.dp.toPx()))
            }

            for (s in series)
                drawSeries(s, rect, vp, xScale)

            // Tallest markers get labelled first; later labels that would overlap are skipped (the dot still shows).
            val placedLabels = ArrayList<Rect>()
            for (pm in pointMarkers.sortedByDescending { it.y }) {
                val xv = toDomain(pm.x, xScale)
                if (xv < vp.xMin || xv > vp.xMax || pm.y < vp.yMin || pm.y > vp.yMax)
                    continue
                val px = rect.left + ((xv - vp.xMin) / vp.width * rect.width).toFloat()
                val py = rect.bottom - ((pm.y - vp.yMin) / vp.height * rect.height).toFloat()
                drawCircle(pm.color, radius = 3.5.dp.toPx(), center = Offset(px, py))
                drawCircle(colors.surface, radius = 3.5.dp.toPx(), center = Offset(px, py), style = Stroke(1.dp.toPx()))

                val layout = textMeasurer.measure(pm.label, TextStyle(color = pm.color, fontSize = 10.sp))
                val tx = (px - layout.size.width / 2).coerceIn(rect.left, rect.right - layout.size.width)
                val ty = (py - layout.size.height - 5.dp.toPx()).coerceAtLeast(rect.top)
                val labelRect = Rect(tx, ty, tx + layout.size.width, ty + layout.size.height)
                if (placedLabels.any { it.overlaps(labelRect) })
                    continue
                placedLabels.add(labelRect)
                drawText(layout, topLeft = Offset(tx, ty))
            }
        }

        crosshairX?.let { cx ->
            drawCrosshair(cx, series, rect, vp, xScale, textMeasurer, readoutStyle, colors.surfaceContainerHighest, colors.onSurface, xValueFormatter, yValueFormatter)
        }
    }
}

/** Hatches the parts of the plot that lie beyond the first or last data point. */
private fun DrawScope.drawOutOfRange(
    rect: Rect,
    vp: Viewport,
    bounds: Viewport,
    xScale: AxisScale,
    textMeasurer: TextMeasurer,
    style: TextStyle,
    color: Color,
    xFormatter: (Double) -> String,
) {
    val sx = rect.width / vp.width
    val sy = rect.height / vp.height

    // Each region: its rectangle, the edge line to draw, and an optional label.
    class Region(val area: Rect, val edgeStart: Offset, val edgeEnd: Offset, val label: String?)
    val regions = ArrayList<Region>()
    if (vp.xMin < bounds.xMin) {
        val edge = (rect.left + ((bounds.xMin - vp.xMin) * sx).toFloat()).coerceAtMost(rect.right)
        regions.add(Region(Rect(rect.left, rect.top, edge, rect.bottom), Offset(edge, rect.top), Offset(edge, rect.bottom), "No data below ${xFormatter(fromDomain(bounds.xMin, xScale))}"))
    }
    if (vp.xMax > bounds.xMax) {
        val edge = (rect.left + ((bounds.xMax - vp.xMin) * sx).toFloat()).coerceAtLeast(rect.left)
        regions.add(Region(Rect(edge, rect.top, rect.right, rect.bottom), Offset(edge, rect.top), Offset(edge, rect.bottom), "No data past ${xFormatter(fromDomain(bounds.xMax, xScale))}"))
    }
    if (vp.yMax > bounds.yMax) {
        val edge = (rect.bottom - ((bounds.yMax - vp.yMin) * sy).toFloat()).coerceAtLeast(rect.top)
        regions.add(Region(Rect(rect.left, rect.top, rect.right, edge), Offset(rect.left, edge), Offset(rect.right, edge), null))
    }
    if (vp.yMin < bounds.yMin) {
        val edge = (rect.bottom - ((bounds.yMin - vp.yMin) * sy).toFloat()).coerceAtMost(rect.bottom)
        regions.add(Region(Rect(rect.left, edge, rect.right, rect.bottom), Offset(rect.left, edge), Offset(rect.right, edge), null))
    }

    val spacing = 12.dp.toPx()
    for (r in regions) {
        val a = r.area
        if (a.width < 1f || a.height < 1f)
            continue
        drawRect(color.copy(alpha = 0.06f), topLeft = a.topLeft, size = a.size)
        clipRect(a.left, a.top, a.right, a.bottom) {
            // 45-degree stripes anchored to the plot so they do not crawl while panning.
            var x = rect.left - rect.height
            while (x < rect.right) {
                drawLine(color.copy(alpha = 0.18f), Offset(x, rect.bottom), Offset(x + rect.height, rect.top), 1.dp.toPx())
                x += spacing
            }
        }
        drawLine(color.copy(alpha = 0.6f), r.edgeStart, r.edgeEnd, 1.dp.toPx())

        val label = r.label ?: continue
        val layout = textMeasurer.measure(label, style)
        if (a.width > layout.size.width + 8.dp.toPx()) {
            val lx = a.left + (a.width - layout.size.width) / 2
            val ly = a.top + 8.dp.toPx()
            drawRoundRect(color.copy(alpha = 0.12f), topLeft = Offset(lx - 4.dp.toPx(), ly - 2.dp.toPx()), size = Size(layout.size.width + 8.dp.toPx(), layout.size.height + 4.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
            drawText(layout, topLeft = Offset(lx, ly))
        }
    }
}

/** Like coerceIn, but tolerant of lo > hi by a rounding error when the window spans the whole data range. */
private fun clampStart(v: Double, lo: Double, hi: Double): Double = if (hi <= lo) lo else v.coerceIn(lo, hi)

private fun toDomain(x: Double, scale: AxisScale): Double =
    if (scale == AxisScale.LOG) log10(x.coerceAtLeast(1e-12)) else x

private fun fromDomain(v: Double, scale: AxisScale): Double =
    if (scale == AxisScale.LOG) 10.0.pow(v) else v

private fun computeBounds(series: List<ChartSeries>, xScale: AxisScale): Viewport? {
    var xMin = Double.POSITIVE_INFINITY
    var xMax = Double.NEGATIVE_INFINITY
    var yMin = Double.POSITIVE_INFINITY
    var yMax = Double.NEGATIVE_INFINITY
    for (s in series) {
        for (i in s.x.indices) {
            val xv = s.x[i]
            val yv = s.y[i]
            if (xScale == AxisScale.LOG && xv <= 0)
                continue
            if (yv.isNaN() || yv.isInfinite())
                continue
            val xd = toDomain(xv, xScale)
            if (xd < xMin)
                xMin = xd
            if (xd > xMax)
                xMax = xd
            if (yv < yMin)
                yMin = yv
            if (yv > yMax)
                yMax = yv
        }
    }
    if (xMin.isInfinite() || yMin.isInfinite())
        return null
    if (xMax <= xMin) {
        xMin -= 1.0
        xMax += 1.0
    }
    if (yMax <= yMin) {
        yMin -= 1.0
        yMax += 1.0
    }
    val yPad = (yMax - yMin) * 0.06
    return Viewport(xMin, xMax, yMin - yPad, yMax + yPad)
}

private fun DrawScope.drawAxesAndGrid(
    rect: Rect,
    vp: Viewport,
    xScale: AxisScale,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    titleStyle: TextStyle,
    xLabel: String,
    yLabel: String,
    gridColor: Color,
    axisColor: Color,
) {
    val gridStroke = 1f
    val xTicks = if (xScale == AxisScale.LOG)
        Axis.logTicks(fromDomain(vp.xMin, xScale), fromDomain(vp.xMax, xScale))
    else
        Axis.linearTicks(vp.xMin, vp.xMax, (rect.width / 48.dp.toPx()).toInt().coerceIn(3, 14))
    val yTicks = Axis.linearTicks(vp.yMin, vp.yMax, (rect.height / 40.dp.toPx()).toInt().coerceIn(2, 10))

    var lastLabelRight = Float.NEGATIVE_INFINITY
    for (t in xTicks) {
        val xd = toDomain(t.value, xScale)
        val px = rect.left + ((xd - vp.xMin) / vp.width * rect.width).toFloat()
        if (px < rect.left - 0.5f || px > rect.right + 0.5f)
            continue
        drawLine(gridColor.copy(alpha = if (t.major) 0.9f else 0.4f), Offset(px, rect.top), Offset(px, rect.bottom), gridStroke)
        val label = t.label ?: continue
        val layout = textMeasurer.measure(label, labelStyle)
        val left = px - layout.size.width / 2
        if (left > lastLabelRight + 4.dp.toPx()) {
            drawText(layout, topLeft = Offset(left, rect.bottom + 4.dp.toPx()))
            lastLabelRight = left + layout.size.width
        }
    }

    for (t in yTicks) {
        val py = rect.bottom - ((t.value - vp.yMin) / vp.height * rect.height).toFloat()
        if (py < rect.top - 0.5f || py > rect.bottom + 0.5f)
            continue
        drawLine(gridColor, Offset(rect.left, py), Offset(rect.right, py), gridStroke)
        val layout = textMeasurer.measure(t.label ?: "", labelStyle)
        drawText(layout, topLeft = Offset(rect.left - layout.size.width - 4.dp.toPx(), py - layout.size.height / 2))
    }

    drawRect(axisColor.copy(alpha = 0.6f), topLeft = rect.topLeft, size = Size(rect.width, rect.height), style = Stroke(1f))

    if (xLabel.isNotEmpty()) {
        val layout = textMeasurer.measure(xLabel, titleStyle)
        drawText(layout, topLeft = Offset(rect.center.x - layout.size.width / 2, size.height - layout.size.height - 1.dp.toPx()))
    }
    if (yLabel.isNotEmpty()) {
        val layout = textMeasurer.measure(yLabel, titleStyle)
        rotate(-90f, pivot = Offset(0f, 0f)) {
            drawText(layout, topLeft = Offset(-rect.center.y - layout.size.width / 2, 2.dp.toPx()))
        }
    }
}

private fun DrawScope.drawSeries(s: ChartSeries, rect: Rect, vp: Viewport, xScale: AxisScale) {
    val n = s.x.size
    if (n == 0)
        return

    // Only walk the points inside the viewport (x is sorted), with one bin of slack either side.
    val xMinLin = fromDomain(vp.xMin, xScale)
    val xMaxLin = fromDomain(vp.xMax, xScale)
    var start = lowerBound(s.x, xMinLin) - 1
    var end = lowerBound(s.x, xMaxLin) + 1
    start = start.coerceIn(0, n - 1)
    end = end.coerceIn(0, n - 1)
    if (end <= start)
        return

    val sx = rect.width / vp.width
    val sy = rect.height / vp.height
    val path = Path()
    val count = end - start + 1
    val pxWidth = rect.width.toInt().coerceAtLeast(1)

    if (count <= pxWidth * 2) {
        var started = false
        for (i in start..end) {
            val xv = s.x[i]
            val yv = s.y[i]
            if (xScale == AxisScale.LOG && xv <= 0 || yv.isNaN()) {
                started = false
                continue
            }
            val px = rect.left + ((toDomain(xv, xScale) - vp.xMin) * sx).toFloat()
            val py = rect.bottom - ((yv - vp.yMin) * sy).toFloat()
            if (!started) {
                path.moveTo(px, py)
                started = true
            } else {
                path.lineTo(px, py)
            }
        }
    } else {
        // Dense data: min/max per pixel column keeps peaks visible without drawing every sample.
        var started = false
        var i = start
        while (i <= end) {
            val xv = s.x[i]
            if (xScale == AxisScale.LOG && xv <= 0) {
                i++
                continue
            }
            val px = rect.left + ((toDomain(xv, xScale) - vp.xMin) * sx).toFloat()
            val col = px.toInt()
            var lo = Double.POSITIVE_INFINITY
            var hi = Double.NEGATIVE_INFINITY
            var first = Double.NaN
            var last = Double.NaN
            while (i <= end) {
                val xi = s.x[i]
                val pxi = rect.left + ((toDomain(xi, xScale) - vp.xMin) * sx).toFloat()
                if (pxi.toInt() != col)
                    break
                val yv = s.y[i]
                if (!yv.isNaN()) {
                    if (first.isNaN())
                        first = yv
                    last = yv
                    if (yv < lo)
                        lo = yv
                    if (yv > hi)
                        hi = yv
                }
                i++
            }
            if (first.isNaN())
                continue
            val cx = col + 0.5f
            val pyFirst = rect.bottom - ((first - vp.yMin) * sy).toFloat()
            val pyLo = rect.bottom - ((lo - vp.yMin) * sy).toFloat()
            val pyHi = rect.bottom - ((hi - vp.yMin) * sy).toFloat()
            val pyLast = rect.bottom - ((last - vp.yMin) * sy).toFloat()
            if (!started) {
                path.moveTo(cx, pyFirst)
                started = true
            } else {
                path.lineTo(cx, pyFirst)
            }
            path.lineTo(cx, pyHi)
            path.lineTo(cx, pyLo)
            path.lineTo(cx, pyLast)
        }
    }

    drawPath(
        path,
        color = s.color,
        style = Stroke(
            width = s.strokeWidthDp.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null,
        ),
    )
}

private fun DrawScope.drawCrosshair(
    cx: Double,
    series: List<ChartSeries>,
    rect: Rect,
    vp: Viewport,
    xScale: AxisScale,
    textMeasurer: TextMeasurer,
    style: TextStyle,
    boxColor: Color,
    lineColor: Color,
    xFormatter: (Double) -> String,
    yFormatter: (Double) -> String,
) {
    if (cx < vp.xMin || cx > vp.xMax)
        return
    val px = rect.left + ((cx - vp.xMin) / vp.width * rect.width).toFloat()
    drawLine(lineColor.copy(alpha = 0.7f), Offset(px, rect.top), Offset(px, rect.bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))

    val xLin = fromDomain(cx, xScale)
    val lines = ArrayList<Pair<Color, String>>()
    lines.add(Color.Unspecified to xFormatter(xLin))
    for (s in series) {
        if (s.x.isEmpty())
            continue
        // Outside a series' recorded range there is nothing to read out.
        if (xLin < s.x.first() || xLin > s.x.last())
            continue
        val idx = nearestIndex(s.x, xLin)
        val yv = s.y[idx]
        if (yv.isNaN())
            continue
        if (yv >= vp.yMin && yv <= vp.yMax) {
            val py = rect.bottom - ((yv - vp.yMin) / vp.height * rect.height).toFloat()
            drawCircle(s.color, radius = 4.dp.toPx(), center = Offset(px, py))
        }
        lines.add(s.color to "${s.label}: ${yFormatter(yv)}")
    }

    val layouts = lines.map { (c, t) ->
        textMeasurer.measure(t, if (c == Color.Unspecified) style.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) else style.copy(color = c))
    }
    val pad = 6.dp.toPx()
    val w = (layouts.maxOf { it.size.width } + pad * 2).coerceAtMost(rect.width)
    val h = layouts.sumOf { it.size.height } + pad * 2
    val bx = if (px + 10.dp.toPx() + w < rect.right) px + 10.dp.toPx() else max(rect.left, px - 10.dp.toPx() - w)
    val by = rect.top + 6.dp.toPx()
    drawRoundRect(boxColor.copy(alpha = 0.92f), topLeft = Offset(bx, by), size = Size(w, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
    var y = by + pad
    for (l in layouts) {
        drawText(l, topLeft = Offset(bx + pad, y))
        y += l.size.height
    }
}

/** First index whose value is >= target. */
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

private fun nearestIndex(arr: DoubleArray, target: Double): Int {
    val i = lowerBound(arr, target)
    if (i <= 0)
        return 0
    if (i >= arr.size)
        return arr.size - 1
    return if (abs(arr[i] - target) < abs(arr[i - 1] - target)) i else i - 1
}
