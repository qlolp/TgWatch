package ru.tgwatch

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LatestRequestLoaderTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() { tasks.removeFirst().run() }
        fun drain() { while (tasks.isNotEmpty()) run() }
    }

    @Test fun rapidRequestsKeepOnlyOneWorkerAndLatestPendingWindow() {
        val worker = Queue()
        val delivery = Queue()
        val loaded = mutableListOf<Int>()
        val shown = mutableListOf<Int>()
        val loader = LatestRequestLoader(worker, delivery, { key: Int -> loaded.add(key); key }) { _, result ->
            shown.add(result.getOrThrow())
        }
        repeat(1_000) { loader.request(it) }
        assertEquals("A period switch cannot queue a thousand history scans", 1, worker.tasks.size)
        worker.drain()
        delivery.drain()
        assertEquals(listOf(999), loaded)
        assertEquals(listOf(999), shown)
    }

    @Test fun newerPeriodDiscardsAlreadyQueuedOldCompletion() {
        val worker = Queue()
        val delivery = Queue()
        val shown = mutableListOf<Int>()
        val loader = LatestRequestLoader(worker, delivery, { key: Int -> key }) { _, result ->
            shown.add(result.getOrThrow())
        }
        loader.request(1)
        worker.run()
        loader.request(7)
        delivery.run()
        assertTrue("Old day data cannot overwrite the week view", shown.isEmpty())
        worker.drain()
        delivery.drain()
        assertEquals(listOf(7), shown)
    }

    @Test fun pausedOrDestroyedActivityReceivesNoLateCompletion() {
        for (close in listOf(false, true)) {
            val worker = Queue()
            val delivery = Queue()
            val shown = mutableListOf<Int>()
            val loader = LatestRequestLoader(worker, delivery, { key: Int -> key }) { _, result ->
                shown.add(result.getOrThrow())
            }
            loader.request(1)
            worker.run()
            if (close) loader.close() else loader.invalidate()
            delivery.drain()
            assertTrue(shown.isEmpty())
            loader.request(7)
            worker.drain()
            delivery.drain()
            assertEquals(if (close) emptyList<Int>() else listOf(7), shown)
        }
    }

    @Test fun requestDuringWorkRunsLatestOnlyAndFailureDoesNotStallNextWindow() {
        val worker = Queue()
        val delivery = Queue()
        val loaded = mutableListOf<Int>()
        val shown = mutableListOf<Int>()
        lateinit var loader: LatestRequestLoader<Int, Int>
        loader = LatestRequestLoader(worker, delivery, { key: Int ->
            loaded.add(key)
            if (key == 1) {
                loader.request(2)
                loader.request(7)
                error("expired history read")
            }
            key
        }) { _, result -> shown.add(result.getOrThrow()) }
        loader.request(1)
        worker.drain()
        delivery.drain()
        assertEquals(listOf(1, 7), loaded)
        assertEquals(listOf(7), shown)
    }

    @Test fun historyWorkRunsOnExecutorThread() {
        val caller = Thread.currentThread()
        val ready = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val loader = LatestRequestLoader(worker, Executor { it.run() }, { _: Int -> Thread.currentThread() }) { _, result ->
                assertNotSame(caller, result.getOrThrow())
                ready.countDown()
            }
            loader.request(1)
            assertTrue("Background preparation must complete", ready.await(2, TimeUnit.SECONDS))
            loader.close()
        } finally { worker.shutdownNow() }
    }
}
