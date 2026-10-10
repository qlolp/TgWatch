package ru.tgwatch

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class CheckScheduleTest {
    @Test fun networkReturningDuringTimerReplacementKeepsFastRetry() {
        val replacing = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val callbackStarted = CountDownLatch(1)
        val callbackFinished = CountDownLatch(1)
        val pending = AtomicLong(-1)
        val failure = AtomicReference<Throwable?>(null)
        val scheduler = CheckSchedule({}) { delay ->
            if (Thread.currentThread().name == "finishing-check") {
                replacing.countDown()
                assertTrue(releaseWorker.await(2, TimeUnit.SECONDS))
            }
            pending.set(delay)
        }
        val revision = scheduler.revision
        val worker = thread(name = "finishing-check") {
            try { scheduler.afterCheck(revision, 300_000L) } catch (t: Throwable) { failure.set(t) }
        }
        assertTrue(replacing.await(2, TimeUnit.SECONDS))
        val callback = thread {
            callbackStarted.countDown()
            try { scheduler.networkChanged(1_500L) } catch (t: Throwable) { failure.set(t) }
            finally { callbackFinished.countDown() }
        }
        try {
            assertTrue(callbackStarted.await(2, TimeUnit.SECONDS))
            // The unsafe implementation replaces the fast retry before the worker resumes.
            // The atomic implementation holds this callback until replacement completes.
            callbackFinished.await(200, TimeUnit.MILLISECONDS)
        } finally {
            releaseWorker.countDown()
            worker.join(2_000)
            callback.join(2_000)
        }
        assertFalse(worker.isAlive)
        assertFalse(callback.isAlive)
        assertNull(failure.get())
        assertEquals(1_500L, pending.get())
    }

    @Test fun revisionChangeBeforeReschedulingInvalidatesOfflineDelay() {
        var resets = 0
        var pending = -1L
        val scheduler = CheckSchedule({ resets++ }) { pending = it }
        val revision = scheduler.revision
        scheduler.networkChanged(800L)
        scheduler.afterCheck(revision, 300_000L)
        assertEquals(1, resets)
        assertEquals(1_000L, pending)
    }
}
