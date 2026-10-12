package ru.tgwatch

import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

class ExtraProbesTest {
    @Before @After fun russianDefault() { Lang.ru = true }

    @Test fun customUrlAcceptsOnlyPublicHttpsWithoutCredentialsQueryOrFragment() {
        for (good in listOf("https://example.org", "https://example.org/", "https://status.example.org:8443/health",
            "HTTPS://Example.org/a/b-c_d.e~f", "https://[2001:db8::1]/", "https://xn--e1afmkfd.xn--p1ai/")) {
            assertEquals(good, ExtraProbes.normalizeCustomUrl(good))
        }
        assertEquals("https://example.org/", ExtraProbes.normalizeCustomUrl("  https://example.org/\n"))
        for (bad in listOf(null, "", "   ", "http://example.org/", "ftp://example.org/", "javascript:alert(1)",
            "https:/example.org", "https://", "https:///path", "https://user@example.org/",
            "https://user:secret@example.org/", "https://example.org/?token=1", "https://example.org/?",
            "https://example.org/#top", "https://example.org/a b", "https://пример.рф/",
            "https://example.org/\u0000",
            "https://example.org:0/", "https://example.org:65536/", "https://exa_mple.org/", "//example.org/",
            "https://example.org/" + "a".repeat(237))) {
            assertNull("Accepted $bad", ExtraProbes.normalizeCustomUrl(bad))
        }
        val longest = "https://example.org/" + "a".repeat(236)
        assertEquals(256, longest.length)
        assertEquals(longest, ExtraProbes.normalizeCustomUrl(longest))
    }

    @Test fun endpointsAreOptInDistinctAndInformational() {
        assertTrue(ExtraProbes.endpoints(false, false, false, "https://example.org/").isEmpty())
        assertTrue("A custom switch without a valid address probes nothing",
            ExtraProbes.endpoints(false, false, true, "http://example.org/").isEmpty())
        val all = ExtraProbes.endpoints(true, true, true, "https://status.example.org/health")
        assertEquals(listOf(ExtraProbes.MATRIX_URL, ExtraProbes.WHATSAPP_URL, "https://status.example.org/health"),
            all.map { it.address })
        assertTrue(all.all { it.group == ProbeGroup.EXTRA })
        assertEquals("Batch results are matched by name", all.size, all.map { it.name }.toSet().size)
        assertEquals("Свой URL (status.example.org)", all.last().name)
        Lang.ru = false
        assertEquals("Custom URL (status.example.org)",
            ExtraProbes.endpoints(false, false, true, "https://status.example.org/health").single().name)
    }

    @Test fun extrasNeverChangeTelegramClassificationLatencyOrReason() {
        val extrasVariants = listOf(
            emptyList(),
            listOf(ProbeOutcome("Matrix", ProbeGroup.EXTRA, true, 1, "HTTP 200")),
            listOf(ProbeOutcome("Matrix", ProbeGroup.EXTRA, false, -1, "Timeout"),
                ProbeOutcome("WhatsApp Web", ProbeGroup.EXTRA, true, 2, "HTTP 302"),
                ProbeOutcome("Свой URL (a)", ProbeGroup.EXTRA, true, 3, "HTTP 204")))
        for (mask in 0 until 8) for (connected in listOf(true, false)) {
            val base = listOf(
                ProbeOutcome("api.telegram.org", ProbeGroup.WEB, mask and 1 != 0, 40, "HTTP 404"),
                ProbeOutcome("MTProto DC2", ProbeGroup.MTPROTO, mask and 2 != 0, 50, "resPQ"),
                ProbeOutcome("Google 204", ProbeGroup.CONTROL, mask and 4 != 0, 30, "HTTP 204"))
            val expected = ProbeReport(base, connected)
            for (extras in extrasVariants) {
                val report = ProbeReport(base + extras, connected)
                assertEquals("mask $mask extras $extras", expected.status, report.status)
                assertEquals(expected.latencyMs, report.latencyMs)
                assertEquals(expected.reason, report.reason)
            }
        }
        // Reachable extras alone prove nothing about Telegram or the internet.
        assertEquals("UNKNOWN", ProbeReport.classify(listOf(
            ProbeOutcome("Matrix", ProbeGroup.EXTRA, true, 1, "HTTP 200")), true))
    }

    @Test fun networkProbeRunsExtrasAfterTelegramWithoutAffectingStatus() {
        val requested = mutableListOf<String>()
        val pool = NetworkProbe.executor()
        val extraPool = NetworkProbe.extraExecutor()
        try {
            val report = NetworkProbe(pool, extraPool, { url ->
                synchronized(requested) { requested += url.host }
                object : HttpURLConnection(url) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                    override fun getResponseCode(): Int = when {
                        url.host.endsWith("telegram.org") -> throw UnknownHostException(url.host)
                        else -> 200
                    }
                }
            }, { object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) { throw SocketTimeoutException("blocked") }
            } }).check(ExtraProbes.endpoints(true, true, true, "https://status.example.org/"))
            assertEquals("Controls reachable and Telegram blocked stays TG_DOWN", "TG_DOWN", report.status)
            val extras = report.results.filter { it.group == ProbeGroup.EXTRA }
            assertEquals(3, extras.size)
            assertTrue(extras.all { it.reachable && it.detail == "HTTP 200" })
            assertTrue(requested.containsAll(listOf("matrix.org", "web.whatsapp.com", "status.example.org")))
            assertEquals(-1L, report.latencyMs)
        } finally { pool.shutdownNow(); extraPool.shutdownNow() }
    }

    @Test fun extrasWithoutEnabledServicesAddNoRequests() {
        val pool = NetworkProbe.executor()
        val extraPool = NetworkProbe.extraExecutor()
        val requested = mutableListOf<URL>()
        try {
            val report = NetworkProbe(pool, extraPool, { url ->
                synchronized(requested) { requested += url }
                object : HttpURLConnection(url) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                    override fun getResponseCode() = 200
                }
            }, { object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) { throw SocketTimeoutException("blocked") }
            } }).check(ExtraProbes.endpoints(false, false, false, null))
            assertEquals("PARTIAL", report.status)
            assertTrue(report.results.none { it.group == ProbeGroup.EXTRA })
            assertTrue(requested.all { it.host.endsWith("telegram.org") })
        } finally { pool.shutdownNow(); extraPool.shutdownNow() }
    }
}
