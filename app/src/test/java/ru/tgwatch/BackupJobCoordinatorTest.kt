package ru.tgwatch

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BackupJobCoordinatorTest {
    @Test fun sleepingPastDeadlineStopsABlockedJobBeforeItsTimerCallback() {
        val io = Executors.newSingleThreadExecutor()
        var now = 0L
        val password = "sleeping secret".toCharArray()
        val runner = BackupJobCoordinator<String>(io, { _, _ -> {} }, { now }, {},
            limits = BackupJobLimits(timeoutMs = 100))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            assertTrue(runner.start(BackupJobKind.READ_BACKUP, sensitive = listOf(password)) { _, _ ->
                entered.countDown(); uninterruptibly(release); "late private result"
            })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            now = 101
            assertEquals(BackupJobPhase.TIMED_OUT, runner.snapshot()!!.phase)
            assertTrue(runner.snapshot()!!.physicalRunning)
            assertTrue(password.all { it == '\u0000' })
            assertFalse(runner.start(BackupJobKind.READ_CSV) { _, _ -> "replacement" })
            release.countDown()
            await { runner.snapshot()?.physicalRunning == false }
            assertNull(runner.snapshot()!!.value)
        } finally { release.countDown(); io.shutdownNow() }
    }

    @Test fun expiredPreviewCannotBeAppliedWhenSleepDelaysTheTimerCallback() {
        var now = 0L
        val runner = BackupJobCoordinator<String>({ it.run() }, { _, _ -> {} }, { now }, {},
            limits = BackupJobLimits(previewTtlMs = 100))
        assertTrue(runner.start(BackupJobKind.READ_BACKUP) { _, _ -> "private preview" })
        val id = runner.snapshot()!!.id
        now = 101
        var applied = false
        assertFalse(runner.start(BackupJobKind.APPLY_BACKUP, false, id) { _, _ -> applied = true; "applied" })
        assertFalse(applied)
        assertEquals(BackupJobPhase.EXPIRED, runner.snapshot()!!.phase)
        assertNull(runner.snapshot()!!.value)
    }

    @Test fun readingStateExpiresAPreviewWithoutWaitingForTheTimerCallback() {
        var now = 0L
        val runner = BackupJobCoordinator<String>({ it.run() }, { _, _ -> {} }, { now }, {},
            limits = BackupJobLimits(previewTtlMs = 100))
        runner.start(BackupJobKind.READ_CSV) { _, _ -> "private CSV" }
        now = 100
        assertEquals(BackupJobPhase.EXPIRED, runner.snapshot()!!.phase)
        assertNull(runner.snapshot()!!.value)
    }

    @Test fun completedRestoreDoesNotTurnIntoAnExpiredImportPreview() {
        val timer = Timer()
        val runner = BackupJobCoordinator<String>({ it.run() }, timer::schedule, { timer.now }, {})
        assertTrue(runner.start(BackupJobKind.APPLY_BACKUP, cancellable = false) { _, _ -> "applied" })
        timer.advance(300_000)
        assertEquals(BackupJobPhase.SUCCEEDED, runner.snapshot()!!.phase)
        assertEquals("applied", runner.snapshot()!!.value)
    }

    @Test fun cancellationBeforeWorkerStartsErasesTheTransferredPassword() {
        val timer = Timer()
        var queued: Runnable? = null
        var opened = false
        val password = "queued secret".toCharArray()
        val runner = BackupJobCoordinator<String>({ queued = it }, timer::schedule, { timer.now }, {})
        assertTrue(runner.start(BackupJobKind.READ_BACKUP, sensitive = listOf(password)) { _, _ ->
            opened = true
            "unused"
        })
        assertTrue(runner.cancel(runner.snapshot()!!.id))
        assertTrue(password.all { it == '\u0000' })
        queued!!.run()
        assertFalse(opened)
        assertFalse(runner.snapshot()!!.physicalRunning)
    }

    @Test fun workerRejectionErasesPasswordAndLeavesNoPendingJob() {
        val timer = Timer()
        val password = "rejected secret".toCharArray()
        val runner = BackupJobCoordinator<String>({ throw java.util.concurrent.RejectedExecutionException() },
            timer::schedule, { timer.now }, {})
        assertFalse(runner.start(BackupJobKind.READ_BACKUP, sensitive = listOf(password)) { _, _ -> "unused" })
        assertTrue(password.all { it == '\u0000' })
        assertNull(runner.snapshot())
    }

    @Test fun errorsExposeOnlyAFailurePhase() {
        val timer = Timer()
        val runner = BackupJobCoordinator<String>({ it.run() }, timer::schedule, { timer.now }, {})
        assertTrue(runner.start(BackupJobKind.READ_BACKUP) { _, _ -> error("PRIVATE_PROVIDER_URL") })
        assertEquals(BackupJobPhase.FAILED, runner.snapshot()!!.phase)
        assertNull(runner.snapshot()!!.value)
        assertFalse(runner.snapshot().toString().contains("PRIVATE_PROVIDER_URL"))
    }

    @Test fun unavailableDeadlineSchedulerRejectsBeforeAnyFileWork() {
        var opened = false
        val runner = BackupJobCoordinator<String>({ it.run() },
            { _, _ -> throw java.util.concurrent.RejectedExecutionException() }, { 0L }, {})
        assertFalse(runner.start(BackupJobKind.EXPORT) { _, _ -> opened = true; "written" })
        assertFalse("A rejected job must not truncate or write a document", opened)
        assertNull(runner.snapshot())
    }

    private class Timer {
        var now = 0L
        private data class Task(val at: Long, val action: () -> Unit, var active: Boolean = true)
        private val tasks = mutableListOf<Task>()
        @Synchronized fun schedule(delay: Long, action: () -> Unit): () -> Unit {
            val task = Task(now + delay, action)
            tasks.add(task)
            return { synchronized(this) { task.active = false } }
        }
        fun advance(ms: Long) {
            val due = synchronized(this) {
                now += ms
                tasks.filter { it.active && it.at <= now }.also { rows -> rows.forEach { it.active = false } }
            }
            due.forEach { it.action() }
        }
    }

    @Test fun timeoutDoesNotFreePhysicalWorkerOrPublishLateSuccess() {
        val io = Executors.newSingleThreadExecutor()
        val timer = Timer()
        val runner = BackupJobCoordinator<String>(io, timer::schedule, { timer.now }, {}, limits = BackupJobLimits(timeoutMs = 100))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            assertTrue(runner.start(BackupJobKind.READ_BACKUP) { _, _ ->
                entered.countDown(); uninterruptibly(release); "late private result"
            })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            timer.advance(100)
            assertEquals(BackupJobPhase.TIMED_OUT, runner.snapshot()!!.phase)
            assertTrue(runner.snapshot()!!.physicalRunning)
            assertFalse(runner.start(BackupJobKind.READ_CSV) { _, _ -> "another" })
            release.countDown()
            await { runner.snapshot()?.physicalRunning == false }
            assertEquals(BackupJobPhase.TIMED_OUT, runner.snapshot()!!.phase)
            assertNull(runner.snapshot()!!.value)
        } finally { release.countDown(); io.shutdownNow() }
    }

    @Test fun cancellationClearsPasswordWhileBlockedWorkRetainsItsSlot() {
        val io = Executors.newSingleThreadExecutor()
        val timer = Timer()
        val runner = BackupJobCoordinator<String>(io, timer::schedule, { timer.now }, {})
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val password = "private password".toCharArray()
        try {
            assertTrue(runner.start(BackupJobKind.READ_BACKUP) { _, control ->
                control.secret(password)
                entered.countDown(); uninterruptibly(release); "discarded"
            })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(runner.cancel(runner.snapshot()!!.id))
            assertTrue(password.all { it == '\u0000' })
            assertTrue(runner.snapshot()!!.physicalRunning)
            release.countDown()
            await { runner.snapshot()?.physicalRunning == false }
            assertEquals(BackupJobPhase.CANCELLED, runner.snapshot()!!.phase)
            assertNull(runner.snapshot()!!.value)
        } finally { release.countDown(); io.shutdownNow() }
    }

    @Test fun applyingRestoreCannotBeCancelledOrTimedOut() {
        val io = Executors.newSingleThreadExecutor()
        val timer = Timer()
        val runner = BackupJobCoordinator<String>(io, timer::schedule, { timer.now }, {}, limits = BackupJobLimits(timeoutMs = 100))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            assertTrue(runner.start(BackupJobKind.APPLY_BACKUP, cancellable = false) { _, _ ->
                entered.countDown(); uninterruptibly(release); "committed"
            })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(runner.cancel(runner.snapshot()!!.id))
            timer.advance(1000)
            assertEquals(BackupJobPhase.RUNNING, runner.snapshot()!!.phase)
            release.countDown()
            await { runner.snapshot()?.phase == BackupJobPhase.SUCCEEDED }
            assertEquals("committed", runner.snapshot()!!.value)
        } finally { release.countDown(); io.shutdownNow() }
    }

    @Test fun pendingPreviewSurvivesUiReplacementAndExpiresWithoutPersistingData() {
        val timer = Timer()
        val runner = BackupJobCoordinator<String>({ it.run() }, timer::schedule, { timer.now }, {}, limits = BackupJobLimits(previewTtlMs = 100))
        assertTrue(runner.start(BackupJobKind.READ_BACKUP) { _, _ -> "private preview" })
        val before = runner.snapshot()!!
        assertEquals("private preview", runner.snapshot()!!.value)
        assertFalse(runner.start(BackupJobKind.READ_CSV) { _, _ -> "replacement" })
        timer.advance(100)
        assertEquals(BackupJobPhase.EXPIRED, runner.snapshot()!!.phase)
        assertNull(runner.snapshot()!!.value)
        assertTrue(runner.dismiss(before.id))
        assertNull(runner.snapshot())
    }

    @Test fun previewCanBeAppliedOnlyOnceWithItsExactIdentity() {
        val timer = Timer()
        val runner = BackupJobCoordinator<String>({ it.run() }, timer::schedule, { timer.now }, {})
        runner.start(BackupJobKind.READ_BACKUP) { _, _ -> "validated" }
        val id = runner.snapshot()!!.id
        assertFalse(runner.start(BackupJobKind.APPLY_BACKUP, false, id + 1) { _, _ -> "wrong" })
        assertTrue(runner.start(BackupJobKind.APPLY_BACKUP, false, id) { previous, _ ->
            assertEquals("validated", previous)
            "applied"
        })
        assertFalse(runner.start(BackupJobKind.APPLY_BACKUP, false, id) { _, _ -> "twice" })
        assertEquals("applied", runner.snapshot()!!.value)
    }

    private fun uninterruptibly(latch: CountDownLatch) {
        while (true) try { latch.await(); return } catch (_: InterruptedException) { }
    }
    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < until) Thread.yield()
        assertTrue("The operation must reach its expected state", condition())
    }
}
