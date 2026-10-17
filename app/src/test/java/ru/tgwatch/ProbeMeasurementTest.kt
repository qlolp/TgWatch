package ru.tgwatch

import java.net.HttpURLConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ProbeMeasurementTest {
    @Test fun busyEndpointsDoNotPublishDown() {
        val endpoints = listOf(
            ProbeEndpoint("api.telegram.org", ProbeGroup.WEB, "https://api.telegram.org/"),
            ProbeEndpoint("web.telegram.org", ProbeGroup.WEB, "https://web.telegram.org/"),
            ProbeEndpoint("core.telegram.org", ProbeGroup.WEB, "https://core.telegram.org/"),
        ) + MtProtoEndpoints.all
        val pool = NetworkProbe.executor()
        val extras = NetworkProbe.extraExecutor()
        val coordinator = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(endpoints.size)
        val release = CountDownLatch(1)
        val controls = AtomicInteger()
        val first = coordinator.submit<List<ProbeOutcome>> {
            ProbeBatch(pool, 1_000L).run(endpoints) { ep, _ ->
                started.countDown()
                while (release.count > 0) {
                    try { release.await() } catch (_: InterruptedException) { /* Native work can ignore cancellation. */ }
                }
                ProbeOutcome(ep.name, ep.group, true, 10, "released")
            }
        }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            first.get(3, TimeUnit.SECONDS)
            val report = NetworkProbe(pool, extras, { url ->
                controls.incrementAndGet()
                object : HttpURLConnection(url) {
                    override fun connect() = Unit
                    override fun disconnect() = Unit
                    override fun usingProxy() = false
                    override fun getResponseCode() = 200
                }
            }, { throw AssertionError("A busy MTProto endpoint must not be attempted again") }).checkPrimary()
            assertFalse(report.publishable)
            assertEquals(3, controls.get())
            assertEquals(0, report.results.count { it.group != ProbeGroup.CONTROL && it.reachable })
            assertEquals("No Telegram transport ran; controls alone cannot prove an outage", "UNKNOWN", report.status)
        } finally {
            release.countDown()
            first.cancel(true)
            coordinator.shutdownNow()
            pool.shutdownNow()
            extras.shutdownNow()
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
            assertTrue(coordinator.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test fun measuredFailureAndSuccessfulControlCanClassifyDown() {
        val report = ProbeReport(listOf(
            ProbeOutcome("web", ProbeGroup.WEB, false, -1, "timeout"),
            ProbeOutcome("mt", ProbeGroup.MTPROTO, false, -1, "timeout"),
            ProbeOutcome("control", ProbeGroup.CONTROL, true, 10, "HTTP 200"),
        ))
        assertEquals("TG_DOWN", report.status)
    }

    @Test fun partialAdmissionRetainsPositiveEvidence() {
        val report = ProbeReport(listOf(
            ProbeOutcome("web", ProbeGroup.WEB, true, 10, "HTTP 200"),
            ProbeOutcome("mt", ProbeGroup.MTPROTO, false, -1, "not attempted", ProbeMeasurement.NOT_MEASURED),
        ))
        assertEquals("PARTIAL", report.status)
        assertTrue(report.publishable)
    }

    @Test fun controlsWithoutPrimaryMeasurementsDoNotProveOutage() {
        val report = ProbeReport(listOf(ProbeOutcome("control", ProbeGroup.CONTROL, true, 10, "HTTP 200")))
        assertEquals("UNKNOWN", report.status)
        assertFalse(report.publishable)
    }

    @Test fun rejectedUnstartedWorkIsNotMeasurement() {
        val pool = Executors.newSingleThreadExecutor().apply { shutdown() }
        val result = ProbeBatch(pool, 10).run(listOf(ProbeEndpoint("web", ProbeGroup.WEB, "https://test.invalid/"))) { _, _ ->
            throw AssertionError("Rejected transport must not run")
        }.single()
        assertEquals(ProbeMeasurement.NOT_MEASURED, result.measurement)
        assertFalse(ProbeReport(listOf(result)).publishable)
    }
}
