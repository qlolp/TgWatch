package ru.tgwatch

import android.os.SystemClock
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class AutomationComputeHealth(val busy: Boolean, val deadlineExpired: Boolean)

/** The reply deadline never releases a physically blocked compute slot. */
internal class AutomationStateDispatcher(
    private val executor: Executor = executor(),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val deadline: (Long, () -> Unit) -> (() -> Unit) = AutomationDeadlines::schedule,
) {
    private val physical = AtomicReference<Attempt?>(null)
    private val generationLock = Any()
    private var generation = 0L

    fun invalidate() = synchronized(generationLock) {
        generation++
        physical.get()?.invalidate()
        AutomationStateCache.clear()
    }

    fun health(): AutomationComputeHealth {
        val current = physical.get()
        return AutomationComputeHealth(current != null, current?.expired == true)
    }

    fun submit(compute: () -> AutomationStateSnapshot, complete: (Result<AutomationStateSnapshot>) -> Unit,
        finish: () -> Unit, key: AutomationStateKey = AutomationStateKey()): Boolean {
        val admitted = synchronized(generationLock) { generation }
        val attempt = Attempt({ result -> synchronized(generationLock) {
            val current = if (admitted == generation) result else
                Result.failure(TimeoutException("State invalidated by data replacement"))
            AutomationStateCache.endCompute(clock(), current.getOrNull(), key)
            complete(current)
        }
        }, finish)
        if (!physical.compareAndSet(null, attempt)) {
            attempt.finish(Result.failure(RejectedExecutionException("State compute is busy")))
            return false
        }
        return schedule(attempt, compute)
    }

    private fun schedule(attempt: Attempt, compute: () -> AutomationStateSnapshot): Boolean {
        val registered = runCatching {
            attempt.bindDeadline(deadline(DEADLINE_MS) { attempt.expire() })
        }
        if (registered.isFailure || !attempt.current()) {
            physical.compareAndSet(attempt, null)
            registered.exceptionOrNull()?.let { attempt.finish(Result.failure(it)) }
            return false
        }
        return try {
            executor.execute {
                try {
                    if (attempt.current()) attempt.finish(runCatching(compute))
                } finally { physical.compareAndSet(attempt, null) }
            }
            true
        } catch (e: RejectedExecutionException) {
            physical.compareAndSet(attempt, null)
            attempt.finish(Result.failure(e))
            false
        }
    }

    private class Attempt(
        private val complete: (Result<AutomationStateSnapshot>) -> Unit,
        private val finished: () -> Unit,
    ) {
        private val ended = AtomicBoolean()
        private val cancel = AtomicReference<(() -> Unit)?>(null)
        @Volatile var expired = false
            private set
        fun current(): Boolean = !ended.get()
        fun bindDeadline(action: () -> Unit) {
            cancel.set(action)
            if (ended.get()) cancel.getAndSet(null)?.invoke()
        }
        fun expire() = finish(Result.failure(TimeoutException("State compute deadline exceeded")), true)
        fun invalidate() = finish(Result.failure(RejectedExecutionException("State invalidated by data replacement")))
        fun finish(result: Result<AutomationStateSnapshot>, timedOut: Boolean = false) {
            if (!ended.compareAndSet(false, true)) return
            expired = timedOut
            try { cancel.getAndSet(null)?.invoke() }
            finally { try { complete(result) } finally { finished() } }
        }
    }

    companion object {
        private const val DEADLINE_MS = 8_000L
        private const val IDLE_TIMEOUT_SEC = 30L
        private fun executor() = ThreadPoolExecutor(1, 1, IDLE_TIMEOUT_SEC, TimeUnit.SECONDS,
            SynchronousQueue(), { task -> Thread(task, "tg-automation-state").apply { isDaemon = true } })
            .apply { allowCoreThreadTimeOut(true) }
    }
}

private object AutomationDeadlines {
    private val timer = ScheduledThreadPoolExecutor(1,
        { task -> Thread(task, "tg-state-deadline").apply { isDaemon = true } })
        .apply { removeOnCancelPolicy = true }
    fun schedule(timeoutMs: Long, expired: () -> Unit): () -> Unit {
        val task = timer.schedule(expired, timeoutMs, TimeUnit.MILLISECONDS)
        return { task.cancel(false) }
    }
}
