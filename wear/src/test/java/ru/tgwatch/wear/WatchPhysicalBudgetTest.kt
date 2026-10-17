package ru.tgwatch.wear

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.SocketAddress
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class WatchPhysicalBudgetTest {
    @Test fun activityReplacementCannotMultiplyFourUninterruptibleRequests() {
        val started = CountDownLatch(4)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        fun block() {
            calls.incrementAndGet(); started.countDown()
            while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
        }
        val transport = object : ru.tgwatch.probe.ProbeTransport {
            override val userAgent = "fixture"
            override fun openConnection(url: URL) = object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun usingProxy() = false
                override fun disconnect() = Unit
                override fun getResponseCode(): Int { block(); return 200 }
            }
            override fun openSocket() = object : Socket() {
                override fun connect(address: SocketAddress, timeout: Int) = Unit
                override fun setSoTimeout(timeout: Int) = Unit
                override fun getOutputStream() = ByteArrayOutputStream()
                override fun getInputStream() = object : InputStream() {
                    override fun read(): Int { block(); return -1 }
                }
                override fun close() = Unit
            }
        }
        val pool = Executors.newFixedThreadPool(4)
        val coordinator = Executors.newSingleThreadExecutor()
        val clock = WatchClocks({ 10000 }, { 1000 }, { 1 })
        val execution = WatchExecution(coordinator, object : WatchScheduler {
            override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit = {}
        })
        val publications = AtomicInteger()
        val firstReturned = CountDownLatch(1)
        val runner1 = WatchProbeRunner(transport, pool)
        val runner2 = WatchProbeRunner(transport, pool)
        val first = WatchCheckController(clock, {
            try { WatchProbeResult(runner1.check(it.rotation)) } finally { firstReturned.countDown() }
        }, { publications.incrementAndGet() }, execution)
        val second = WatchCheckController(clock, { WatchProbeResult(runner2.check(it.rotation)) },
            { publications.incrementAndGet() }, execution)
        try {
            assertTrue(first.request())
            assertTrue(started.await(2, TimeUnit.SECONDS))
            first.cancel()
            assertTrue(firstReturned.await(2, TimeUnit.SECONDS))
            assertEquals(4, WatchBudget.admission.snapshot().primary)
            assertTrue(second.request())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (second.isChecking && System.nanoTime() < deadline) Thread.sleep(1)
            assertFalse(second.isChecking)
            assertEquals(4, calls.get())
            assertEquals(4, WatchBudget.admission.snapshot().primary)
            assertNull(second.snapshot())
            assertEquals(0, publications.get())
        } finally {
            first.cancel(); second.cancel(); release.countDown(); coordinator.shutdownNow(); pool.shutdownNow()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (WatchBudget.admission.snapshot().primary > 0 && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals(0, WatchBudget.admission.snapshot().primary)
        }
    }
}
