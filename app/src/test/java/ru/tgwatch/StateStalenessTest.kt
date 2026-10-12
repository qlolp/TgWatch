package ru.tgwatch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateStalenessTest {
    private val wall = 1_700_000_000_000L
    private val mono = 5_000_000L

    private fun state(expectedSec: Int, checkedElapsed: Long = mono) = MonitorService.State(
        status = MonitorService.Status.OK, checkedAt = wall, since = wall,
        expectedIntervalSec = expectedSec, checkedElapsed = checkedElapsed,
    )

    @Test fun usesTheIntervalTheResultScheduled() {
        // 100 s after a check: stale for a 30 s screen-on interval, fresh for a 120 s sleep interval.
        assertTrue(state(30).isStale(wall + 100_000, mono + 100_000))
        assertFalse(state(120).isStale(wall + 100_000, mono + 100_000))
        assertTrue(state(120).isStale(wall + 361_000, mono + 361_000))
    }

    @Test fun monotonicAgeWinsOverWallClockJumps() {
        assertFalse(state(30).isStale(wall + 3_600_000, mono + 20_000))
        assertTrue(state(30).isStale(wall + 20_000, mono + 3_600_000))
    }

    @Test fun restoredStateWithoutMonotonicStampUsesWallClock() {
        assertFalse(state(30, checkedElapsed = 0L).isStale(wall + 60_000, 0L))
        assertTrue(state(30, checkedElapsed = 0L).isStale(wall + 120_000, 0L))
    }

    @Test fun clockMovedBeforeTheCheckIsStaleAndNoResultIsNot() {
        assertTrue(state(30).isStale(wall - 1, mono + 1_000))
        assertFalse(MonitorService.State().isStale(wall, mono))
    }
}
