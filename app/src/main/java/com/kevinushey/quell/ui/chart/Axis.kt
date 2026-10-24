package com.kevinushey.quell.ui.chart

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

data class Tick(val value: Double, val label: String?, val major: Boolean)

object Axis {

    /** Ticks at "nice" multiples (1, 2, 5 x 10^k) covering [min, max]. */
    fun linearTicks(min: Double, max: Double, targetCount: Int): List<Tick> {
        if (!(max > min) || targetCount < 1)
            return emptyList()

        val rawStep = (max - min) / targetCount
        val mag = 10.0.pow(floor(log10(rawStep)))
        val residual = rawStep / mag
        val step = when {
            residual <= 1.0 -> 1.0
            residual <= 2.0 -> 2.0
            residual <= 5.0 -> 5.0
            else -> 10.0
        } * mag

        val first = ceil(min / step) * step
        val ticks = ArrayList<Tick>()
        var v = first
        var guard = 0
        while (v <= max + step * 1e-6 && guard++ < 1000) {
            val snapped = if (abs(v) < step * 1e-9) 0.0 else v
            ticks.add(Tick(snapped, formatNumber(snapped, step), true))
            v += step
        }
        return ticks
    }

    /** Ticks for a log10 axis. Values are in linear units; `min`/`max` are linear too. */
    fun logTicks(min: Double, max: Double): List<Tick> {
        if (!(max > min) || min <= 0)
            return emptyList()

        val lo = floor(log10(min)).toInt()
        val hi = ceil(log10(max)).toInt()
        val decades = log10(max) - log10(min)
        val ticks = ArrayList<Tick>()
        for (e in lo..hi) {
            val base = 10.0.pow(e)
            for (m in 1..9) {
                val v = m * base
                if (v < min * 0.999 || v > max * 1.001)
                    continue
                val major = m == 1
                val labelled = major || (decades < 3 && (m == 2 || m == 5)) || (decades < 1.2)
                ticks.add(Tick(v, if (labelled) formatNumber(v, v) else null, major))
            }
        }
        return ticks
    }

    fun formatNumber(v: Double, step: Double): String {
        if (v == 0.0)
            return "0"
        val a = abs(v)
        if (a >= 1e5 || a < 1e-3)
            return "%.1e".format(java.util.Locale.US, v)

        val decimals = when {
            step >= 1 -> 0
            step >= 0.1 -> 1
            step >= 0.01 -> 2
            else -> 3
        }
        return if (decimals == 0) v.roundToInt().toString() else "%.${decimals}f".format(java.util.Locale.US, v)
    }

    fun formatValue(v: Double): String {
        if (v.isNaN())
            return "--"
        val a = abs(v)
        return when {
            a == 0.0 -> "0"
            a >= 1e4 || a < 1e-3 -> "%.2e".format(java.util.Locale.US, v)
            a >= 100 -> "%.1f".format(java.util.Locale.US, v)
            a >= 1 -> "%.2f".format(java.util.Locale.US, v)
            else -> "%.3f".format(java.util.Locale.US, v)
        }
    }
}
