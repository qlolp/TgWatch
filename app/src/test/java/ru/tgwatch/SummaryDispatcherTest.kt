package ru.tgwatch

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class SummaryDispatcherTest {
    @Test fun blockedSummaryFinishesItsBroadcastBeforePhysicalWorkReturns() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val physicalEnd = CountDownLatch(1)
        val result = AtomicReference<Boolean?>()
        val finishes = AtomicInteger()
        val posts = AtomicInteger()
        val timer = ManualSummaryDeadline()
        val dispatcher = SummaryDispatcher(deadline = timer::schedule)
        try {
            assertTrue(dispatcher.submit({ current ->
                try {
                    started.countDown()
                    while (release.count > 0) try { release.await() } catch (_: InterruptedException) {}
                    if (current()) { posts.incrementAndGet(); true } else false
                } finally { physicalEnd.countDown() }
            }, { result.set(it) }, { finishes.incrementAndGet(); finished.countDown() }))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertEquals(8_000L, timer.delays.single())
            timer.fire(0)
            assertTrue("PendingResult must finish within its own deadline even if history never returns",
                finished.await(2, TimeUnit.SECONDS))
            assertEquals(1L, release.count)
            assertEquals(false, result.get())
            timer.fire(0)
            assertEquals(1, finishes.get())
        } finally {
            release.countDown()
            assertTrue(physicalEnd.await(2, TimeUnit.SECONDS))
        }
        assertEquals("Expired work must suppress its late notification", 0, posts.get())
        assertEquals(1, finishes.get())
    }

    @Test fun queuedBroadcastDeadlineIncludesItsWaitAndSkipsExpiredHistory() {
        val timer = ManualSummaryDeadline()
        val pool = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1))
        val dispatcher = SummaryDispatcher(pool, deadline = timer::schedule, timeoutMs = 25L)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finishedQueued = AtomicInteger()
        val queuedWork = AtomicInteger()
        try {
            assertTrue(dispatcher.submit({ started.countDown(); release.await(); true }))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue(dispatcher.submit({ queuedWork.incrementAndGet(); true },
                { assertFalse(it) }, { finishedQueued.incrementAndGet() }))
            assertEquals(listOf(25L, 25L), timer.delays)
            timer.fire(1)
            assertEquals("Queued PendingResult expires while the physical worker remains occupied", 1,
                finishedQueued.get())
            assertEquals(1L, release.count)
            release.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
            assertEquals(0, queuedWork.get())
            assertEquals(1, finishedQueued.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun blockedSummaryDoesNotBlockCallerOrAccumulateUnboundedBroadcasts() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val completed = CountDownLatch(3)
        val finishes = AtomicInteger()
        val posts = AtomicInteger()
        val failures = AtomicInteger()
        val caller = Thread.currentThread()
        val worker = AtomicReference<Thread?>()
        try {
            val dispatcher = SummaryDispatcher()
            fun finish() { finishes.incrementAndGet(); completed.countDown() }
            fun result(posted: Boolean) { if (posted) posts.incrementAndGet() else failures.incrementAndGet() }
            assertTrue(dispatcher.submit({
                worker.set(Thread.currentThread())
                started.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                true
            }, ::result, ::finish))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertNotSame("Summary history must be loaded on a background worker", caller, worker.get())
            assertTrue(dispatcher.submit({ true }, ::result, ::finish))
            assertFalse("A full bounded queue must reject a third delivery promptly",
                dispatcher.submit({ fail("Rejected summary ran"); true }, ::result, ::finish))
            assertEquals("Rejected broadcasts are finished immediately", 1, finishes.get())
            release.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(3, finishes.get())
            assertEquals(2, posts.get())
            assertEquals(1, failures.get())
        } finally {
            release.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun rejectedExecutorFinishesPendingBroadcastWithoutRunningHistory() {
        val rejection = Executor { throw RejectedExecutionException("fixture") }
        val dispatcher = SummaryDispatcher(rejection)
        var finishes = 0
        var result: Boolean? = null
        assertFalse(dispatcher.submit({ fail("Rejected history load ran"); true }, { result = it }, { finishes++ }))
        assertEquals(false, result)
        assertEquals(1, finishes)
    }

    @Test fun failedHistoryStillFinishesAndAllowsTheNextSummary() {
        val failures = mutableListOf<Exception>()
        val dispatcher = SummaryDispatcher(Executor { it.run() }, failures::add)
        var finishes = 0
        val results = mutableListOf<Boolean>()
        val failure = IllegalStateException("history unavailable")
        assertTrue(dispatcher.submit({ throw failure }, results::add, { finishes++ }))
        assertSame(failure, failures.single())
        assertEquals(listOf(false), results)
        assertEquals(1, finishes)
        assertTrue(dispatcher.submit({ true }, results::add, { finishes++ }))
        assertEquals(listOf(false, true), results)
        assertEquals(2, finishes)
    }

    @Test fun completionFailureCannotLeakPendingBroadcast() {
        val dispatcher = SummaryDispatcher(Executor { it.run() })
        var finishes = 0
        val failure = runCatching {
            dispatcher.submit({ true }, { throw IllegalStateException("callback failed") }, { finishes++ })
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(1, finishes)
    }

    @Test fun deadlineRegistrationFailureFinishesWithoutQueueingWork() {
        var calls = 0
        var finishes = 0
        var result: Boolean? = null
        val dispatcher = SummaryDispatcher(Executor { calls++ },
            deadline = { _, _ -> throw IllegalStateException("deadline unavailable") })
        assertFalse(dispatcher.submit({ fail("Unprotected history ran"); true }, { result = it }, { finishes++ }))
        assertEquals(0, calls)
        assertEquals(false, result)
        assertEquals(1, finishes)
    }

    @Test fun failingErrorReporterCannotLeakPendingBroadcast() {
        val dispatcher = SummaryDispatcher(Executor { it.run() }, failed = { throw it })
        var finishes = 0
        runCatching { dispatcher.submit({ throw IllegalStateException("history") }, finish = { finishes++ }) }
        assertEquals(1, finishes)
    }
}

private class ManualSummaryDeadline {
    val delays = mutableListOf<Long>()
    private val callbacks = mutableListOf<() -> Unit>()
    fun schedule(delay: Long, callback: () -> Unit): () -> Unit {
        delays += delay
        callbacks += callback
        return {}
    }
    fun fire(index: Int) { callbacks[index]() }
}
