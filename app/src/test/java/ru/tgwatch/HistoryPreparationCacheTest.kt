package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class HistoryPreparationCacheTest {
    @Test fun repeatedReadsReusePreparationUntilAnActualExpiry() {
        val cache = HistoryPreparationCache()
        val rows = listOf(Observation(100, 200, "OK"), Observation(200, 400, "TG_DOWN"))
        val first = cache.prepare(rows, 0, 0)
        assertSame(first, cache.prepare(rows, 0, 199))
        val expired = cache.prepare(rows, 0, 200)
        assertNotSame(first, expired)
        assertEquals(rows.drop(1), expired.rows)
        assertSame(expired, cache.prepare(rows, 0, 399))
    }

    @Test fun revisionChangeReplacesSameTimeResultAndRestoredEpoch() {
        val cache = HistoryPreparationCache()
        val rows = mutableListOf(Observation(100, 200, "TG_DOWN"))
        val first = cache.prepare(rows, 0, 0)
        rows += Observation(100, 300, "OK")
        val updated = cache.prepare(rows, 1, 0)
        assertNotSame(first, updated)
        assertEquals("OK", updated.checks.single().kind)
        val restored = cache.prepare(listOf(Observation(100, 200, "PARTIAL", clockEpoch = 4)), 2, 0)
        assertEquals(4L, restored.epoch)
        assertEquals("PARTIAL", restored.checks.single().kind)
    }

    @Test fun backwardCutoffRebuildsAndExpiredEpochCannotReviveArchive() {
        val cache = HistoryPreparationCache()
        val rows = listOf(Observation(500, 600, "OK"), Observation(10, 20, "TG_DOWN", clockEpoch = 1))
        val expired = cache.prepare(rows, 0, 20)
        assertTrue(expired.checks.isEmpty())
        assertEquals(1L, expired.epoch)
        assertEquals(10L, expired.latestAt)
        val earlier = cache.prepare(rows, 0, 0)
        assertNotSame(expired, earlier)
        assertEquals("TG_DOWN", earlier.checks.single().kind)
    }

    @Test fun capsRowsAndRealUnknownWinsOverEpochMarker() {
        val cache = HistoryPreparationCache()
        val rows = List(65_001) { Observation(it.toLong(), it + 10L, "OK") } +
            Observation(65_001, 65_001, "UNKNOWN", clockEpoch = 1) +
            Observation(65_001, 65_002, "UNKNOWN", clockEpoch = 1)
        val prepared = cache.prepare(rows, 0, 0)
        assertEquals(65_000, prepared.rows.size)
        assertEquals(1, prepared.checks.size)
        assertFalse(prepared.checks.single().isEpochMetadata)
    }

    @Test fun clearAndBackwardReadsInvalidateWithoutChangingTheRevision() {
        val cache = HistoryPreparationCache()
        val rows = listOf(Observation(100, 200, "OK"))
        val first = cache.prepare(rows, 0, 0)
        assertSame(first, cache.prepare(rows, 0, 50))
        val backward = cache.prepare(rows, 0, 49)
        assertNotSame(first, backward)
        cache.clear()
        assertNotSame(backward, cache.prepare(rows, 0, 49))
    }

    @Test fun acceptedPruningRevisionReusesPreparationButNextMutationDoesNot() {
        val cache = HistoryPreparationCache()
        val first = cache.prepare(listOf(Observation(10, 20, "OK")), 0, 20)
        cache.acceptRevision(1)
        assertSame(first, cache.prepare(first.rows, 1, 21))
        assertNotSame(first, cache.prepare(first.rows + Observation(30, 40, "OK"), 2, 21))
    }

    @Test fun preparationMatchesCanonicalStatsForDifferentPeriods() {
        val rows = listOf(Observation(0, 1000, "OK"), Observation(1000, 2000, "TG_DOWN"),
            Observation(2000, 3000, "PARTIAL"), Observation(3000, 4000, "OK"))
        val prepared = HistoryPreparationCache().prepare(rows, 0, -1)
        for (from in listOf(0L, 500L, 1500L)) {
            assertEquals(Timeline.stats(rows, from, 5000), Timeline.stats(prepared.checks, from, 5000))
        }
    }
}
