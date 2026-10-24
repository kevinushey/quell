package com.kevinushey.quell.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevinushey.quell.data.AnalysisSettings
import com.kevinushey.quell.data.ThemeMode
import com.kevinushey.quell.ui.MainViewModel

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val app by vm.appSettings.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mode in ThemeMode.entries) {
                FilterChip(
                    selected = app.theme == mode,
                    onClick = { vm.updateAppSettings { it.copy(theme = mode) } },
                    label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }
        SwitchRow("Dynamic color", "Use wallpaper-based colors on Android 12+", app.dynamicColor) { on ->
            vm.updateAppSettings { it.copy(dynamicColor = on) }
        }

        HorizontalDivider()
        Text("Units", style = MaterialTheme.typography.titleMedium)
        SwitchRow("Speed in mph", "Used by the wheel order markers", app.useMph) { on ->
            vm.updateAppSettings { it.copy(useMph = on) }
        }

        HorizontalDivider()
        Text("Analysis", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { vm.updateAnalysis { AnalysisSettings() } }) { Text("Reset analysis options to defaults") }

        HorizontalDivider()
        Text("About", style = MaterialTheme.typography.titleMedium)
        Text(
            """
            Spectra are estimated with Welch's method: the signal is resampled to a uniform rate, split into overlapping windowed segments, and the squared FFT magnitudes are averaged. The "Total" channel sums the X, Y and Z power spectra, which makes runs comparable even when the phone was oriented differently.

            Supported inputs: phyphox "Acceleration" exports (.zip or the CSV inside), and any CSV with a time column plus x/y/z or magnitude columns. Files can be opened from the picker or shared to this app.

            Tips: for car vibration, mount the phone rigidly (not on a soft seat), record 20-60 s at a steady speed, and compare runs using the same channel and segment size. Enable wheel order markers to check whether a peak tracks wheel rotation speed.
            """.trimIndent(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
