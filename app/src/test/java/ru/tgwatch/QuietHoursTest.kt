package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class QuietHoursTest {
    @Test fun overnightMinuteWindowHasExactHalfOpenBoundaries() {
        val start = 23 * 60 + 15
        val end = 8 * 60 + 45
        assertFalse(QuietHours.contains(start - 1, start, end))
        assertTrue(QuietHours.contains(start, start, end))
        assertTrue(QuietHours.contains(0, start, end))
        assertTrue(QuietHours.contains(end - 1, start, end))
        assertFalse(QuietHours.contains(end, start, end))
        assertFalse(QuietHours.contains(12 * 60, start, end))
    }

    @Test fun sameDayMinuteWindowAndEqualEndpoints() {
        assertFalse(QuietHours.contains(12 * 60 + 14, 735, 765))
        assertTrue(QuietHours.contains(735, 735, 765))
        assertTrue(QuietHours.contains(764, 735, 765))
        assertFalse(QuietHours.contains(765, 735, 765))
        for (minute in 0..1439) assertFalse(QuietHours.contains(minute, 735, 735))
    }

    @Test fun minuteLabelsAndLegacyLabelsKeepChosenPrecision() {
        assertEquals("23:15–08:45", QuietHours.label(1395, 525))
        assertEquals("00:00–23:59", QuietHours.label(0, 1439))
        assertEquals("23:15–08:45", ProbeRules.quietHoursMinutesLabel(1395, 525))
        assertTrue(ProbeRules.inQuietHoursMinutes(524, 1395, 525))
        assertFalse(ProbeRules.inQuietHoursMinutes(525, 1395, 525))
        assertEquals("23:00–08:00", ProbeRules.quietHoursLabel(23, 8))
    }

    @Test fun absentMinuteFallsBackToLegacyHoursAndMinuteTakesPrecedence() {
        assertEquals(1380, QuietHours.resolveMinute(null, 23))
        assertEquals(480, QuietHours.resolveMinute(null, 8))
        assertEquals(1395, QuietHours.resolveMinute(1395, 23))
        assertEquals(525, QuietHours.resolveMinute(525, 8))
        assertEquals(0, QuietHours.resolveMinute(null, Int.MIN_VALUE))
        assertEquals(1380, QuietHours.resolveMinute(null, Int.MAX_VALUE))
        assertEquals(480, QuietHours.resolveMinute(Int.MAX_VALUE, 8))
        assertEquals(480, QuietHours.resolveMinute(-1, 8))
    }
}
