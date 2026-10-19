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
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ExtraProbeWorkerIsolationTest {
    @Test fun primaryPublicationDoesNotWaitForDelayedExtras() {
        val pool = NetworkProbe.executor()
        val extraPool = NetworkProbe.extraExecutor()
        val coordinator = Executors.newSingleThreadExecutor()
        val transport = Transport()
        val probe = NetworkProbe(pool, extraPool, transport::connection, transport::socket)
        val gate = TelegramGate()
        transport.gate = gate
        val diagnostics = ExtraProbeCoordinator()
        val published = CountDownLatch(1)
        val publication = java.util.concurrent.atomic.AtomicReference<ProbeReport>()
        val check = coordinator.submit {
            val primary = probe.checkPrimary()
            publication.set(primary)
            published.countDown()
            val token = diagnostics.begin(7, 19)
            assertTrue(diagnostics.submit(token, {
                probe.checkExtras(ExtraProbes.endpoints(true, true, true, "https://status.example.org/"))
            }, {}))
        }
        try {
            assertTrue(gate.started.await(2, TimeUnit.SECONDS))
            gate.release.countDown()
            assertTrue(transport.extrasStarted.await(2, TimeUnit.SECONDS))
            assertTrue("Telegram primary must publish while EXTRA transports are still blocked",
                published.await(500, TimeUnit.MILLISECONDS))
            check.get(1, TimeUnit.SECONDS)
            assertEquals(1L, transport.releaseExtras.count)
            assertEquals("OK", publication.get().status)
            assertTrue(publication.get().results.none { it.group == ProbeGroup.EXTRA })
        } finally {
            gate.release.countDown()
            check.cancel(true)
            diagnostics.close()
            assertTrue("Split coordinator cancellation closes bound EXTRA connections",
                transport.extrasCancelled.await(2, TimeUnit.SECONDS))
            transport.releaseExtras.countDown()
            coordinator.shutdownNow()
            pool.shutdownNow()
            extraPool.shutdownNow()
            assertTrue(coordinator.awaitTermination(2, TimeUnit.SECONDS))
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
            assertTrue(extraPool.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun sharedTelegramAndExtraExecutorIsRejectedBeforeAnyRequest() {
        val pool = NetworkProbe.executor()
        try {
            try {
                NetworkProbe(pool, pool)
                fail("A caller must supply a distinct informational executor")
            } catch (expected: IllegalArgumentException) {
                assertEquals("EXTRA probes require a separate executor", expected.message)
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun extraExecutorHasThreeReusableWorkersAndRejectsAnOverflowWithoutQueueing() {
        val pool = NetworkProbe.extraExecutor() as ThreadPoolExecutor
        val started = CountDownLatch(3)
        val release = CountDownLatch(1)
        val firstThreads = Collections.synchronizedSet(mutableSetOf<Thread>())
        try {
            repeat(3) {
                pool.execute {
                    firstThreads += Thread.currentThread()
                    started.countDown()
                    release.await(3, TimeUnit.SECONDS)
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            try {
                pool.execute { fail("Overflow task must never run") }
                fail("A fourth extra must be rejected rather than queued")
            } catch (_: RejectedExecutionException) {}
            assertTrue(pool.queue.isEmpty())
            assertEquals(3, pool.largestPoolSize)
            assertTrue(firstThreads.all { it.isDaemon && it.name == "tg-extra-probe" })
            release.countDown()
            awaitIdle(pool)

            val secondThreads = Collections.synchronizedSet(mutableSetOf<Thread>())
            val endpoints = (1..3).map { ProbeEndpoint("extra$it", ProbeGroup.EXTRA, "extra$it") }
            val results = ProbeBatch(pool, 1_000).run(endpoints) { endpoint, _ ->
                secondThreads += Thread.currentThread()
                ProbeOutcome(endpoint.name, endpoint.group, true, 1, "OK")
            }
            assertTrue(results.all { it.reachable })
            assertTrue(firstThreads.containsAll(secondThreads))
            assertEquals(3, pool.largestPoolSize)
            assertTrue(pool.queue.isEmpty())
        } finally {
            release.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun interruptingTheCoordinatorCancelsExtrasAndLeavesTelegramWorkersAvailable() {
        val pool = NetworkProbe.executor()
        val extraPool = NetworkProbe.extraExecutor()
        val coordinator = Executors.newSingleThreadExecutor()
        val transport = Transport()
        val probe = NetworkProbe(pool, extraPool, transport::connection, transport::socket)
        val gate = TelegramGate()
        transport.gate = gate
        val check = coordinator.submit<ProbeReport> {
            probe.check(ExtraProbes.endpoints(true, true, true, "https://status.example.org/"))
        }
        try {
            assertTrue(gate.started.await(2, TimeUnit.SECONDS))
            gate.release.countDown()
            assertTrue(transport.extrasStarted.await(2, TimeUnit.SECONDS))
            assertTrue(check.cancel(true))
            assertTrue("Coordinator cancellation disconnects all active extras",
                transport.extrasCancelled.await(2, TimeUnit.SECONDS))
            coordinator.submit {}.get(2, TimeUnit.SECONDS)
            awaitIdle(pool)
            assertEquals("OK", checkedBatch(probe, coordinator, transport).status)
            assertEquals(3, transport.extraCalls.get())
        } finally {
            gate.release.countDown()
            transport.gate?.release?.countDown()
            transport.releaseExtras.countDown()
            coordinator.shutdownNow()
            pool.shutdownNow()
            extraPool.shutdownNow()
            assertTrue(coordinator.awaitTermination(2, TimeUnit.SECONDS))
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
            assertTrue(extraPool.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun cancelledStuckExtrasCannotRejectAnyOperationInTheNextTelegramBatch() {
        val pool = NetworkProbe.executor()
        val extraPool = NetworkProbe.extraExecutor()
        val coordinator = Executors.newSingleThreadExecutor()
        val transport = Transport()
        val probe = NetworkProbe(pool, extraPool, transport::connection, transport::socket)
        val extras = ExtraProbes.endpoints(true, true, true, "https://status.example.org/")
        try {
            val baseline = checkedBatch(probe, coordinator, transport)
            assertEquals("OK", baseline.status)
            awaitIdle(pool)

            val before = System.nanoTime()
            val timedOut = checkedBatch(probe, coordinator, transport, extras)
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)
            assertTrue("Extras keep their five-second overall deadline: $elapsed ms", elapsed in 4_500..8_000)
            assertTrue(transport.extrasStarted.await(1, TimeUnit.SECONDS))
            assertTrue("Cancellation disconnects all extra transports", transport.extrasCancelled.await(2, TimeUnit.SECONDS))
            assertEquals(3, transport.extraCalls.get())
            assertTrue(timedOut.results.filter { it.group == ProbeGroup.EXTRA }.all { !it.reachable })
            assertEquals("Fixture transports remain occupied after cancellation", 1L, transport.releaseExtras.count)

            val after = checkedBatch(probe, coordinator, transport)
            assertEquals("OK", after.status)
            assertEquals(baseline.reason, after.reason)
            assertEquals(3 + MtProtoEndpoints.all.size, after.results.size)
            assertTrue(after.results.all { it.reachable })
            assertEquals(MtProtoEndpoints.all.size, after.results.count { it.group == ProbeGroup.MTPROTO && it.detailCode == ru.tgwatch.probe.ProbeDetailCode.MTPROTO_CONFIRMED })
            awaitIdle(pool)

            val repeatStart = System.nanoTime()
            val repeated = checkedBatch(probe, coordinator, transport, extras)
            assertTrue("Occupied extra endpoint leases prevent another five-second wait",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - repeatStart) < 2_000)
            assertEquals(3, transport.extraCalls.get())
            assertEquals(baseline.status, repeated.status)
            assertEquals(baseline.reason, repeated.reason)
            assertTrue(repeated.results.filter { it.group == ProbeGroup.EXTRA }.all { !it.reachable })
            awaitIdle(pool)

            transport.telegramReachable = false
            val down = checkedBatch(probe, coordinator, transport)
            assertEquals("Controls retain their workers while extras are stuck", "TG_DOWN", down.status)
            assertEquals(3, transport.controlCalls.get())
            assertEquals(3 + MtProtoEndpoints.all.size, down.results.count { it.group == ProbeGroup.WEB || it.group == ProbeGroup.MTPROTO })
            assertEquals(-1L, down.latencyMs)
        } finally {
            transport.gate?.release?.countDown()
            transport.releaseExtras.countDown()
            coordinator.shutdownNow()
            pool.shutdownNow()
            extraPool.shutdownNow()
            assertTrue(coordinator.awaitTermination(2, TimeUnit.SECONDS))
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
            assertTrue(extraPool.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    private fun checkedBatch(probe: NetworkProbe, coordinator: ExecutorService, transport: Transport,
        extras: List<ProbeEndpoint> = emptyList()): ProbeReport {
        val gate = TelegramGate()
        transport.gate = gate
        val result = coordinator.submit<ProbeReport> { probe.check(extras) }
        try {
            val allStarted = gate.started.await(2, TimeUnit.SECONDS)
            assertTrue("Every Telegram endpoint operation must run; started ${gate.operations.size}: ${gate.operations}",
                allStarted)
            assertEquals(3 + MtProtoEndpoints.all.size, gate.operations.toSet().size)
        } finally {
            gate.release.countDown()
        }
        return result.get(8, TimeUnit.SECONDS)
    }

    private fun awaitIdle(pool: ExecutorService) {
        val executor = pool as ThreadPoolExecutor
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (executor.activeCount != 0 && System.nanoTime() < deadline) Thread.yield()
        assertEquals("Previous Telegram completion wrappers have returned", 0, executor.activeCount)
    }

    private class TelegramGate {
        val started = CountDownLatch(3 + MtProtoEndpoints.all.size)
        val release = CountDownLatch(1)
        val operations = Collections.synchronizedList(mutableListOf<String>())
        fun enter(address: String) {
            operations += address
            started.countDown()
            if (!release.await(3, TimeUnit.SECONDS)) throw IllegalStateException("Telegram fixture was not released")
        }
    }

    private class Transport {
        @Volatile var gate: TelegramGate? = null
        val releaseExtras = CountDownLatch(1)
        val extrasStarted = CountDownLatch(3)
        val extrasCancelled = CountDownLatch(3)
        val extraCalls = AtomicInteger()
        val controlCalls = AtomicInteger()
        @Volatile var telegramReachable = true

        fun connection(url: URL): HttpURLConnection = object : HttpURLConnection(url) {
            override fun connect() {}
            override fun usingProxy() = false
            override fun disconnect() { if (isExtra(url)) extrasCancelled.countDown() }
            override fun getResponseCode(): Int {
                if (url.host.endsWith("telegram.org")) {
                    gate!!.enter(url.host)
                    if (!telegramReachable) throw UnknownHostException("Fixture Telegram unavailable")
                } else if (isExtra(url)) {
                    extraCalls.incrementAndGet()
                    extrasStarted.countDown()
                    while (releaseExtras.count > 0) {
                        try {
                            if (!releaseExtras.await(15, TimeUnit.SECONDS)) throw IllegalStateException("Extra fixture was not released")
                        } catch (_: InterruptedException) {
                            // Deliberately noncooperative transport: cancellation does not release its worker.
                        }
                    }
                } else controlCalls.incrementAndGet()
                return 200
            }
        }

        fun socket(): Socket = object : Socket() {
            private val request = ByteArrayOutputStream()
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                gate!!.enter((endpoint as InetSocketAddress).address.hostAddress!!)
                if (!telegramReachable) throw SocketTimeoutException("Fixture Telegram unavailable")
            }
            override fun setSoTimeout(timeout: Int) {}
            override fun getOutputStream() = request
            override fun getInputStream(): ByteArrayInputStream {
                val nonce = request.toByteArray().copyOfRange(26, 42)
                val response = MtProtoFixtures.response(nonce)
                return ByteArrayInputStream(byteArrayOf((response.size / 4).toByte()) + response)
            }
        }

        private fun isExtra(url: URL) = url.host in setOf("matrix.org", "web.whatsapp.com", "status.example.org")
    }
}
