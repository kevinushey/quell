package com.kevinushey.quell

import com.kevinushey.quell.record.StreamGaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StreamGapsTest {

    private val fs = 400.0

    @Test
    fun anUnbrokenRecordingWithJitterHasNoGaps() {
        // Delivery jitter of a few intervals is not a break.
        val time = DoubleArray(4000) { it / fs + if (it % 97 == 0) 3 / fs else 0.0 }
        assertNull(StreamGaps.find(time))
    }

    @Test
    fun aBackgroundBreakIsFoundAndTheLongestStretchKept() {
        // 0-10 s, then nothing until 30 s (the app in the background), then 30-55 s.
        val before = DoubleArray((10 * fs).toInt()) { it / fs }
        val after = DoubleArray((25 * fs).toInt()) { 30.0 + it / fs }
        val gaps = StreamGaps.find(before + after)
        assertNotNull(gaps)
        assertEquals(1, gaps!!.count)
        assertEquals(20.0, gaps.missingSec, 0.01)
        assertEquals(30.0, gaps.longestStart, 1e-9)
        assertEquals(55.0, gaps.longestEnd, 0.01)
    }
}
