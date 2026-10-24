package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceWindowsCodecTest {
    @Test fun encodeDecodeRoundTrip() {
        val window = MaintenanceWindow("w1", 120, 300, daysMask = 0b0000011, enabled = true)
        val encoded = listOf(window).joinToString(";") {
            listOf(it.id, it.startMinuteOfDay, it.endMinuteOfDay, it.daysMask, if (it.enabled) 1 else 0)
                .joinToString("|")
        }
        val parts = encoded.split("|")
        assertEquals(5, parts.size)
        val decoded = MaintenanceWindow(parts[0], parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), parts[4] == "1")
        assertEquals(window, decoded)
    }

    @Test fun activeAtUsesLocalCalendar() {
        val window = MaintenanceWindow("n", 22 * 60, 23 * 60)
        val at = 1_792_000_000_000L
        val zone = java.util.TimeZone.getTimeZone("UTC")
        val calendar = java.util.Calendar.getInstance(zone).apply { timeInMillis = at }
        val minute = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
        val expected = minute >= 22 * 60 && minute < 23 * 60
        assertEquals(expected, MaintenanceSchedule.activeAt(listOf(window), at, zone))
        assertFalse(MaintenanceWindows.active(emptyList(), at))
        assertTrue(MaintenanceSchedule.activeAt(listOf(MaintenanceWindow("all", 0, 1440)), at, zone))
    }
}
