package com.kevinushey.quell.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kevinushey.quell.ui.ChartExportHost
import com.kevinushey.quell.ui.MainViewModel

/** Time-domain views: the raw waveform or the spectrogram of one run. */
@Composable
fun SignalScreen(vm: MainViewModel, exportHost: ChartExportHost) {
    var spectrogram by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            SegmentedButton(selected = !spectrogram, onClick = { spectrogram = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Waveform") }
            SegmentedButton(selected = spectrogram, onClick = { spectrogram = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Spectrogram") }
        }
        if (spectrogram)
            SpectrogramScreen(vm, exportHost)
        else
            WaveformScreen(vm, exportHost)
    }
}
