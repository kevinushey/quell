package com.kevinushey.quell.ui

import java.util.Locale

fun formatDuration(sec: Double): String {
    if (sec < 60)
        return String.format(Locale.US, "%.1f s", sec)
    val total = Math.round(sec)
    return String.format(Locale.US, "%dm %02ds", total / 60, total % 60)
}

fun formatHz(hz: Double): String = when {
    hz >= 100 -> String.format(Locale.US, "%.0f Hz", hz)
    hz >= 10 -> String.format(Locale.US, "%.1f Hz", hz)
    else -> String.format(Locale.US, "%.2f Hz", hz)
}

fun formatSigned(v: Double, digits: Int = 1): String =
    String.format(Locale.US, "%+.${digits}f", v)

fun formatCount(n: Int): String = String.format(Locale.US, "%,d", n)
