package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class ObservationCoverageTest {
    private fun stats(ok: Long = 0, down: Long = 0, partial: Long = 0, offline: Long = 0,
        unknown: Long = 0) = TimeStats(ok, down, partial, offline, unknown, 0, 0, -1)

    @Test fun offlineTimeIsObservedButUnknownTimeIsMissing() {
        val value = ObservationCoverage.from(stats(ok = 40, offline = 30, unknown = 30), 100)
        assertEquals(70, value.observedMs)
        assertEquals(30, value.unknownMs)
        assertEquals(70.0, value.percent!!, 0.0)
        assertFalse(value.complete)
    }

    @Test fun aFullyObservedOfflineWindowIsCompleteWithoutClaimingServiceUptime() {
        val value = ObservationCoverage.from(stats(offline = 100), 100)
        assertEquals(100.0, value.percent!!, 0.0)
        assertTrue(value.complete)
    }

    @Test fun emptyWindowHasNoPercentage() {
        assertNull(ObservationCoverage.from(stats(), 0).percent)
    }

    @Test fun durationOverflowCannotProduceNegativeObservedTime() {
        val value = ObservationCoverage.from(stats(ok = Long.MAX_VALUE, down = Long.MAX_VALUE), Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, value.observedMs)
        assertEquals(100.0, value.percent!!, 0.0)
    }
}
