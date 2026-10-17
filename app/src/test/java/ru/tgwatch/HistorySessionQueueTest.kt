package ru.tgwatch

import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class HistorySessionQueueTest {
    @Test fun closeReportsTheRetiredSessionIdentityAfterANewOneOpens() {
        val worker = ManualWorker()
        val ended = mutableListOf<Pair<Long, Long>>()
        val queue = HistorySessionQueue(worker, { fail("Use the target-aware closer") },
            sessionClosed = { id, at -> ended.add(id to at) })
        val old = queue.open()
        queue.submit(old) { }
        queue.close(old, 123)
        queue.open()
        worker.drain()
        assertEquals(listOf(old to 123L), ended)
    }
    private class ManualWorker : Executor {
        val work = ArrayDeque<Runnable>()
        override fun execute(task: Runnable) { work.add(task) }
        fun drain() { while (work.isNotEmpty()) work.removeFirst().run() }
    }

    @Test fun concurrentStopWaitersShareOneBarrier() {
        val worker = ManualWorker()
        val queue = HistorySessionQueue(worker, {}, { 100L })
        val session = queue.open()
        assertTrue(queue.submit(session) {})
        val closed = queue.close(session, 100)
        repeat(100) { assertSame(closed, queue.closedBarrier()) }
        assertEquals(1, queue.pendingTransitions())
        worker.drain()
        assertTrue(closed.isDone)
    }

    @Test fun blockedDiskDoesNotGrowHistoryQueue() {
        val worker = ManualWorker()
        val journal = mutableListOf<String>()
        val queue = HistorySessionQueue(worker, { journal.add("close:$it") }, { 100L })
        val session = queue.open()
        repeat(64) { index -> assertTrue(queue.submit(session) { journal.add("record:$index") }) }
        assertFalse(queue.submit(session) { fail("Backpressured work ran") })
        val closed = queue.close(session, 101)
        assertFalse(closed.isDone)
        assertEquals(64, queue.pendingRecords())
        worker.drain()
        assertTrue(closed.isDone)
        assertEquals("close:101", journal.last())
        assertEquals(65, journal.size)
    }

    @Test fun oldCloseCannotClipNewSession() {
        val worker = ManualWorker()
        val journal = mutableListOf<String>()
        val queue = HistorySessionQueue(worker, { journal.add("close:$it") }, { 100L })
        val old = queue.open()
        assertTrue(queue.submit(old) { journal.add("old") })
        val ended = queue.close(old, 50)
        val next = queue.open()
        assertFalse(queue.submit(old) { fail("Retired session accepted work") })
        assertFalse(queue.submit(next) { fail("New work crossed the pending close") })
        worker.drain()
        assertTrue(ended.isDone)
        assertTrue(queue.submit(next) { journal.add("new") })
        queue.close(old, 200)
        worker.drain()
        assertEquals(listOf("old", "close:50", "new"), journal)
    }

    @Test fun repeatedRestartsCoalesceWithoutLosingStopBarrier() {
        val worker = ManualWorker()
        val journal = mutableListOf<Long>()
        val queue = HistorySessionQueue(worker, { journal.add(it) }, { 100L })
        var session = queue.open()
        assertTrue(queue.submit(session) {})
        repeat(100) {
            queue.close(session, 100 + it.toLong())
            session = queue.open()
        }
        assertTrue(queue.pendingTransitions() <= 2)
        worker.drain()
        assertEquals(listOf(100L), journal)
        assertTrue(queue.submit(session) {})
        worker.drain()
    }

    @Test fun exclusiveRecoveryRunsAfterOldSessionAndBeforeNewRecords() {
        val worker = ManualWorker()
        val journal = mutableListOf<String>()
        val queue = HistorySessionQueue(worker, { journal.add("close") }, { 100L })
        val session = queue.open()
        queue.submit(session) { journal.add("old") }
        queue.close(session, 100)
        val recovery = queue.exclusive { journal.add("restore") }
        val next = queue.open()
        assertFalse(queue.submit(next) {})
        worker.drain()
        assertTrue(recovery.isDone)
        queue.submit(next) { journal.add("new") }
        worker.drain()
        assertEquals(listOf("old", "close", "restore", "new"), journal)
    }
}
