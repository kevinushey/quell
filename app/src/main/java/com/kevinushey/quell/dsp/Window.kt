package com.kevinushey.quell.dsp

import kotlin.math.PI
import kotlin.math.cos

enum class WindowType(val label: String) {
    HANN("Hann"),
    HAMMING("Hamming"),
    BLACKMAN("Blackman"),
    RECTANGULAR("Rectangular");

    fun coefficients(n: Int): DoubleArray {
        val w = DoubleArray(n)
        val denom = (n - 1).coerceAtLeast(1).toDouble()
        for (i in 0 until n) {
            val x = 2.0 * PI * i / denom
            w[i] = when (this) {
                HANN -> 0.5 - 0.5 * cos(x)
                HAMMING -> 0.54 - 0.46 * cos(x)
                BLACKMAN -> 0.42 - 0.5 * cos(x) + 0.08 * cos(2 * x)
                RECTANGULAR -> 1.0
            }
        }
        return w
    }
}
