package ru.tgwatch

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class RetentionPolicyTest {
    @After fun reset() {
        RetentionPolicy.update(Retention.WEEK)
    }

    @Test fun defaultsToWeekAndUpdatesProcessWide() {
        assertEquals(Retention.WEEK, RetentionPolicy.current())
        RetentionPolicy.update(Retention.QUARTER)
        assertEquals(90, RetentionPolicy.maxDays())
        assertEquals(Retention.QUARTER.totalMs, RetentionPolicy.totalMs())
        assertEquals(1_000L - Retention.QUARTER.totalMs, RetentionPolicy.keepFrom(1_000L))
        RetentionPolicy.update(Retention.MONTH)
        assertEquals(30, RetentionPolicy.maxDays())
    }

    @Test fun journalKeepWindowMatchesPolicy() {
        RetentionPolicy.update(Retention.MONTH)
        val now = 50 * Retention.DAY_MS
        val at = now - 10 * Retention.DAY_MS
        val rows = listOf(TargetObservation(Observation(at, at + 30_000L, "OK", 1), 0))
        val monthKept = TargetJournal.retained(rows, now - RetentionPolicy.totalMs(), 11_000)
        assertEquals(1, monthKept.size)
        assertEquals("OK", monthKept.single().row.kind)
        RetentionPolicy.update(Retention.WEEK)
        val weekKept = TargetJournal.retained(rows, now - RetentionPolicy.totalMs(), 11_000)
        // Timeline.retained may keep an epoch marker, but never the expired OK check itself.
        assertEquals(0, weekKept.count { it.row.kind == "OK" })
    }
}
