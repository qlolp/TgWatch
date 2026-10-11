package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class ScheduleDiagnosticsTest {
    @Test fun lateArrivalUsesElapsedTimeAndRecordsIdleOnlyAsObservation() {
        val metrics = ScheduleDiagnostics()
        metrics.scheduled(1_000, 30_000)
        metrics.arrived(36_000, idleObserved = true)
        val s = metrics.snapshot()
        assertEquals(1L, s.scheduledArrivals)
        assertEquals(1L, s.lateArrivals)
        assertEquals(5_000L, s.totalDelayMs)
        assertEquals(5_000L, s.maxDelayMs)
        assertEquals(1L, s.idleObservedArrivals)
    }
    @Test fun manualReplacementAndOverlapAreExcludedFromScheduledMetrics() {
        val metrics = ScheduleDiagnostics()
        metrics.scheduled(0, 30_000)
        metrics.manual(overlapping = true)
        metrics.arrived(90_000, idleObserved = true)
        assertEquals(0L, metrics.snapshot().scheduledArrivals)
        assertEquals(1L, metrics.snapshot().manualRequests)
        assertEquals(1L, metrics.snapshot().overlappingRequests)
    }
    @Test fun alarmAndHandlerArrivalCountOnce() {
        val metrics = ScheduleDiagnostics()
        metrics.scheduled(100, 30_000)
        metrics.arrived(30_100, false)
        metrics.arrived(30_102, false)
        assertEquals(1L, metrics.snapshot().scheduledArrivals)
        assertEquals(0L, metrics.snapshot().lateArrivals)
    }
    @Test fun earlierArrivalAndCanceledNetworkRetryDoNotReportDelay() {
        val metrics = ScheduleDiagnostics()
        metrics.scheduled(1_000, 30_000)
        metrics.arrived(2_000, false)
        assertEquals(0L, metrics.snapshot().scheduledArrivals)
        metrics.canceled()
        metrics.arrived(60_000, false)
        assertEquals(0L, metrics.snapshot().scheduledArrivals)
    }
    @Test fun arrivalDuringAnActiveProbeIsCountedAsOverlapNotScheduleDelay() {
        val metrics = ScheduleDiagnostics()
        metrics.scheduled(1_000, 30_000)
        metrics.arrived(60_000, idleObserved = true, overlapping = true)
        assertEquals(1L, metrics.snapshot().overlappingRequests)
        assertEquals(0L, metrics.snapshot().scheduledArrivals)
        assertEquals(0L, metrics.snapshot().lateArrivals)
        metrics.arrived(60_001, false)
        assertEquals(0L, metrics.snapshot().scheduledArrivals)
    }
    @Test fun negligibleHandlerJitterIsNotLate() {
        val metrics = ScheduleDiagnostics()
        metrics.scheduled(0, 30_000)
        metrics.arrived(30_500, false)
        assertEquals(0L, metrics.snapshot().lateArrivals)
    }
}
