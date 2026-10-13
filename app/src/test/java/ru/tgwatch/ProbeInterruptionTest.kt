package ru.tgwatch

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class ProbeInterruptionTest {
    @Test fun interruptedCoordinatorCannotReturnAReportFromIncompleteProbes() {
        val pool = Executors.newFixedThreadPool(2)
        val started = CountDownLatch(2)
        val healthyFinished = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val result = AtomicReference<List<ProbeOutcome>?>()
        val failure = AtomicReference<Throwable?>()
        val endpoints = listOf(ProbeEndpoint("healthy", ProbeGroup.WEB, "healthy"),
            ProbeEndpoint("blocked", ProbeGroup.MTPROTO, "blocked"))
        val coordinator = thread(name = "interrupted-primary") {
            try {
                result.set(ProbeBatch(pool, 10_000L).run(endpoints) { ep, cancellation ->
                    started.countDown()
                    if (ep.name == "healthy") {
                        healthyFinished.countDown()
                        ProbeOutcome(ep.name, ep.group, true, 12, "HTTP 200")
                    } else {
                        cancellation.register { closed.countDown() }
                        blocked.await()
                        ProbeOutcome(ep.name, ep.group, true, 12, "resPQ")
                    }
                })
            } catch (e: Throwable) { failure.set(e) }
        }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue(healthyFinished.await(2, TimeUnit.SECONDS))
            coordinator.interrupt()
            coordinator.join(2000)
            assertFalse("Cancellation must release the coordinator promptly", coordinator.isAlive)
            assertTrue("Cancellation must still close active transports", closed.await(2, TimeUnit.SECONDS))
            assertNull("An interrupted batch must not classify a partial set of completed outcomes", result.get())
            assertTrue(failure.get() is InterruptedException)
        } finally {
            blocked.countDown()
            coordinator.interrupt()
            coordinator.join(2000)
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun alreadyInterruptedCoordinatorDoesNotStartAnyTransport() {
        val pool = Executors.newFixedThreadPool(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        try {
            Thread.currentThread().interrupt()
            val failure = runCatching {
                ProbeBatch(pool).run(listOf(ProbeEndpoint("fixture", ProbeGroup.WEB, "fixture"))) { ep, _ ->
                    calls.incrementAndGet()
                    ProbeOutcome(ep.name, ep.group, true, 1, "HTTP 200")
                }
            }.exceptionOrNull()
            assertTrue("Cancellation is not a normal deadline result", failure is InterruptedException)
            assertEquals(0, calls.get())
        } finally {
            Thread.interrupted()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
}
