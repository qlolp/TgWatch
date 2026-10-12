package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertMuteTest {
    private val now = 1_760_000_000_000L

    @Test fun muteLastsExactlyOneHour() {
        val until = AlertMute.until(now)
        assertEquals(now + 3_600_000L, until)
        assertTrue(AlertMute.active(until, now))
        assertTrue(AlertMute.active(until, until - 1))
        assertFalse(AlertMute.active(until, until))
        assertFalse(AlertMute.active(until, until + 60_000L))
    }

    @Test fun absentDeadlineIsNotMuted() {
        assertFalse(AlertMute.active(0L, now))
        assertFalse(AlertMute.active(0L, 0L))
    }

    @Test fun clockMovedBackDoesNotMuteForLongerThanAnHour() {
        val until = AlertMute.until(now)
        assertTrue(AlertMute.active(until, now))
        assertFalse("A wall-clock correction must not extend the mute", AlertMute.active(until, now - 60_000L))
    }

    @Test fun overflowingWallDifferenceIsNotAValidLegacyWindow() {
        assertFalse(AlertMute.active(Long.MAX_VALUE, Long.MIN_VALUE))
    }
}
