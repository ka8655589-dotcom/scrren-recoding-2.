package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ExampleUnitTest {
    @Test
    fun testTimeFormatting() {
        val totalSecs = 3665L
        val h = totalSecs / 3600
        val m = (totalSecs % 3600) / 60
        val s = totalSecs % 60
        val formatted = String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
        assertEquals("01:01:05", formatted)
    }

    @Test
    fun testBatteryThresholdLogic() {
        val batteryLevel = 14
        val threshold = 15
        val shouldStop = batteryLevel <= threshold
        assertTrue("Battery at 14% must trigger threshold auto-stop when limit is 15%", shouldStop)
    }

    @Test
    fun testChunkDurationCalculation() {
        val splitMins = 60
        val splitSecs = splitMins * 60L
        assertEquals(3600L, splitSecs)
    }
}
