package ru.tgwatch

import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class DcLatencyBucketsTest {
    @Test fun stableIdsAndAddressFingerprintNeverUseListIndex() {
        assertEquals("dc1-v4", MtProtoEndpoints.idFor("MTProto DC1"))
        assertEquals("dc5-v6", MtProtoEndpoints.idFor("MTProto DC5 (IPv6)"))
        assertNull(MtProtoEndpoints.idFor("https://private.example"))
        assertEquals(10, MtProtoEndpoints.all.map { MtProtoEndpoints.idFor(it.name) }.distinct().size)
        assertTrue(MtProtoEndpoints.revision > 0L)
    }
    @Test fun inclusiveEdgesFailuresAndApproximateRanges() {
        val buckets = DcLatencyBuckets()
        listOf(10L, 25L, 50L, 100L, 200L, 400L, 600L, 1000L, 2000L, 3000L, 6000L, 10000L, 10001L)
            .forEach { buckets.add("dc1-v4", 1, 0, 1, ProbeMeasurement.SUCCESS, it) }
        buckets.add("dc1-v4", 1, 0, 1, ProbeMeasurement.FAILURE, 0)
        buckets.add("dc1-v4", 1, 0, 1, ProbeMeasurement.NOT_MEASURED, null)
        val row = buckets.snapshot().single()
        assertEquals(13L, row.successCount)
        assertEquals(List(13) { 1L }, row.histogram)
        assertEquals(1L, row.errorCount)
        assertEquals(1L, row.notMeasuredCount)
        assertEquals(401L..600L, row.percentileRange(50))
        assertEquals(10001L..Long.MAX_VALUE, row.percentileRange(95))
    }
    @Test fun rollbackRevisionAndRetentionNeverMerge() {
        val buckets = DcLatencyBuckets()
        buckets.add("dc1-v4", 1, 0, 0, ProbeMeasurement.SUCCESS, 10)
        buckets.add("dc1-v4", 1, 1, 0, ProbeMeasurement.SUCCESS, 20)
        buckets.add("dc1-v4", 2, 1, 0, ProbeMeasurement.SUCCESS, 30)
        assertEquals(3, buckets.snapshot().size)
        repeat(700) { buckets.add("dc1-v4", 2, 1, it * 900000L, ProbeMeasurement.FAILURE, null) }
        assertEquals(672, buckets.snapshot().size)
        assertTrue(buckets.snapshot().all { it.endpointRevision == 2L && it.clockEpoch == 1L })
    }
    @Test fun overflowIsUnavailableAndNeverWraps() {
        val old = DcLatencyBucket.empty("dc1-v4", 1, 0, 0).copy(successCount = Long.MAX_VALUE,
            exactSum = BigInteger.valueOf(Long.MAX_VALUE), minMs = 1, maxMs = 1,
            histogram = listOf(Long.MAX_VALUE) + List(12) { 0L })
        val buckets = DcLatencyBuckets(listOf(old))
        buckets.add("dc1-v4", 1, 0, 1, ProbeMeasurement.SUCCESS, 1)
        val row = buckets.snapshot().single()
        assertFalse(row.available)
        assertEquals(Long.MAX_VALUE, row.successCount)
        assertNull(row.percentileRange(95))
    }
    @Test fun allSixteenEndpointLimitsHoldTogether() {
        val buckets = DcLatencyBuckets()
        for (dc in 1..8) for (family in listOf(4, 6)) repeat(673) {
            buckets.add("dc$dc-v$family", 1, 0, it * 900000L, ProbeMeasurement.NOT_MEASURED, null)
        }
        assertEquals(10752, buckets.snapshot().size)
        assertTrue(buckets.snapshot().groupBy { it.endpointId }.values.all { it.size == 672 })
    }
}
