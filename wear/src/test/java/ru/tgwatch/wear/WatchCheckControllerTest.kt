package ru.tgwatch.wear

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test
import ru.tgwatch.probe.*

class WatchCheckControllerTest {
    private class Timer : WatchScheduler {
        var action: (() -> Unit)? = null
        override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
            action = task
            return { action = null }
        }
        fun fire() { action?.invoke() }
    }
    private val wall = AtomicLong(10000)
    private val elapsed = AtomicLong(1000)
    private val network = AtomicLong(1)
    private fun clocks() = WatchClocks(wall::get, elapsed::get, { 1L }, network::get)
    private fun success() = WatchProbeResult(ProbeReport(listOf(
        ProbeOutcome("web", ProbeGroup.WEB, true, 10, ProbeDetail(ProbeDetailCode.HTTP_RESPONSE, 200)),
        ProbeOutcome("dc", ProbeGroup.MTPROTO, true, 30, ProbeDetail(ProbeDetailCode.MTPROTO_CONFIRMED)))))

    @Test fun manualCheckHasNoPhoneOrAutomaticWork() {
        val timer = Timer()
        val execution = WatchExecution(Executors.newSingleThreadExecutor(), timer)
        val calls = AtomicInteger()
        val published = CountDownLatch(1)
        val controller = WatchCheckController(clocks(), { calls.incrementAndGet(); success() },
            { published.countDown() }, execution)
        try {
            assertEquals(0, calls.get())
            assertNull(controller.snapshot())
            assertTrue(controller.request())
            assertTrue(published.await(2, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
            assertEquals("OK", controller.snapshot()!!.status)
            assertEquals(30L, controller.snapshot()!!.latencyMs)
            assertNull(timer.action)
        } finally { controller.cancel(); execution.coordinator.shutdownNow() }
    }
    @Test fun timeoutAndNetworkChangeCannotPublishLateData() {
        for (changeNetwork in listOf(false, true)) {
            val timer = Timer()
            val execution = WatchExecution(Executors.newSingleThreadExecutor(), timer)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val returned = CountDownLatch(1)
            val count = AtomicInteger()
            val controller = WatchCheckController(clocks(), {
                entered.countDown()
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                try { success() } finally { returned.countDown() }
            }, { count.incrementAndGet() }, execution)
            try {
                assertTrue(controller.request())
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                if (changeNetwork) network.incrementAndGet() else {
                    elapsed.addAndGet(8001)
                    timer.fire()
                    assertFalse(controller.isChecking)
                }
                release.countDown()
                assertTrue(returned.await(2, TimeUnit.SECONDS))
                waitUntil { !controller.isChecking }
                assertNull(controller.snapshot())
                assertEquals(0, count.get())
            } finally { release.countDown(); controller.cancel(); execution.coordinator.shutdownNow() }
        }
    }
    @Test fun probePlanRotatesFourEndpointsAndSubsetFailureIsNotGlobalDown() {
        val all = (0 until 10).flatMap { rotation ->
            val plan = WatchProbePlan.endpoints(rotation)
            assertEquals(4, plan.size)
            assertEquals(1, plan.count { it.group == ProbeGroup.WEB })
            assertEquals(3, plan.count { it.group == ProbeGroup.MTPROTO })
            plan.filter { it.group == ProbeGroup.MTPROTO }.map { it.name }
        }.toSet()
        assertEquals(10, all.size)
        assertEquals(4, WatchProbePlan.endpoints(Int.MAX_VALUE).size)
        val timer = Timer()
        val execution = WatchExecution(Executors.newSingleThreadExecutor(), timer)
        val published = CountDownLatch(1)
        val controller = WatchCheckController(clocks(), {
            WatchProbeResult(ProbeReport(WatchProbePlan.endpoints(0).map {
                ProbeOutcome(it.name, it.group, false, -1, ProbeDetail(ProbeDetailCode.TIMEOUT))
            }))
        }, { published.countDown() }, execution)
        try {
            controller.request()
            assertTrue(published.await(2, TimeUnit.SECONDS))
            assertEquals("UNKNOWN", controller.snapshot()!!.status)
        } finally { controller.cancel(); execution.coordinator.shutdownNow() }
    }
    @Test fun rebootAndThreeMinutesInvalidateMonotonicCache() {
        val value = WatchSnapshot("OK", 10000, 1000, 20, 1, 7)
        assertFalse(value.stale(181000, 7))
        assertTrue(value.stale(181001, 7))
        assertTrue(value.stale(1100, 8))
        assertTrue(value.copy(checkedElapsedMs = 0).stale(1100, 7))
    }
    private fun waitUntil(test: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!test() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue(test())
    }
}
