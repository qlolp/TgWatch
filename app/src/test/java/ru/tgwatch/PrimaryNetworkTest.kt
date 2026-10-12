package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PrimaryNetworkTest {
    @Test fun everyProductionDcHasBothNumericAddressFamilies() {
        val endpoints = MtProtoEndpoints.all
        assertEquals(10, endpoints.size)
        assertEquals(endpoints.size, endpoints.map { it.name }.toSet().size)
        for (dc in 1..5) {
            val v4 = endpoints.single { it.name == "MTProto DC$dc" }
            val v6 = endpoints.single { it.name == "MTProto DC$dc (IPv6)" }
            assertEquals(ProbeGroup.MTPROTO, v4.group)
            assertEquals(ProbeGroup.MTPROTO, v6.group)
            assertTrue(InetAddress.getByName(v4.address) is Inet4Address)
            assertTrue(InetAddress.getByName(v6.address) is Inet6Address)
        }
    }

    @Test fun eitherAddressFamilyCanConfirmTelegramWithoutTheOther() {
        for (ipv6Only in listOf(true, false)) {
            val report = check(ipv6Only)
            assertEquals("Only IPv6 available: $ipv6Only", "OK", report.status)
            assertEquals(5, report.results.count { it.group == ProbeGroup.MTPROTO && it.reachable })
            assertTrue(report.results.filter { it.group == ProbeGroup.MTPROTO && it.reachable }
                .all { it.endpoint.endsWith("(IPv6)") == ipv6Only })
        }
    }

    @Test fun theWholePrimaryBatchStartsBeforeAnyTransportFinishes() {
        val started = CountDownLatch(13)
        val release = CountDownLatch(1)
        val pool = NetworkProbe.executor()
        val extras = NetworkProbe.extraExecutor()
        val coordinator = Executors.newSingleThreadExecutor()
        fun enter() {
            started.countDown()
            check(release.await(4, TimeUnit.SECONDS)) { "Primary fixture was not released" }
        }
        val probe = NetworkProbe(pool, extras, { url -> connection(url) { enter() } },
            { GreetingSocket(null, ::enter) })
        val result = coordinator.submit<ProbeReport> { probe.checkPrimary() }
        try {
            assertTrue("All 3 HTTPS and 10 DC requests must be admitted together",
                started.await(2, TimeUnit.SECONDS))
            release.countDown()
            val report = result.get(2, TimeUnit.SECONDS)
            assertEquals(13, report.results.size)
            assertTrue(report.results.all { it.reachable })
            assertEquals("OK", report.status)
        } finally {
            release.countDown()
            result.cancel(true)
            coordinator.shutdownNow()
            pool.shutdownNow()
            extras.shutdownNow()
            assertTrue(coordinator.awaitTermination(2, TimeUnit.SECONDS))
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
            assertTrue(extras.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun webRequestsIdentifyTheSourceVersion() {
        val headers = Collections.synchronizedList(mutableListOf<String>())
        val pool = NetworkProbe.executor()
        val extras = NetworkProbe.extraExecutor()
        try {
            val report = NetworkProbe(pool, extras, { url -> connection(url) {
                headers += getRequestProperty("User-Agent")
            } }, { GreetingSocket(null) }).checkPrimary()
            val version = sequenceOf(java.io.File("../version.properties"), java.io.File("version.properties"))
                .first { it.isFile }.readLines().single { it.startsWith("version=") }.removePrefix("version=")
            assertEquals("OK", report.status)
            assertEquals(3, headers.size)
            assertTrue(headers.all { it == "TgWatch/$version (Android)" })
        } finally { pool.shutdownNow(); extras.shutdownNow() }
    }

    private fun check(ipv6Only: Boolean): ProbeReport {
        val pool = NetworkProbe.executor()
        val extras = NetworkProbe.extraExecutor()
        return try {
            NetworkProbe(pool, extras, { url -> connection(url) }, { GreetingSocket(ipv6Only) }).checkPrimary()
        } finally { pool.shutdownNow(); extras.shutdownNow() }
    }

    private fun connection(url: URL, beforeResponse: HttpURLConnection.() -> Unit = {}): HttpURLConnection =
        object : HttpURLConnection(url) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getResponseCode(): Int { beforeResponse(); return 200 }
        }

    private class GreetingSocket(private val ipv6Only: Boolean?, private val beforeConnect: () -> Unit = {}) : Socket() {
        private val request = ByteArrayOutputStream()
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            beforeConnect()
            val ipv6 = (endpoint as InetSocketAddress).address is Inet6Address
            if (ipv6Only != null && ipv6 != ipv6Only) throw SocketTimeoutException("Address family unavailable in fixture")
        }
        override fun setSoTimeout(timeout: Int) {}
        override fun getOutputStream() = request
        override fun getInputStream(): ByteArrayInputStream {
            val nonce = request.toByteArray().copyOfRange(26, 42)
            val response = MtProtoFixtures.response(nonce)
            return ByteArrayInputStream(byteArrayOf((response.size / 4).toByte()) + response)
        }
    }
}
