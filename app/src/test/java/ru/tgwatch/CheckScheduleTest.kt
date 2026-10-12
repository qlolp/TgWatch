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

    @Test fun immediateRequestDuringCheckCoalescesIntoOneFollowUp() {
        val replaced = mutableListOf<Long>()
        val scheduler = CheckSchedule({}) { replaced += it }
        val revision = scheduler.begin()
        // New settings or a manual press arrive while the check runs.
        scheduler.request(0L)
        scheduler.request(0L)
        scheduler.afterCheck(revision, 300_000L)
        assertEquals(0L, replaced.last())
        // The follow-up consumes both requests; its own completion returns to the interval.
        val followUp = scheduler.begin()
        scheduler.afterCheck(followUp, 300_000L)
        assertEquals(300_000L, replaced.last())
    }

    @Test fun requestFromAnotherThreadDuringCheckSurvivesCompletion() {
        val pending = AtomicLong(-1)
        val scheduler = CheckSchedule({}) { pending.set(it) }
        val revision = scheduler.begin()
        val failure = AtomicReference<Throwable?>(null)
        thread { try { scheduler.request(0L) } catch (t: Throwable) { failure.set(t) } }.join(2_000)
        scheduler.afterCheck(revision, 300_000L)
        assertNull(failure.get())
        assertEquals(0L, pending.get())
    }

    @Test fun requestThatStartedTheCheckIsNotRepeated() {
        var pending = -1L
        val scheduler = CheckSchedule({}) { pending = it }
        scheduler.request(0L)
        val revision = scheduler.begin()
        scheduler.afterCheck(revision, 300_000L)
        assertEquals(300_000L, pending)
    }

    @Test fun alarmWakeDuringCheckIsSatisfiedByIt() {
        var pending = -1L
        val scheduler = CheckSchedule({}) { pending = it }
        val revision = scheduler.begin()
        scheduler.wake()
        assertEquals(0L, pending)
        scheduler.afterCheck(revision, 300_000L)
        assertEquals(300_000L, pending)
    }

    @Test fun requestDuringCheckWinsOverNetworkRetryDelay() {
        var pending = -1L
        val scheduler = CheckSchedule({}) { pending = it }
        val revision = scheduler.begin()
        scheduler.networkChanged(800L)
        scheduler.request(0L)
        scheduler.afterCheck(revision, 300_000L)
        assertEquals(0L, pending)
    }
}
