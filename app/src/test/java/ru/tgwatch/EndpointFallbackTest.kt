package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.SSLHandshakeException

class EndpointFallbackTest {
    @Test fun backupDcCanConfirmMtprotoWhenOriginalTwoDcsFail() {
        val report = checkWithOnlyDc3Available(false)
        assertEquals("OK", report.status)
        assertTrue(report.results.any { it.group == ProbeGroup.MTPROTO && it.reachable })
    }

    @Test fun backupWithWrongNonceDoesNotProduceFalseSuccess() {
        assertEquals("PARTIAL", checkWithOnlyDc3Available(true).status)
    }

    @Test fun interceptedTelegramHostsCannotConfirmWebReachability() {
        // A portal, DNS hijack or ISP proxy cannot present a Telegram certificate, so the
        // HTTPS probe fails in TLS before any HTTP code could count as "server answered".
        val urls = java.util.Collections.synchronizedList(mutableListOf<URL>())
        val pool = NetworkProbe.executor()
        val report = try {
            NetworkProbe(pool,
                { url -> urls += url; object : HttpURLConnection(url) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                    override fun getResponseCode(): Int =
                        if (url.host.endsWith("telegram.org")) throw SSLHandshakeException("certificate mismatch") else 200
                } },
                { object : Socket() {
                    override fun connect(endpoint: SocketAddress, timeout: Int) { throw SocketTimeoutException("blocked") }
                } }).check()
        } finally { pool.shutdownNow() }
        assertEquals("TG_DOWN", report.status)
        assertTrue(urls.isNotEmpty())
        assertTrue(urls.all { it.protocol == "https" })
        assertTrue(report.results.filter { it.group == ProbeGroup.WEB }.all { !it.reachable && it.detail.startsWith("TLS") })
    }

    private fun checkWithOnlyDc3Available(wrongNonce: Boolean): ProbeReport {
        val pool = NetworkProbe.executor()
        try {
            return NetworkProbe(pool,
                { url -> object : HttpURLConnection(url) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                    override fun getResponseCode() = 200
                } },
                { GreetingSocket(wrongNonce) }).check()
        } finally { pool.shutdownNow() }
    }

    private class GreetingSocket(private val wrongNonce: Boolean) : Socket() {
        private val request = ByteArrayOutputStream()
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val host = (endpoint as InetSocketAddress).address.hostAddress
            if (host != "149.154.175.100") throw SocketTimeoutException("DC unavailable in fixture")
        }
        override fun setSoTimeout(timeout: Int) {}
        override fun getOutputStream() = request
        override fun getInputStream(): ByteArrayInputStream {
            // Abridged header (2 bytes), unencrypted header + constructor (24), then nonce.
            val nonce = request.toByteArray().copyOfRange(26, 42)
            if (wrongNonce) nonce[0] = (nonce[0].toInt() xor 1).toByte()
            val response = MtProtoFixtures.response(nonce)
            return ByteArrayInputStream(byteArrayOf((response.size / 4).toByte()) + response)
        }
    }
}
