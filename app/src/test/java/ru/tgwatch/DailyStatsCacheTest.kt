package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class DailyStatsCacheTest {
    private fun stats() = TimeStats(1, 1, 1, 1, 1, 1, 1, 1)
    @Test fun repeatedNotificationTicksDoNotReadHistory() {
        val cache = DailyStatsCache()
        var reads = 0
        val expected = stats()
        repeat(15) { tick ->
            assertSame(expected, cache.get(7, 120_000L + tick * 1_000L) { reads++; expected })
        }
        assertEquals(1, reads)
    }
    @Test fun newHistoryMinuteAndClockRollbackRefreshStatistics() {
        val cache = DailyStatsCache()
        var reads = 0
        cache.get(1, 120_000) { reads++; stats() }
        cache.get(2, 120_000) { reads++; stats() }
        cache.get(2, 180_000) { reads++; stats() }
        cache.get(2, 60_000) { reads++; stats() }
        assertEquals(4, reads)
    }
}
