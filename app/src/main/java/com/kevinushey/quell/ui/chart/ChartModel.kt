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

/** Filled region between two curves, e.g. a one-sigma band; x must be sorted ascending. */
class ChartBand(
    val id: String,
    val color: Color,
    val x: DoubleArray,
    val yLow: DoubleArray,
    val yHigh: DoubleArray,
) {
    init {
        require(x.size == yLow.size && x.size == yHigh.size)
    }
}

/** Vertical whisker of +/- `error` around a point. */
data class ErrorBar(val x: Double, val y: Double, val error: Double, val color: Color)

/** Vertical reference line with a label, e.g. a wheel harmonic. */
data class VerticalMarker(val x: Double, val label: String, val color: Color, val dashed: Boolean = true)

/** Annotated point on a series, e.g. a spectral peak. */
data class PointMarker(val x: Double, val y: Double, val label: String, val color: Color)

data class Viewport(val xMin: Double, val xMax: Double, val yMin: Double, val yMax: Double) {
    val width: Double get() = xMax - xMin
    val height: Double get() = yMax - yMin
}

enum class AxisScale { LINEAR, LOG }
