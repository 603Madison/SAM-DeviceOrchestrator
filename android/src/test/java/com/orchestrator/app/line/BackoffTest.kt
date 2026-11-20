package com.orchestrator.app.line

import com.orchestrator.api.Battery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    @Test
    fun `one second, doubling, to five minutes`() {
        val seconds = (0..12).map { Backoff.pause(it) / 1000 }
        assertEquals(listOf(1L, 2, 4, 8, 16, 32, 64, 128, 256, 300, 300, 300, 300), seconds)
    }

    @Test
    fun `never shorter than a second or longer than five minutes, however long it's been failing`() {
        listOf(-5, -1, 0, 9, 31, 62, 63, 64, 1_000, Int.MAX_VALUE).forEach {
            assertTrue("after $it", Backoff.pause(it) in Backoff.FIRST_MS..Backoff.LONGEST_MS)
        }
        assertEquals(1_000L, Backoff.pause(-1))
        assertEquals(300_000L, Backoff.pause(Int.MAX_VALUE))
        assertTrue((0..40).zipWithNext().all { (a, b) -> Backoff.pause(a) <= Backoff.pause(b) })
    }

    @Test
    fun `the battery is told when it changes by a percent or charging flips`() {
        assertTrue(BatteryReport.changed(null, Battery(80)))
        assertFalse(BatteryReport.changed(Battery(80), Battery(80)))
        assertTrue(BatteryReport.changed(Battery(80), Battery(79)))
        assertTrue(BatteryReport.changed(Battery(80), Battery(81)))
        assertTrue(BatteryReport.changed(Battery(80), Battery(80, charging = true)))
        assertTrue(BatteryReport.changed(Battery(80, charging = true), Battery(80)))
        assertEquals(81, BatteryReport.percent(81, 100))
        assertEquals(50, BatteryReport.percent(100, 200))
        // 80.5 % and 80.9 % are both 80: no new report within the same percent.
        assertEquals(BatteryReport.percent(805, 1000), BatteryReport.percent(809, 1000))
        assertNull(BatteryReport.percent(-1, 100))
        assertNull(BatteryReport.percent(50, 0))
    }
}
