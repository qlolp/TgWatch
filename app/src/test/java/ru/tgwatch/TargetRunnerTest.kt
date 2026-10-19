package ru.tgwatch

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class TargetRunnerTest {
    private class Connection(val bytes: ByteArray, private val code: Int, val knownSize: Boolean = true) :
        HttpURLConnection(URL("https://example.org")) {
        var readBytes = 0
        var methodUsed = ""
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode(): Int { methodUsed = requestMethod; return code }
        override fun getContentLengthLong(): Long = if (knownSize) bytes.size.toLong() else -1L
        override fun getInputStream() = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also {
                if (it > 0) readBytes += it
            }
            override fun read(): Int = super.read().also { if (it >= 0) readBytes++ }
        }
    }
    private class Fixture(private val open: (URL) -> Connection) : java.io.Closeable {
        val primary: ExecutorService = NetworkProbe.executor()
        val secondary: ExecutorService = NetworkProbe.extraExecutor()
        val probe = NetworkProbe(primary, secondary, openConnection = open)
        val runner = TargetRunner({ ProbeReport(listOf(
            ProbeOutcome("web", ProbeGroup.WEB, true, 10, "ok"),
            ProbeOutcome("mt", ProbeGroup.MTPROTO, true, 20, "ok"))) }, probe::checkSecondary)
        fun check(id: TargetId, endpoint: String? = null): TargetReport = runner.check(
            TargetAttempt(id, 0, 0, 0, 0), TargetDefinition.default(id).copy(enabled = true), endpoint)
        override fun close() { primary.shutdownNow(); secondary.shutdownNow() }
    }

    @Test fun customConditionsReachTheRealParserAndExposeOnlyASafeFailureCategory() {
        Fixture { url -> Connection((if (url.host == "custom.example")
            "{\"private-field\":\"error\"}" else "").toByteArray(), 200) }.use { fixture ->
            val report = fixture.runner.check(TargetAttempt(TargetId.CUSTOM, 0, 0, 0, 0),
                TargetDefinition.default(TargetId.CUSTOM).copy(enabled = true), "https://custom.example",
                ru.tgwatch.probe.HttpProbeCondition(jsonField = "private-field", expectedValue = "private-value"))
            assertEquals(TargetStatus.DOWN, report.status)
            assertEquals(ru.tgwatch.probe.ProbeDetailCode.HTTP_JSON_NOT_CONFIRMED, report.failure)
            assertFalse(report.toString().contains("private-field"))
            assertFalse(report.toString().contains("private-value"))
        }
    }

    @Test fun matrixRequiresBoundedVersionsJson() {
        for (body in listOf("{}", "{\"versions\":[]}", "{\"versions\":[42]}", "not json")) {
            Fixture { url -> Connection((if (url.host == "matrix.org") body else "").toByteArray(), 200) }.use {
                assertEquals(TargetStatus.DOWN, it.check(TargetId.MATRIX).status)
            }
        }
        val valid = Connection("{\"versions\":[\"v1.13\"]}".toByteArray(), 200)
        Fixture { valid }.use {
            assertEquals(TargetStatus.OK, it.check(TargetId.MATRIX).status)
            assertEquals("GET", valid.methodUsed)
            assertFalse(valid.instanceFollowRedirects)
        }
        val huge = Connection(("{\"versions\":[\"v1.13\"],\"padding\":\"" + "x".repeat(80_000) + "\"}").toByteArray(), 200,
            knownSize = false)
        Fixture { url -> if (url.host == "matrix.org") huge else Connection(byteArrayOf(), 200) }.use {
            assertEquals(TargetStatus.DOWN, it.check(TargetId.MATRIX).status)
            assertTrue(huge.readBytes <= 65_536)
        }
    }
    @Test fun customRequires2xxAndNoRedirects() {
        for (code in listOf(301, 401, 404, 503)) {
            val target = Connection(byteArrayOf(), code)
            Fixture { url -> if (url.host == "custom.example") target else Connection(byteArrayOf(), 200) }.use {
                assertEquals(TargetStatus.DOWN, it.check(TargetId.CUSTOM, "https://custom.example").status)
                assertEquals("HEAD", target.methodUsed)
                assertFalse(target.instanceFollowRedirects)
                assertEquals(3_000, target.connectTimeout)
                assertEquals(3_000, target.readTimeout)
            }
        }
        Fixture { Connection(byteArrayOf(), 204) }.use {
            assertEquals(TargetStatus.OK, it.check(TargetId.CUSTOM, "https://custom.example").status)
        }
    }
    @Test fun customFallsBackOnlyFor405Or501() {
        for (firstCode in listOf(405, 501)) {
            val connections = mutableListOf<Connection>()
            Fixture { Connection(byteArrayOf(), if (connections.isEmpty()) firstCode else 204)
                .also(connections::add) }.use {
                assertEquals(TargetStatus.OK, it.check(TargetId.CUSTOM, "https://custom.example").status)
                assertEquals(listOf("HEAD", "GET"), connections.map { c -> c.methodUsed })
            }
        }
    }
    @Test fun noPositiveControlCannotProduceDownAndMissingEndpointIsUnmeasured() {
        Fixture { Connection(byteArrayOf(), 503) }.use {
            val unknown = it.check(TargetId.CUSTOM, "https://custom.example")
            assertEquals(TargetStatus.UNKNOWN, unknown.status)
            assertNull(unknown.latencyMs)
            assertFalse(it.check(TargetId.CUSTOM).publishable)
            assertFalse(it.check(TargetId.CUSTOM, "https://custom.example?token=secret").publishable)
        }
    }
    @Test fun stuckCustomDoesNotDelayTelegramOrReleaseItsPhysicalSlot() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked = Thread {
            Fixture { url ->
                if (url.host == "custom.example") {
                    started.countDown()
                    while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                }
                Connection(byteArrayOf(), 200)
            }.use { fixture -> fixture.check(TargetId.CUSTOM, "https://custom.example") }
        }.apply { start() }
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            Fixture { Connection(byteArrayOf(), 200) }.use {
                val before = System.nanoTime()
                assertEquals(TargetStatus.OK, it.check(TargetId.TELEGRAM).status)
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 250)
                assertTrue(ProbeAdmission.process.snapshot().secondary >= 1)
            }
        } finally { release.countDown(); blocked.join(10_000); assertFalse(blocked.isAlive) }
    }
    @Test fun lateOldEndpointAndNetworkResultsAreRejectedAtomically() {
        val repository = TargetRepository(wallClock = { 1_000L }, elapsedClock = { 200L })
        val cfg = repository.configure(TargetDefinition.default(TargetId.CUSTOM).copy(enabled = true))
        repository.networkChanged(2)
        val oldNetwork = TargetAttempt(TargetId.CUSTOM, cfg.generation, 1, cfg.endpointRevision, 100)
        val ok = TargetReport(TargetStatus.OK, emptyList(), true, 50)
        assertFalse(repository.publish(oldNetwork, ok))
        val valid = oldNetwork.copy(networkRevision = 2)
        assertTrue(repository.publish(valid, ok))
        repository.configure(cfg.definition, endpointChanged = true)
        assertFalse(repository.publish(valid, ok))
        assertNull(repository.snapshot(TargetId.CUSTOM))
    }
    @Test fun activeTargetsExcludeEquivalentInformationalProbes() {
        val definitions = listOf(TargetId.MATRIX, TargetId.WHATSAPP, TargetId.CUSTOM)
            .map { TargetDefinition.default(it).copy(enabled = true) }
        val extras = ExtraProbes.endpoints(true, true, true, "https://custom.example")
        assertTrue(TargetEndpoints.informational(extras, definitions) { "https://custom.example" }.isEmpty())
        val different = TargetEndpoints.informational(extras, definitions) { "https://different.example" }
        assertEquals(1, different.size)
        assertEquals("https://custom.example", different.single().address)
    }

}
