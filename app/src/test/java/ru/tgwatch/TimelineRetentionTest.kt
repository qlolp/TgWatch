package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class TimelineRetentionTest {
    @Test fun lastRealResultWinsOverTrailingMetadata() {
        val first = Observation(10, 20, "OK", 1, 2)
        val last = first.copy(kind = "TG_DOWN", latencyMs = 9)
        val marker = first.copy(until = 10, kind = "UNKNOWN", latencyMs = -1)
        assertEquals(listOf(last), Timeline.retained(listOf(marker, first, last, marker), 0))
    }

    @Test fun epochsAreDistinctAndShuffledInputIsUnchanged() {
        val older = Observation(10, 20, "OK", clockEpoch = 1)
        val newer = older.copy(kind = "TG_DOWN", clockEpoch = 2)
        val earliest = older.copy(at = 1, until = 5)
        val input = listOf(newer, older, earliest)
        assertEquals(listOf(earliest, older, newer), Timeline.retained(input, 0))
        assertEquals(listOf(newer, older, earliest), input)
    }

    @Test fun expiredLatestEpochKeepsBoundaryWithoutClaimingCheck() {
        val live = Observation(100, 200, "OK", 3, 1)
        val expired = Observation(10, 20, "TG_DOWN", 5, 2)
        val marker = Observation(10, 10, "UNKNOWN", -1, 2)
        assertEquals(listOf(live, marker), Timeline.retained(listOf(expired, live), 50))
        assertEquals(listOf(marker), Timeline.retained(listOf(live, expired), 50, 1))
    }

    @Test fun capKeepsNewestRowsAcrossEpochs() {
        val rows = listOf(Observation(100, 101, "OK", clockEpoch = 1),
            Observation(1, 2, "OK", clockEpoch = 2), Observation(2, 3, "OK", clockEpoch = 2))
        assertEquals(rows.drop(1), Timeline.retained(rows, 0, 2))
    }

    @Test fun sixtyFiveThousandLimitDropsExactlyTheOldestRow() {
        val rows = List(65_001) { Observation(it.toLong(), it + 1L, "OK") }
        val actual = Timeline.retained(rows, -1)
        assertEquals(65_000, actual.size)
        assertEquals(rows[1], actual.first())
        assertEquals(rows.last(), actual.last())
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyInputStillRejectsZeroLimit() { Timeline.retained(emptyList(), 0, 0) }
}
