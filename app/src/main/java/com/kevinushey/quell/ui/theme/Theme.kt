package com.kevinushey.quell.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.kevinushey.quell.data.ThemeMode

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00658E),
    secondary = Color(0xFF4F616E),
    tertiary = Color(0xFF63597C),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF85CFFF),
    secondary = Color(0xFFB6C9D8),
    tertiary = Color(0xFFCDC0E9),
)

@Composable
fun QuellTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** Categorical palette for overlaid runs; chosen to stay distinct on light and dark backgrounds. */
val SeriesPalette = listOf(
    Color(0xFF4E79A7),
    Color(0xFFF28E2B),
    Color(0xFFE15759),
    Color(0xFF76B7B2),
    Color(0xFF59A14F),
    Color(0xFFEDC948),
    Color(0xFFB07AA1),
    Color(0xFFFF9DA7),
    Color(0xFF9C755F),
    Color(0xFFBAB0AC),
    Color(0xFF1B9E77),
    Color(0xFF7570B3),
)

fun seriesColor(index: Int): Color = SeriesPalette[Math.floorMod(index, SeriesPalette.size)]
