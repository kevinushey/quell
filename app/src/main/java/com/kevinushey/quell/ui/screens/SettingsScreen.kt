package com.kevinushey.quell.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import java.text.DateFormat
import java.util.Date
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
fun SettingsScreen(vm: MainViewModel, onOpenGuide: () -> Unit) {
    val app by vm.appSettings.collectAsStateWithLifecycle()
    val sync by vm.syncStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmRestore by remember { mutableStateOf(false) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null)
            return@rememberLauncherForActivityResult
        // The grant must outlive this activity, or the mirror stops at the next launch.
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        vm.setSyncFolder(uri)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Cloud sync", style = MaterialTheme.typography.titleMedium)
        Text(
            "Mirrors every recording, its settings and your sweeps into a folder you choose. Pick a Google Drive, Dropbox or OneDrive folder and that app uploads the files; pick a local folder for a plain copy. Nothing is sent anywhere else.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (app.syncTreeUri == null) {
            Button(onClick = { pickFolder.launch(null) }) { Text("Choose folder") }
        } else {
            val when_ = sync.lastSyncEpochMs?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }
            Text(
                buildString {
                    append("Folder: ${sync.folderName ?: "(chosen)"}")
                    if (sync.restoring)
                        append("  |  restoring...")
                    else if (sync.syncing)
                        append("  |  syncing...")
                    else if (when_ != null)
                        append("  |  ${sync.mirroredCount} mirrored, last sync $when_")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            sync.lastError?.let {
                Text("Last error: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (sync.indexDegraded && sync.notOnThisPhone > 0) {
                Text(
                    "This phone's dataset index could not be fully read. The folder keeps its own entries, so nothing is lost there; \"Restore from folder\" brings the missing runs back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (sync.notOnThisPhone > 0) {
                Text(
                    "The folder holds ${sync.notOnThisPhone} dataset${if (sync.notOnThisPhone == 1) "" else "s"} this phone does not have. \"Restore from folder\" brings them in; they stay in the folder either way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            SwitchRow("Mirror automatically", "Copies new and changed data a few seconds after each change while the app is open. Only runs you delete here are removed from the folder.", app.syncEnabled) { on ->
                vm.updateAppSettings { it.copy(syncEnabled = on) }
                if (on)
                    vm.syncNow()
            }
            SwitchRow("Also write CSV copies", "One CSV per run next to the app's own files, for spreadsheets or other tools", app.syncCsv) { on ->
                vm.updateAppSettings { it.copy(syncCsv = on) }
                if (on)
                    vm.syncNow()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.syncNow() }, enabled = app.syncEnabled && !sync.syncing && !sync.restoring) { Text("Sync now") }
                OutlinedButton(onClick = { confirmRestore = true }, enabled = !sync.restoring) { Text("Restore from folder") }
                TextButton(onClick = { pickFolder.launch(null) }) { Text("Change") }
            }
            TextButton(onClick = { vm.setSyncFolder(null) }) { Text("Stop mirroring") }
        }

        HorizontalDivider()
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
        Text("Help", style = MaterialTheme.typography.titleMedium)
        Text(
            "The guide walks through a session: recording or importing, reading a spectrum and its uncertainty, tap tests and the antinode search, tuning a damper with sweeps, and keeping the data.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenGuide) { Text("Open the guide") }
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(GUIDE_URL))) }
            }) { Text("On GitHub") }
        }

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

    if (confirmRestore) {
        RestoreDialog(onDismiss = { confirmRestore = false }) {
            confirmRestore = false
            vm.restoreFromSync()
        }
    }
}

@Composable
private fun RestoreDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore from folder?") },
        text = { Text("Datasets and sweeps in the folder that this phone does not have are copied in. Nothing here is overwritten or removed.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Restore") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
