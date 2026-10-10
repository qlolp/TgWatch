package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class ClockEpochBoundaryTest {
    private fun rollback(rows: List<Observation>, now: Long): List<Observation> = Timeline.clockRollback(rows, now)

    @Test fun maximumEpochRemapsDistinctArchivesInEpochOrderAndCreatesNewCurrentEpoch() {
        val rows = listOf(Observation(3000, 4000, "TG_DOWN", clockEpoch = Long.MAX_VALUE),
            Observation(500, 600, "OK", 21, 7), Observation(700, 800, "PARTIAL", clockEpoch = Long.MAX_VALUE - 1),
            Observation(900, 1000, "NO_NETWORK", clockEpoch = 7))
        val rolled = rollback(rows, 2000)
        assertEquals(listOf(2L, 0L, 1L, 0L, 3L), rolled.map { it.clockEpoch })
        assertEquals(rows.map { it.copy(clockEpoch = 0) }, rolled.dropLast(1).map { it.copy(clockEpoch = 0) })
        assertEquals(Observation(2000, 2001, "UNKNOWN", clockEpoch = 3), rolled.last())
        assertEquals(listOf(rolled.last()), Timeline.current(rolled))
        assertTrue(rolled.all { it.clockEpoch >= 0 })
    }
    @Test fun repeatedReadsDoNotCreateMoreEpochsAndFreshChecksStayCurrent() {
        val rolled = rollback(listOf(Observation(5000, 6000, "OK", 8, Long.MAX_VALUE)), 2000)
        assertEquals(rolled, rollback(rolled, 2000))
        val fresh = Observation(2500, 3000, "OK", 10, rolled.last().clockEpoch)
        val afterCheck = rolled + fresh
        assertEquals(afterCheck, rollback(afterCheck, 2500))
        assertEquals(1, Timeline.stats(afterCheck, 2000, 3000).okChecks)
        assertEquals(500L, Timeline.stats(afterCheck, 2000, 3000).okMs)
    }
    @Test fun normalRollbackIncrementsWithoutRemappingArchives() {
        val old = Observation(5000, 6000, "OK", clockEpoch = 40)
        val rolled = rollback(listOf(old), 1000)
        assertEquals(old, rolled.first())
        assertEquals(41L, rolled.last().clockEpoch)
    }
    @Test fun maximumEpochWithoutFutureObservationsNeedsNoRemap() {
        val rows = listOf(Observation(1000, 2000, "OK", clockEpoch = Long.MAX_VALUE))
        assertEquals(rows, rollback(rows, 2000))
    }
}
