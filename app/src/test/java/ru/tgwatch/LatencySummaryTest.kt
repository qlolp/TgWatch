package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class LatencySummaryTest {
    @Test fun nearestRankEmptySingleEvenAndTwenty() {
        assertNull(LatencySummary.exact(emptyList()).p95Ms)
        assertFalse(LatencySummary.exact(emptyList()).smallSample)
        assertEquals(7L, LatencySummary.exact(listOf(7L)).p50Ms)
        val even = LatencySummary.exact(listOf(40L, 10L, 30L, 20L))
        assertEquals(20L, even.p50Ms)
        assertEquals(40L, even.p95Ms)
        assertTrue(LatencySummary.exact(List(19) { 10L }).smallSample)
        assertFalse(LatencySummary.exact(List(20) { 10L }).smallSample)
    }
    @Test fun meanDoesNotOverflow() {
        assertEquals(Long.MAX_VALUE, LatencySummary.exact(List(2) { Long.MAX_VALUE }).meanMs)
        assertEquals(0L, LatencySummary.exact(listOf(-1L, 0L)).meanMs)
    }
    @Test fun onlyCurrentEndpointClockWindowAndConfirmedSamplesContribute() {
        fun row(at: Long, kind: String, latency: Long, epoch: Long = 2, revision: Long = 3) =
            TargetObservation(Observation(at, at + 100, kind, latency, epoch), revision)
        val rows = listOf(row(10, "OK", 1, revision = 2), row(20, "OK", 2, epoch = 1),
            row(30, "OK", 3), row(90, "OK", 100), row(95, "PARTIAL", 200),
            row(96, "DOWN", 0), row(97, "UNKNOWN", 0), row(98, "OK", -1), row(110, "OK", 4))
        val result = LatencyDisplay.reduce(rows, 3, 80, 100)
        assertEquals(2, result.summary.count)
        assertEquals(200L, result.summary.p95Ms)
        assertEquals(150L, result.summary.meanMs)
        assertEquals(2L, result.clockEpoch)
        assertEquals(listOf(90L, 95L), result.samples.map { it.at })
        assertEquals(2, result.errorCount)
    }
}
