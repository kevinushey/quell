package com.kevinushey.quell

import com.kevinushey.quell.data.TimeSeries
import com.kevinushey.quell.ui.TapAnalysis
import com.kevinushey.quell.ui.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Test

class UiLogicTest {

    @Test
    fun decimalFieldsAcceptCommaSeparators() {
        assertEquals(0.5, com.kevinushey.quell.ui.components.parseDecimal("0,5")!!, 0.0)
        assertEquals(12.5, com.kevinushey.quell.ui.components.parseDecimal(" 12.5 ")!!, 0.0)
        assertEquals(null, com.kevinushey.quell.ui.components.parseDecimal("abc"))
    }

    @Test
    fun formatDurationRoundsWholeMinutes() {
        assertEquals("2m 00s", formatDuration(119.96))
        assertEquals("1m 05s", formatDuration(65.2))
        assertEquals("59.9 s", formatDuration(59.94))
    }

    @Test
    fun tapAnalysisTerminatesOnNanosecondTimestamps() {
        // Raw SensorEvent timestamps give a tiny estimated sample rate and a zero-length scan window.
        val n = 2000
        val time = DoubleArray(n) { it * 2_500_000.0 }
        val z = DoubleArray(n) { if (it % 400 == 0) 50.0 else 0.0 }
        val series = TimeSeries(time, DoubleArray(n), DoubleArray(n), z)

        val summary = TapAnalysis.analyze(series, threshold = 10.0)

        assertEquals(0, summary.count)
    }
}
