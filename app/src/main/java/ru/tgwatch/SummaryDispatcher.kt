package ru.tgwatch

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One running summary and one waiting delivery; every accepted/rejected broadcast is finished. */
class SummaryDispatcher(
    private val executor: Executor = executor(),
    private val failed: (Exception) -> Unit = {},
    private val deadline: (Long, () -> Unit) -> (() -> Unit) = SummaryDeadlines::schedule,
    private val timeoutMs: Long = DEADLINE_MS,
) {
    init { require(timeoutMs in 1..DEADLINE_MS) }

    // Deadline registration failures must finish a broadcast as well as executor rejection.
    @Suppress("TooGenericExceptionCaught")
    fun submit(work: (() -> Boolean) -> Boolean, complete: (Boolean) -> Unit = {},
        finish: () -> Unit = {}): Boolean {
        val attempt = Attempt(complete, finish)
        val cancel = try { deadline(timeoutMs) { attempt.finish(false) } } catch (e: Exception) {
            try { failed(e) } finally { attempt.finish(false) }
            return false
        }
        attempt.bindDeadline(cancel)
        return try {
            executor.execute { run(attempt, work) }
            true
        } catch (_: RejectedExecutionException) {
            attempt.finish(false)
            false
        }
    }

    // A worker failure cannot retain PendingResult, even if reporting/completion throws too.
    @Suppress("TooGenericExceptionCaught")
    private fun run(attempt: Attempt, work: (() -> Boolean) -> Boolean) {
        try {
            if (attempt.isCurrent()) {
                val posted = try { work(attempt::isCurrent) } catch (e: Exception) { failed(e); false }
                attempt.finish(posted)
            }
        } finally { attempt.finish(false) }
    }

    private class Attempt(private val complete: (Boolean) -> Unit, private val finished: () -> Unit) {
        private val ended = AtomicBoolean()
        private val cancel = AtomicReference<(() -> Unit)?>(null)
        fun isCurrent(): Boolean = !ended.get()
        fun bindDeadline(action: () -> Unit) {
            cancel.set(action)
            if (ended.get()) cancel.getAndSet(null)?.invoke()
        }
        fun finish(posted: Boolean) {
            if (!ended.compareAndSet(false, true)) return
            try { cancel.getAndSet(null)?.invoke() }
            finally { try { complete(posted) } finally { finished() } }
        }
    }

    companion object {
        const val DEADLINE_MS = 8_000L
        private const val IDLE_TIMEOUT_SEC = 30L
        private fun executor() = ThreadPoolExecutor(1, 1, IDLE_TIMEOUT_SEC, TimeUnit.SECONDS, ArrayBlockingQueue(1),
            { task -> Thread(task, "tg-summary").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
            .apply { allowCoreThreadTimeOut(true) }
    }
}

private object SummaryDeadlines {
    private val timer = ScheduledThreadPoolExecutor(1,
        { task -> Thread(task, "tg-summary-deadline").apply { isDaemon = true } })
        .apply { removeOnCancelPolicy = true }
    fun schedule(timeoutMs: Long, expired: () -> Unit): () -> Unit {
        val task = timer.schedule(expired, timeoutMs, TimeUnit.MILLISECONDS)
        return { task.cancel(false) }
    }
}
