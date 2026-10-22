package ru.tgwatch

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test
import ru.tgwatch.probe.ProbeCancellation

class RegionalHttpTransportTest {
    private class Connection(code: Int, body: String = "", private val location: String? = null,
        val stream: InputStream = ByteArrayInputStream(body.toByteArray())) :
        HttpsURLConnection(URL("https://gemini.google.com/")) {
        var disconnected = false
        init { responseCode = code }
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getResponseCode() = responseCode
        override fun getContentType() = "text/html"
        override fun getHeaderField(name: String): String? = if (name == "Location") location else null
        override fun getInputStream() = stream
        override fun getErrorStream() = stream
        override fun getCipherSuite() = "TLS_TEST"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    @Test fun fixedHttpsRequestKeepsTlsValidationAndNeverSendsCookies() {
        val connection = Connection(200, "<h1>Welcome</h1>")
        val transport = RegionalHttpTransport { uri ->
            assertEquals(URI(RegionalService.GEMINI.address), uri)
            connection
        }
        val result = transport.check(RegionalService.GEMINI, ProbeCancellation())
        assertEquals(RegionalStatus.WEB_REACHABLE, result.status)
        assertEquals(3000, connection.connectTimeout)
        assertEquals(3000, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals("", connection.getRequestProperty("Cookie"))
        assertTrue(connection.disconnected)
        assertSame(HttpsURLConnection.getDefaultHostnameVerifier(), connection.hostnameVerifier)
    }

    @Test fun loginRedirectDoesNotFetchTheAccountOrSendCredentials() {
        var requests = 0
        val transport = RegionalHttpTransport {
            requests++
            Connection(302, location = "https://accounts.google.com/login?continue=private")
        }
        assertEquals(RegionalStatus.AUTH_REQUIRED, transport.check(RegionalService.GEMINI, ProbeCancellation()).status)
        assertEquals(1, requests)
    }

    @Test fun unsafeRedirectsAndRedirectLoopsAreBounded() {
        for (location in listOf("http://gemini.google.com/", "https://user:secret@gemini.google.com/",
            "https://gemini.google.com.attacker.example/", "https://gemini.google.com:444/")) {
            var requests = 0
            val result = RegionalHttpTransport { requests++; Connection(302, location = location) }
                .check(RegionalService.GEMINI, ProbeCancellation())
            assertEquals(RegionalStatus.UNKNOWN, result.status)
            assertEquals(1, requests)
        }
        var requests = 0
        RegionalHttpTransport { requests++; Connection(302, location = "/loop") }
            .check(RegionalService.GEMINI, ProbeCancellation())
        assertEquals(3, requests)
    }

    @Test fun responseLimitCannotTurnARegionalPrefixIntoConfirmedEvidence() {
        var consumed = 0
        val stream = object : InputStream() {
            override fun read(): Int { consumed++; return 'x'.code }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                consumed += length
                bytes.fill('x'.code.toByte(), offset, offset + length)
                return length
            }
        }
        val connection = Connection(403, stream = stream)
        assertEquals(RegionalStatus.UNKNOWN, RegionalHttpTransport { connection }
            .check(RegionalService.GEMINI, ProbeCancellation()).status)
        assertEquals(32769, consumed)
        assertTrue(connection.disconnected)
    }

    @Test fun certificateFailureIsANetworkErrorWithNoExceptionText() {
        val result = RegionalHttpTransport { throw SSLHandshakeException("secret network detail") }
            .check(RegionalService.GEMINI, ProbeCancellation())
        assertEquals(RegionalStatus.NETWORK_ERROR, result.status)
        assertEquals(RegionalFailure.TLS, result.failure)
        assertFalse(result.toString().contains("secret"))
    }

    @Test fun cancellationDisconnectsAnInFlightRequestAndRejectsItsResponse() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val cancellation = ProbeCancellation()
        val connection = Connection(200, stream = object : InputStream() {
            override fun read(): Int {
                entered.countDown()
                assertTrue(released.await(5, TimeUnit.SECONDS))
                return -1
            }
        })
        cancellation.register { released.countDown() }
        var result: RegionalHttpResponse? = null
        val worker = Thread { result = RegionalHttpTransport { connection }.check(RegionalService.GEMINI, cancellation) }
        worker.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        cancellation.close()
        worker.join(3000)
        assertFalse(worker.isAlive)
        assertEquals(RegionalStatus.NETWORK_ERROR, result?.status)
        assertEquals(RegionalFailure.TIMEOUT, result?.failure)
    }
}
