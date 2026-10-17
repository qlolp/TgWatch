package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class CoverageStatsTest {
    private val day = 86_400_000L

    @Test fun twentyMinutesOfOkIsNotFullDayCoverage() {
        val rows = (0 until 2).map { Observation(it * 600_000L, (it + 1) * 600_000L, "OK", 100) }
        val stats = Timeline.stats(rows, 0, day)
        val coverage = CoverageStats.from(stats, day)
        assertEquals(100.0, stats.uptimePercent, 0.001)
        assertEquals(1_200_000L, coverage.assessedMs)
        assertEquals(day, coverage.windowMs)
        assertEquals(100.0 / 72.0, coverage.percent!!, 0.001)
        assertEquals(day - 1_200_000, coverage.unknownMs)
    }

    @Test fun noDataIsNotZeroUptime() {
        val stats = Timeline.stats(emptyList(), 0, day)
        val coverage = CoverageStats.from(stats, day)
        assertEquals(-1.0, stats.uptimePercent, 0.0)
        assertNull(coverage.percent)
        assertEquals(day, coverage.unknownMs)
    }

    @Test fun offlineIsSeparateFromAssessedServiceCoverage() {
        val stats = Timeline.stats(listOf(Observation(0, 300_000, "NO_NETWORK"),
            Observation(300_000, 600_000, "PARTIAL")), 0, 600_000)
        val coverage = CoverageStats.from(stats, 600_000)
        assertEquals(300_000L, coverage.assessedMs)
        assertEquals(300_000L, coverage.offlineMs)
        assertEquals(0L, coverage.unknownMs)
        assertEquals(50.0, coverage.percent!!, 0.001)
    }

    @Test fun rollbackDoesNotDoubleCountCoverage() {
        val stats = Timeline.stats(listOf(Observation(0, 600_000, "OK", 100, 0),
            Observation(0, 300_000, "PARTIAL", -1, 1)), 0, 600_000)
        val coverage = CoverageStats.from(stats, 600_000)
        assertEquals(300_000L, coverage.assessedMs)
        assertEquals(300_000L, coverage.unknownMs)
        assertEquals(50.0, coverage.percent!!, 0.001)
    }

    @Test fun emptyWindowNeverProducesNanOrAvailability() {
        val coverage = CoverageStats.from(Timeline.stats(emptyList(), 0, 0), 0)
        assertNull(coverage.percent)
        assertEquals(0L, coverage.assessedMs + coverage.offlineMs + coverage.unknownMs)
    }

    @Test fun oversizedTotalsCannotOverflowOrExceedTheWindow() {
        val stats = TimeStats(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
            Long.MAX_VALUE, 0, 0, -1)
        val coverage = CoverageStats.from(stats, day)
        assertEquals(day, coverage.assessedMs)
        assertEquals(0L, coverage.offlineMs)
        assertEquals(0L, coverage.unknownMs)
        assertEquals(100.0, coverage.percent!!, 0.001)
    }
}
