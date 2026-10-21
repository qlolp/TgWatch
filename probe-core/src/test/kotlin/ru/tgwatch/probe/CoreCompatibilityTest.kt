package ru.tgwatch.probe

import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CoreCompatibilityTest {
    @Test fun busyDuplicateDoesNotReplaceTheFirstMeasuredResult() {
        val pool = Executors.newSingleThreadExecutor()
        val released = CountDownLatch(1)
        val endpoint = ProbeEndpoint("same", ProbeGroup.WEB, "https://fixture.invalid/")
        try {
            val result = ProbeBatch(pool, 1000, ProbeAdmission(1, 0)).runMapped(listOf(endpoint, endpoint),
                { _, detail, _ ->
                    assertEquals(ProbeDetailCode.BUSY, detail.code)
                    released.countDown()
                    "busy"
                }) { _, _ ->
                    assertTrue(released.await(1, TimeUnit.SECONDS))
                    "measured"
                }
            assertEquals(listOf("measured", "busy"), result)
        } finally { released.countDown(); pool.shutdownNow() }
    }

    @Test fun sameDisplayNameDoesNotMixDifferentEndpointResults() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val endpoints = listOf("https://a.invalid/", "https://b.invalid/").map {
                ProbeEndpoint("same", ProbeGroup.WEB, it)
            }
            val result = ProbeBatch(pool, 1000, ProbeAdmission(2, 0)).runMapped(endpoints,
                { _, _, _ -> "fallback" }) { endpoint, _ -> endpoint.address }
            assertEquals(endpoints.map { it.address }, result)
        } finally { pool.shutdownNow() }
    }

    @Test fun coreClassLoaderHasNoAndroidOrPhoneDependencies() {
        val loader = ProbeReport::class.java.classLoader
        for (name in listOf("android.content.Context", "ru.tgwatch.Lang", "ru.tgwatch.BuildConfig"))
            assertThrows(ClassNotFoundException::class.java) { loader.loadClass(name) }
    }
    @Test fun fullPhoneEndpointSetAndClassificationStayIntact() {
        val endpoints = NetworkProbe.primaryEndpoints
        assertEquals(13, endpoints.size)
        assertEquals(3, endpoints.count { it.group == ProbeGroup.WEB })
        assertEquals(10, endpoints.count { it.group == ProbeGroup.MTPROTO })
        assertEquals(10, MtProtoEndpoints.all.map { MtProtoEndpoints.idFor(it.name) }.distinct().size)
        fun outcome(group: ProbeGroup, reachable: Boolean, measurement: ProbeMeasurement =
            if (reachable) ProbeMeasurement.SUCCESS else ProbeMeasurement.FAILURE) =
            ProbeOutcome(group.name, group, reachable, 20, ProbeDetail(ProbeDetailCode.NONE), measurement)
        val web = outcome(ProbeGroup.WEB, true)
        val mt = outcome(ProbeGroup.MTPROTO, true)
        assertEquals("OK", ProbeReport(listOf(web, mt)).status)
        assertEquals("PARTIAL", ProbeReport(listOf(mt)).status)
        assertEquals("UNKNOWN", ProbeReport(listOf(outcome(ProbeGroup.WEB, false, ProbeMeasurement.NOT_MEASURED),
            outcome(ProbeGroup.CONTROL, true))).status)
        assertEquals("TG_DOWN", ProbeReport(listOf(outcome(ProbeGroup.WEB, false),
            outcome(ProbeGroup.CONTROL, true))).status)
        assertEquals("NO_NETWORK", ProbeReport(listOf(web, mt), false).status)
    }
    @Test fun injectedConnectionIsClosedByCancellationAndGenericMappingKeepsCallerValues() {
        val closed = CountDownLatch(1)
        val started = CountDownLatch(1)
        val transport = object : ProbeTransport {
            override val userAgent = "fixture"
            override fun openSocket() = Socket()
            override fun openConnection(url: URL) = object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun usingProxy() = false
                override fun disconnect() { closed.countDown() }
                override fun getResponseCode(): Int { started.countDown(); closed.await(); return 200 }
            }
        }
        val cancellation = ProbeCancellation()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val endpoint = ProbeEndpoint("web", ProbeGroup.WEB, "https://fixture.invalid/")
            val request = worker.submit<ProbeOutcome> { EndpointProbe(transport).probe(endpoint, cancellation) }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            cancellation.close()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            request.get(2, TimeUnit.SECONDS)
            val result = ProbeBatch(worker, 1000, ProbeAdmission(1, 0)).runMapped(listOf(endpoint),
                { _, _, _ -> "fallback" }) { ep, _ -> "mapped:${ep.name}" }
            assertEquals(listOf("mapped:web"), result)
        } finally { cancellation.close(); closed.countDown(); worker.shutdownNow() }
    }
}
