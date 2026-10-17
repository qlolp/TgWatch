package ru.tgwatch

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AutomationStateDispatcherTest {
    private class Deadline {
        lateinit var expire: () -> Unit
        var delay = 0L
        fun schedule(ms: Long, action: () -> Unit): () -> Unit {
            delay = ms
            expire = action
            return {}
        }
    }
    private val snapshot = AutomationStateSnapshot("OK", 100, false, true)
    @After fun clear() { AutomationStateCache.clear() }

    @Test fun restoreInvalidatesPendingReplyAndCacheWithoutReleasingPhysicalCompute() {
        val deadline = Deadline()
        var work: Runnable? = null
        val dispatcher = AutomationStateDispatcher(Executor { work = it }, { 0L }, deadline::schedule)
        var replies = 0
        var finishes = 0
        dispatcher.submit({ snapshot }, { result ->
            assertTrue(result.isFailure)
            replies++
        }, { finishes++ })
        dispatcher.invalidate()
        assertEquals(1, replies)
        assertEquals(1, finishes)
        assertTrue(dispatcher.health().busy)
        assertFalse("Restore invalidation is not an eight-second timeout", dispatcher.health().deadlineExpired)
        assertNull(AutomationStateCache.take(0))
        work!!.run()
        assertFalse(dispatcher.health().busy)
        assertEquals(1, replies)
        assertNull(AutomationStateCache.take(0))
    }

    @Test fun hangingComputeFinishesPendingAtEightSecondsAndNeverSpawnsReplacement() {
        val worker = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val deadline = Deadline()
        val calls = AtomicInteger()
        val finished = AtomicInteger()
        val dispatcher = AutomationStateDispatcher(worker, { 8_000L }, deadline::schedule)
        try {
            assertTrue(dispatcher.submit({
                calls.incrementAndGet()
                started.countDown()
                release.await()
                snapshot
            }, { }, { finished.incrementAndGet() }))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertEquals(8_000L, deadline.delay)
            deadline.expire()
            assertEquals(1, finished.get())
            assertTrue(dispatcher.health().busy)
            assertTrue(dispatcher.health().deadlineExpired)
            repeat(5) { index ->
                assertTrue(AutomationStateCache.beginCompute(8_000L + index * 200L))
                assertFalse(dispatcher.submit({ calls.incrementAndGet(); snapshot }, {}, {}))
            }
            assertEquals(1, calls.get())
            assertNull(AutomationStateCache.take(8_000))
        } finally {
            release.countDown()
            worker.shutdown()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
        assertNull("A late completion must not refresh the cache", AutomationStateCache.take(8_000))
        assertFalse(dispatcher.health().busy)
        assertTrue(AutomationStateCache.beginCompute(10_000))
    }

    @Test fun completionCachesAtFinishTimeAndCancelsDeadline() {
        val clock = AtomicLong(0)
        val deadline = Deadline()
        val finished = AtomicInteger()
        val completed = AtomicInteger()
        val dispatcher = AutomationStateDispatcher(Executor { it.run() }, clock::get, deadline::schedule)
        assertTrue(dispatcher.submit({ clock.set(5_000); snapshot }, {
            assertEquals(snapshot, it.getOrThrow()); completed.incrementAndGet()
        }, { finished.incrementAndGet() }))
        deadline.expire()
        assertEquals(1, finished.get())
        assertEquals(1, completed.get())
        assertEquals(snapshot, AutomationStateCache.take(5_999))
        assertNull(AutomationStateCache.take(6_000))
    }

    @Test fun deadlineCompletionRaceFinishesOnce() {
        repeat(50) {
            AutomationStateCache.clear()
            val deadline = Deadline()
            val run = arrayOfNulls<Runnable>(1)
            val dispatcher = AutomationStateDispatcher(Executor { run[0] = it }, { 0L }, deadline::schedule)
            val completed = AtomicInteger()
            val finished = AtomicInteger()
            dispatcher.submit({ snapshot }, { completed.incrementAndGet() }, { finished.incrementAndGet() })
            val start = CountDownLatch(1)
            val timeout = Thread { start.await(); deadline.expire() }
            val compute = Thread { start.await(); run[0]!!.run() }
            timeout.start(); compute.start(); start.countDown()
            timeout.join(2_000); compute.join(2_000)
            assertFalse(timeout.isAlive); assertFalse(compute.isAlive)
            assertEquals(1, completed.get())
            assertEquals(1, finished.get())
        }
    }

    @Test fun rejectionAndBrokenDeadlineRegistrationStillFinishExactlyOnce() {
        val deadline = Deadline()
        val finished = AtomicInteger()
        val rejected = AutomationStateDispatcher(Executor { throw RejectedExecutionException() }, { 0L }, deadline::schedule)
        assertFalse(rejected.submit({ snapshot }, {}, { finished.incrementAndGet() }))
        deadline.expire()
        assertFalse(rejected.health().busy)
        assertEquals(1, finished.get())
        val broken = AutomationStateDispatcher(Executor { fail("Must not submit without deadline") }, { 0L },
            { _, _ -> throw IllegalStateException("Deadline unavailable") })
        assertFalse(broken.submit({ snapshot }, {}, { finished.incrementAndGet() }))
        assertFalse(broken.health().busy)
        assertEquals(2, finished.get())
    }
}
