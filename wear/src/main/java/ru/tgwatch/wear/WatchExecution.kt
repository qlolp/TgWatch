package ru.tgwatch.wear

import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

interface WatchScheduler { fun schedule(delayMs: Long, task: () -> Unit): () -> Unit }
data class WatchClocks(val wall: () -> Long, val elapsed: () -> Long, val boot: () -> Long,
    val networkRevision: () -> Long = { 0L })

/** One physically running coordinator, shared even when activities/controllers are replaced. */
class WatchExecution(val coordinator: ExecutorService, val scheduler: WatchScheduler) {
    private val sequence = AtomicLong()
    val requestCount = AtomicLong()
    fun seedRevision(value: Long) { sequence.updateAndGet { maxOf(it, value) } }
    fun nextRevision(): Long {
        requestCount.incrementAndGet()
        // Attempt identity is an object token; saturation cannot revive an older attempt.
        return sequence.updateAndGet { if (it == Long.MAX_VALUE) it else it + 1L }
    }
    companion object {
        private val deadlines = ScheduledThreadPoolExecutor(1, { task ->
            Thread(task, "tg-watch-deadline").apply { isDaemon = true }
        }).apply { removeOnCancelPolicy = true }
        val process = WatchExecution(ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, SynchronousQueue(),
            { task -> Thread(task, "tg-watch-check").apply { isDaemon = true } }), object : WatchScheduler {
            override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
                val future = deadlines.schedule(task, delayMs, TimeUnit.MILLISECONDS)
                return { future.cancel(false) }
            }
        })
    }
}
