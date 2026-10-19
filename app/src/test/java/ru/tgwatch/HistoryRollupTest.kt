package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRollupTest {
    private val day = Retention.DAY_MS
    private val minute = Retention.MINUTE_MS
    private val coarse = Retention.COARSE_MS

    private fun check(at: Long, kind: String, latencyMs: Long = -1L, epoch: Long = 0L,
        until: Long = at + 30_000L) = Observation(at, until, kind, latencyMs, epoch)

    private fun minuteBucket(start: Long, ok: Int = 0, fail: Int = 0, offline: Int = 0,
        partial: Int = 0, unknown: Int = 0, latencySum: Long = 0L, latencyCount: Int = 0,
        epoch: Long = 0L) =
        HistoryBucket(epoch, start, Retention.MINUTE_MS, ok, fail, offline, partial, unknown, latencySum, latencyCount)

    @Test fun weekKeepsOnlyRawWithinSevenDays() {
        val now = 90 * day
        val fresh = check(now - 1_000L, "OK", 120L)
        val edge = check(now - 6 * day, "TG_DOWN")
        val expired = check(now - 8 * day, "OK", 50L)
        val rolled = HistoryRollup.rollup(listOf(fresh, edge, expired), now, Retention.WEEK)
        assertEquals(listOf(edge, fresh), rolled.raw)
        assertTrue(rolled.minutes.isEmpty())
        assertTrue(rolled.coarse.isEmpty())
        assertTrue(HistoryRollup.asObservations(rolled).none { it.at == expired.at })
    }

    @Test fun monthRollsDaysEightToThirtyIntoMinuteBuckets() {
        val now = 100 * day
        val raw = check(now - 1_000L, "OK", 80L)
        val oldA = check(now - 10 * day + 5_000L, "OK", 40L)
        val oldB = check(now - 10 * day + 8_000L, "TG_DOWN")
        val older = check(now - 40 * day, "OK", 10L)
        val rolled = HistoryRollup.rollup(listOf(raw, oldA, oldB, older), now, Retention.MONTH)
        assertEquals(listOf(raw), rolled.raw)
        assertEquals(1, rolled.minutes.size)
        val bucket = rolled.minutes.single()
        assertEquals(1, bucket.ok)
        assertEquals(1, bucket.fail)
        assertEquals(2, bucket.total)
        assertEquals(40L, bucket.latencySum)
        assertEquals(1, bucket.latencyCount)
        assertTrue(rolled.coarse.isEmpty())
        assertEquals(0, HistoryRollup.asObservations(rolled).count { it.at == older.at })
    }

    @Test fun quarterRollsPastThirtyDaysIntoFiveMinuteBuckets() {
        val now = 200 * day
        val fresh = check(now - 2_000L, "PARTIAL")
        val minuteAge = check(now - 12 * day + 11_000L, "OK", 33L)
        val coarseAge = check(now - 45 * day + 21_000L, "NO_NETWORK")
        val expired = check(now - 100 * day, "OK", 5L)
        val rolled = HistoryRollup.rollup(listOf(fresh, minuteAge, coarseAge, expired), now, Retention.QUARTER)
        assertEquals(listOf(fresh), rolled.raw)
        assertEquals(1, rolled.minutes.size)
        assertEquals(1, rolled.coarse.size)
        assertEquals(Retention.COARSE_MS, rolled.coarse.single().resolutionMs)
        assertEquals(1, rolled.coarse.single().offline)
        assertEquals(0, rolled.coarse.single().ok)
    }

    @Test fun bucketSumsMatchSourceChecksAndPreserveLatencyAverage() {
        val now = 10 * day
        val start = now - 10 * day + 12 * minute
        val rows = listOf(
            check(start + 1_000L, "OK", 100L),
            check(start + 2_000L, "OK", 300L),
            check(start + 3_000L, "TG_DOWN"),
            check(start + 4_000L, "PARTIAL"),
            check(start + 5_000L, "NO_NETWORK"),
            check(start + 6_000L, "UNKNOWN"),
        )
        val rolled = HistoryRollup.rollup(rows, now, Retention.MONTH)
        val bucket = rolled.minutes.single()
        assertEquals(6, bucket.total)
        assertEquals(2, bucket.ok)
        assertEquals(1, bucket.fail)
        assertEquals(1, bucket.partial)
        assertEquals(1, bucket.offline)
        assertEquals(1, bucket.unknown)
        assertEquals(400L, bucket.latencySum)
        assertEquals(2, bucket.latencyCount)
        assertEquals(200L, LatencyTotal(bucket.latencySum).average(bucket.latencyCount))
    }

    @Test fun aggregatesNeverCrossClockEpoch() {
        val now = 50 * day
        val oldEpoch = check(now - 10 * day + minute, "OK", 10L, epoch = 0L)
        val newEpoch = check(now - 10 * day + minute + 3_000L, "TG_DOWN", epoch = 1L)
        val rolled = HistoryRollup.rollup(listOf(oldEpoch, newEpoch), now, Retention.MONTH)
        assertEquals(2, rolled.minutes.size)
        assertEquals(setOf(0L, 1L), rolled.minutes.map { it.clockEpoch }.toSet())
        assertTrue(rolled.minutes.all { it.startMs % Retention.MINUTE_MS == 0L })
    }

    @Test fun minuteAndCoarseBucketsAlignToTheirResolution() {
        val now = 200 * day
        val rows = listOf(
            check(now - 10 * day + 90_000L, "OK", 1L),
            check(now - 50 * day + 190_000L, "OK", 2L),
        )
        val rolled = HistoryRollup.rollup(rows, now, Retention.QUARTER)
        assertEquals(0L, rolled.minutes.single().startMs % Retention.MINUTE_MS)
        assertEquals(0L, rolled.coarse.single().startMs % Retention.COARSE_MS)
        assertEquals(Retention.MINUTE_MS, rolled.minutes.single().resolutionMs)
        assertEquals(Retention.COARSE_MS, rolled.coarse.single().resolutionMs)
    }

    @Test fun realCheckWinsOverEpochMetadataAtSameTimestamp() {
        val now = 20 * day
        val at = now - 10 * day + 3 * minute
        val marker = Observation(at, at, "UNKNOWN", -1L, 0L)
        val real = check(at, "OK", 77L)
        val rolled = HistoryRollup.rollup(listOf(marker, real), now, Retention.MONTH)
        assertEquals(1, rolled.minutes.size)
        assertEquals(1, rolled.minutes.single().ok)
        assertEquals(0, rolled.minutes.single().unknown)
        assertEquals(77L, rolled.minutes.single().latencySum)
    }

    @Test fun latestEpochMarkerSurvivesEvenWhenChecksExpire() {
        val now = 100 * day
        val marker = Observation(now - day, now - day, "UNKNOWN", -1L, 3L)
        val expired = check(now - 40 * day, "OK", 9L, epoch = 3L)
        val rolled = HistoryRollup.rollup(listOf(expired, marker), now, Retention.MONTH)
        assertEquals(listOf(marker), rolled.raw)
        assertTrue(rolled.minutes.isEmpty())
    }

    @Test fun oldEpochMarkersDoNotResurrectArchivedSamples() {
        val now = 100 * day
        val oldMarker = Observation(now - 40 * day, now - 40 * day, "UNKNOWN", -1L, 1L)
        val currentMarker = Observation(now - 1_000L, now - 1_000L, "UNKNOWN", -1L, 2L)
        val rolled = HistoryRollup.rollup(listOf(oldMarker, currentMarker), now, Retention.MONTH)
        assertEquals(listOf(currentMarker), rolled.raw)
    }

    @Test fun downAliasIsCountedAsFail() {
        val now = 20 * day
        val at = now - 10 * day + minute
        val rolled = HistoryRollup.rollup(listOf(check(at, "DOWN")), now, Retention.MONTH)
        assertEquals(1, rolled.minutes.single().fail)
        assertEquals(0, rolled.minutes.single().unknown)
    }

    @Test fun unknownLatencyIsNotAveragedIntoOk() {
        val now = 20 * day
        val at = now - 10 * day + 2 * minute
        val rolled = HistoryRollup.rollup(listOf(check(at, "UNKNOWN", 999L)), now, Retention.MONTH)
        val bucket = rolled.minutes.single()
        assertEquals(1, bucket.unknown)
        assertEquals(0, bucket.ok)
        assertEquals(0L, bucket.latencySum)
        assertEquals(0, bucket.latencyCount)
    }

    @Test fun boundsReportExpectedRowsAndSharedByteCeiling() {
        val week = HistoryRollup.bounds(Retention.WEEK)
        val month = HistoryRollup.bounds(Retention.MONTH)
        val quarter = HistoryRollup.bounds(Retention.QUARTER)
        assertEquals(65_000, week.expectedRows)
        assertEquals(65_000 + 30 * 24 * 60, month.expectedRows)
        assertEquals(65_000 + 30 * 24 * 60 + 90 * 24 * 12, quarter.expectedRows)
        assertEquals(HistoryCompaction.MAX_DISK_BYTES, week.maxBytes)
        assertEquals(HistoryCompaction.MAX_DISK_BYTES, quarter.maxBytes)
        assertTrue(quarter.maxRows >= quarter.expectedRows)
    }

    @Test fun retentionWindowsMatchDocumentedTiers() {
        assertEquals(7 * day, Retention.WEEK.rawMs)
        assertEquals(0L, Retention.WEEK.minuteMs)
        assertEquals(0L, Retention.WEEK.coarseMs)
        assertEquals(7 * day, Retention.MONTH.rawMs)
        assertEquals(30 * day, Retention.MONTH.minuteMs)
        assertEquals(0L, Retention.MONTH.coarseMs)
        assertEquals(90 * day, Retention.QUARTER.coarseMs)
        assertEquals(90 * day, Retention.QUARTER.totalMs)
        assertEquals(30 * day, Retention.MONTH.totalMs)
        assertEquals(7 * day, Retention.WEEK.totalMs)
    }

    @Test fun asObservationsExpandsBucketsToDominantKindWithoutInventingChecks() {
        val now = 20 * day
        val at = now - 10 * day + 4 * minute
        val rolled = HistoryRollup.rollup(listOf(check(at, "OK", 60L), check(at + 1_000L, "OK", 80L)), now,
            Retention.MONTH)
        val expanded = HistoryRollup.asObservations(rolled)
        assertEquals(1, expanded.size)
        val sample = expanded.single()
        assertEquals("OK", sample.kind)
        assertEquals(70L, sample.latencyMs)
        assertEquals(rolled.minutes.single().startMs, sample.at)
        assertEquals(rolled.minutes.single().endMs, sample.until)
        assertEquals(2, rolled.minutes.single().total)
    }

    @Test fun dominantKindPrefersTheLargestCount() {
        assertEquals("TG_DOWN", HistoryRollup.dominantKind(minuteBucket(0L, ok = 1, fail = 2)))
        assertEquals("OK", HistoryRollup.dominantKind(minuteBucket(0L, ok = 3, fail = 2, unknown = 1)))
        assertEquals("UNKNOWN", HistoryRollup.dominantKind(minuteBucket(0L)))
        assertEquals("PARTIAL", HistoryRollup.dominantKind(minuteBucket(0L, partial = 1)))
    }

    @Test fun emptyAndMetadataOnlyInputStaysEmpty() {
        val now = 5 * day
        assertTrue(HistoryRollup.rollup(emptyList(), now, Retention.WEEK).isEmpty)
        val marker = Observation(now - 10L, now - 10L, "UNKNOWN", -1L, 0L)
        val rolled = HistoryRollup.rollup(listOf(marker), now, Retention.WEEK)
        assertEquals(listOf(marker), rolled.raw)
        assertFalse(rolled.isEmpty)
    }

    @Test fun bucketRejectsMisalignedWindowsAndNegativeCounts() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            HistoryBucket(0L, 1L, Retention.MINUTE_MS)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            HistoryBucket(0L, 0L, 1_000L)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            HistoryBucket(0L, 0L, Retention.MINUTE_MS, ok = -1)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            HistoryBucket(0L, 0L, Retention.MINUTE_MS, ok = 1, latencyCount = 2)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            minuteBucket(0L).merge(minuteBucket(Retention.MINUTE_MS))
        }
    }
}
