package com.kevinushey.quell.dsp

enum class Channel(val label: String, val short: String) {
    X("X axis", "X"),
    Y("Y axis", "Y"),
    Z("Z axis", "Z"),
    MAGNITUDE("Magnitude |a|", "|a|"),
    TOTAL("Total (X+Y+Z power)", "XYZ");

    val description: String
        get() = when (this) {
            X, Y, Z -> "Single sensor axis"
            MAGNITUDE -> "Vector magnitude; orientation independent but mixes axes nonlinearly"
            TOTAL -> "Sum of per-axis power spectra; orientation independent and linear"
        }
}
