package com.kevinushey.quell.ui.chart

import androidx.compose.ui.graphics.Color

/** x must be sorted ascending. */
class ChartSeries(
    val id: String,
    val label: String,
    val color: Color,
    val x: DoubleArray,
    val y: DoubleArray,
    val strokeWidthDp: Float = 1.8f,
    val dashed: Boolean = false,
) {
    init {
        require(x.size == y.size)
    }
}

/** Vertical reference line with a label, e.g. a wheel harmonic. */
data class VerticalMarker(val x: Double, val label: String, val color: Color, val dashed: Boolean = true)

/** Annotated point on a series, e.g. a spectral peak. */
data class PointMarker(val x: Double, val y: Double, val label: String, val color: Color)

data class Viewport(val xMin: Double, val xMax: Double, val yMin: Double, val yMax: Double) {
    val width: Double get() = xMax - xMin
    val height: Double get() = yMax - yMin
}

enum class AxisScale { LINEAR, LOG }
