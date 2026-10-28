package ru.tgwatch.probe

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class HttpProbeConditionTest {
    private class Connection(val body: ByteArray, val status: Int = 200,
        val length: Long = body.size.toLong(), val encoding: String? = null,
        val stream: InputStream? = null) : HttpURLConnection(URL("https://fixture.invalid/health")) {
        var reads = 0
        var usedMethod = ""
        val disconnected = CountDownLatch(1)
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected.countDown() }
        override fun getResponseCode(): Int { usedMethod = requestMethod; return status }
        override fun getContentLengthLong() = length
        override fun getContentEncoding() = encoding
        override fun getInputStream(): InputStream = stream ?: object : ByteArrayInputStream(body) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also {
                if (it > 0) reads += it
            }
        }
        override fun getErrorStream() = inputStream
    }
    private class Transport(private val connections: List<Connection>) : ProbeTransport {
        val opened = mutableListOf<Connection>()
        override val userAgent = "fixture"
        override fun openSocket() = Socket()
        override fun openConnection(url: URL) = connections[opened.size].also(opened::add)
    }
    private fun endpoint(condition: HttpProbeCondition? = null) = ProbeEndpoint("custom", ProbeGroup.EXTRA,
        "https://fixture.invalid/health", HttpProbeMode.STRICT_2XX, condition)
    private fun jsonCondition(value: String = "ok") = HttpProbeCondition(jsonField = "status", expectedValue = value)
    private fun check(connection: Connection, condition: HttpProbeCondition? = jsonCondition()): HttpProbeResult =
        ProbeCancellation().use { HttpEndpointProbe(Transport(listOf(connection))).check(endpoint(condition), it) }
    private fun confirm(text: String) = check(Connection(text.toByteArray()))

    @Test fun exactCodeCanRequireNonSuccessAndRejectsUnexpected200() {
        val rejected = check(Connection(byteArrayOf(), 200), HttpProbeCondition(expectedCode = 204))
        assertFalse(rejected.confirmed)
        assertEquals(HttpConditionFailure.CODE_MISMATCH, rejected.failure)
        assertTrue(check(Connection(byteArrayOf(), 503), HttpProbeCondition(expectedCode = 503)).confirmed)
    }
    @Test fun nestedJsonPathConfirmsStatuspageStyleIndicator() {
        val path = HttpProbeCondition(jsonField = "status.indicator", expectedValue = "none")
        val healthy = """{"page":{"name":"Discord"},"status":{"indicator":"none","description":"ok"}}"""
        val degraded = """{"page":{"name":"Discord"},"status":{"indicator":"minor","description":"slow"}}"""
        val shallow = """{"status":{"indicator":"none"}}"""
        val flat = """{"status":"none"}"""
        assertTrue(check(Connection(healthy.toByteArray()), path).confirmed)
        assertTrue(check(Connection(shallow.toByteArray()), path).confirmed)
        assertFalse(check(Connection(degraded.toByteArray()), path).confirmed)
        assertFalse(check(Connection(flat.toByteArray()), path).confirmed)
        // Wrong type at the path terminal is not confirmed.
        assertFalse(check(Connection("""{"status":{"indicator":1}}""".toByteArray()), path).confirmed)
        // Terminal string at a deeper object is not silently promoted.
        assertFalse(check(Connection("""{"status":{"indicator":{"value":"none"}}}""".toByteArray()), path).confirmed)
    }

    @Test fun nestedPathDoesNotMatchSiblingKeysOrUnrelatedDepth() {
        val path = HttpProbeCondition(jsonField = "status.indicator", expectedValue = "none")
        for (text in listOf(
            """{"status":{"description":"none"}}""",
            """{"other":{"indicator":"none"}}""",
            """{"status":[{"indicator":"none"}]}""",
            """{"status":"none"}""",
            """{"status":{"indicator":"none","indicator":"none"}}""")) {
            assertFalse(text, check(Connection(text.toByteArray()), path).confirmed)
        }
        val nestedDuplicate = HttpProbeCondition(jsonField = "a.b", expectedValue = "x")
        assertFalse(check(Connection("""{"a":{"b":"x"},"a":{"b":"x"}}""".toByteArray()), nestedDuplicate).confirmed)
    }

    @Test fun jsonUsesOneGetAndRequiresStringValueAndMatchingCode() {
        val connection = Connection("{\"status\":\"ok\"}".toByteArray())
        assertTrue(check(connection).confirmed)
        assertEquals("GET", connection.usedMethod)
        assertEquals("application/json", connection.getRequestProperty("Accept"))
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(3000, connection.connectTimeout)
        assertEquals(3000, connection.readTimeout)
        for (text in listOf("{}", "{\"status\":\"error\"}", "{\"status\":true}", "{\"status\":[\"ok\"]}")) {
            val rejected = confirm(text)
            assertFalse(text, rejected.confirmed)
            assertEquals(HttpConditionFailure.JSON_NOT_CONFIRMED, rejected.failure)
        }
        assertFalse(check(Connection("{\"status\":\"ok\"}".toByteArray(), 500)).confirmed)
        assertTrue(check(Connection("{\"status\":\"\"}".toByteArray()), jsonCondition("")).confirmed)
    }
    @Test fun bodySubstringAndSeveralJsonConditionsMustAllMatch() {
        val condition = HttpProbeCondition(bodySubstring = "\"indicator\":\"none\"",
            jsonConditions = listOf(HttpJsonCondition("status.indicator", "none"),
                HttpJsonCondition("page.name", "Example")))
        val healthy = """{"status":{"indicator":"none"},"page":{"name":"Example"}}"""
        val missingSubstring = """{"status":{"indicator":"none"},"page":{"name":"Example"},"note":"changed"}"""
        val wrongJson = """{"status":{"indicator":"none"},"page":{"name":"Other"}}"""
        assertTrue(check(Connection(healthy.toByteArray()), condition).confirmed)
        assertFalse(check(Connection(missingSubstring.toByteArray()), condition.copy(bodySubstring = "not present"))
            .confirmed)
        assertFalse(check(Connection(wrongJson.toByteArray()), condition).confirmed)
    }

    @Test fun bodyOnlyAndRequestHeadersUseOneGetAndNeverExposeValues() {
        val body = Connection("service healthy".toByteArray())
        val condition = HttpProbeCondition(bodySubstring = "healthy",
            headers = listOf(HttpRequestHeader("Authorization", "******")))
        val endpoint = endpoint(condition)
        assertTrue(check(body, condition).confirmed)
        assertEquals("GET", body.usedMethod)
        assertEquals("******", body.getRequestProperty("Authorization"))
        for (secret in listOf("private-token", "healthy", "Authorization", "fixture.invalid"))
            assertFalse(endpoint.toString().contains(secret))
        val headerOnly = Connection(byteArrayOf(), 204)
        assertTrue(check(headerOnly, HttpProbeCondition(headers = listOf(HttpRequestHeader("X-Health", "private"))))
            .confirmed)
        assertEquals("HEAD", headerOnly.usedMethod)
    }
    @Test fun wholeObjectRejectsNestedDuplicatesTrailingDataAndExcessiveDepth() {
        for (text in listOf("{\"status\":\"ok\",\"status\":\"ok\"}",
            "{\"status\":\"ok\",\"unused\":{\"x\":1,\"x\":2}}",
            "{\"status\":\"ok\"} true", "{\"status\":\"ok\"",
            "{\"status\":\"ok\",\"extra\":" + "[".repeat(10) + "0" + "]".repeat(10) + "}")) {
            assertFalse(text, confirm(text).confirmed)
        }
        assertTrue(confirm("{\"status\":\"ok\",\"extra\":" + "[".repeat(9) + "0" + "]".repeat(9) + "}").confirmed)
    }
    @Test fun malformedUtf8AndUnexpectedCompressionFailWithoutEchoingBody() {
        assertEquals(HttpConditionFailure.JSON_NOT_CONFIRMED,
            check(Connection(byteArrayOf(0xc3.toByte(), 0x28))).failure)
        val compressed = Connection("{\"status\":\"ok\"}".toByteArray(), encoding = "gzip")
        assertFalse(check(compressed).confirmed)
        assertEquals(0, compressed.reads)
        assertTrue(check(Connection("{\"status\":\"ok\"}".toByteArray(), encoding = "identity")).confirmed)
    }
    @Test fun exactKnownCapWorksButUnknownCapOversizeAndTruncationFail() {
        val prefix = "{\"status\":\"ok\"}"
        val cap = 32 * 1024
        val bytes = prefix.padEnd(cap).toByteArray()
        assertTrue(check(Connection(bytes)).confirmed)
        val unknown = Connection(bytes, length = -1)
        assertFalse(check(unknown).confirmed)
        assertTrue(unknown.reads <= cap)
        val oversized = Connection(bytes + 32.toByte())
        assertFalse(check(oversized).confirmed)
        assertEquals(0, oversized.reads)
        assertFalse(check(Connection(prefix.toByteArray(), length = cap.toLong())).confirmed)
        assertTrue(check(Connection(prefix.padEnd(cap - 1).toByteArray(), length = -1)).confirmed)
    }
    @Test fun defaultHeadAndUnsupportedMethodFallbackStayUnchanged() {
        for (code in listOf(405, 501)) {
            val first = Connection(byteArrayOf(), code)
            val second = Connection(byteArrayOf(), 204)
            val transport = Transport(listOf(first, second))
            ProbeCancellation().use { assertTrue(HttpEndpointProbe(transport).check(endpoint(), it).confirmed) }
            assertEquals(listOf("HEAD", "GET"), transport.opened.map { it.usedMethod })
        }
        val ordinary = Connection(byteArrayOf(), 200)
        assertTrue(check(ordinary, null).confirmed)
        assertEquals("HEAD", ordinary.usedMethod)
        assertEquals(0, ordinary.reads)
    }
    @Test fun conditionBoundsAndDebugRenderingNeverExposePrivateValues() {
        for (code in listOf(199, 600)) assertThrows(IllegalArgumentException::class.java) {
            HttpProbeCondition(expectedCode = code)
        }
        for (field in listOf("", "x".repeat(65), "status.", ".status", "a..b", "a.b.c.d"))
            assertThrows(IllegalArgumentException::class.java) {
                HttpProbeCondition(jsonField = field, expectedValue = "secret")
            }
        assertThrows(IllegalArgumentException::class.java) { HttpProbeCondition(jsonField = "status") }
        assertThrows(IllegalArgumentException::class.java) { HttpProbeCondition(expectedValue = "secret") }
        assertThrows(IllegalArgumentException::class.java) {
            HttpProbeCondition(jsonField = "status", expectedValue = "x".repeat(257))
        }
        val value = HttpProbeCondition(jsonField = "private-field", expectedValue = "private-token")
        assertFalse(value.toString().contains("private-field"))
        assertFalse(value.toString().contains("private-token"))
    }
    @Test fun conditionListsAreBoundedCaseInsensitiveAndImmutable() {
        val tooManyJson = List(4) { HttpJsonCondition("status", "ok") }
        assertThrows(IllegalArgumentException::class.java) {
            HttpProbeCondition(jsonField = "status", expectedValue = "ok", jsonConditions = tooManyJson)
        }
        assertEquals(4, HttpProbeCondition(jsonConditions = tooManyJson).allJsonConditions().size)
        assertThrows(IllegalArgumentException::class.java) {
            HttpProbeCondition(bodySubstring = "x".repeat(257))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpProbeCondition(headers = listOf(HttpRequestHeader("X-Key", "one"),
                HttpRequestHeader("x-key", "two")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpProbeCondition(headers = listOf(HttpRequestHeader("X-Key", "bad\r\nInjected: true")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpProbeCondition(headers = listOf(HttpRequestHeader("Accept-Encoding", "gzip")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpProbeCondition(headers = List(9) { HttpRequestHeader("X-Key-$it", "value") })
        }
        val mutable = mutableListOf(HttpJsonCondition("status", "ok"))
        val condition = HttpProbeCondition(jsonConditions = mutable)
        mutable.clear()
        assertEquals(1, condition.jsonConditions.size)
        assertThrows(UnsupportedOperationException::class.java) { (condition.jsonConditions as MutableList).clear() }
    }
    @Test fun cancellationDisconnectsButPhysicalSlotStaysOwnedUntilBodyReturns() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stream = object : InputStream() {
            override fun read(): Int { block(); return -1 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { block(); return -1 }
            private fun block() {
                started.countDown()
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
            }
        }
        val connection = Connection(byteArrayOf(), length = -1, stream = stream)
        val admission = ProbeAdmission(0, 1)
        val worker = Executors.newSingleThreadExecutor()
        val owner = Executors.newSingleThreadExecutor()
        val endpoint = endpoint(jsonCondition())
        try {
            val result = owner.submit<List<ProbeOutcome>> { ProbeBatch(worker, 1000, admission, ProbeLane.SECONDARY)
                .run(listOf(endpoint), EndpointProbe(Transport(listOf(connection)))::probe) }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val timedOut = result.get(2, TimeUnit.SECONDS).single()
            assertEquals(ProbeMeasurement.FAILURE, timedOut.measurement)
            assertEquals(ProbeDetailCode.TIME_LIMIT, timedOut.detail.code)
            assertTrue(connection.disconnected.await(2, TimeUnit.SECONDS))
            assertEquals(1, admission.snapshot().secondary)
            assertNull(admission.tryAcquire("${endpoint.group}:${endpoint.name}:${endpoint.address}", ProbeLane.SECONDARY))
        } finally {
            release.countDown(); owner.shutdownNow(); worker.shutdownNow()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
        assertEquals(0, admission.snapshot().secondary)
    }
}
