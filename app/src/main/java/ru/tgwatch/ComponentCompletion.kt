package ru.tgwatch

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Logical component deadline; it never releases the physically executing settings job. */
internal class ComponentCompletion(private val finished: () -> Unit,
    schedule: (Long, () -> Unit) -> (() -> Unit) = ComponentDeadlines::schedule) {
    private val ended = AtomicBoolean()
    private val cancellation = AtomicReference<(() -> Unit)?>(null)
    val current: Boolean get() = !ended.get()
    init {
        cancellation.set(schedule(DEADLINE_MS, ::finish))
        if (ended.get()) cancellation.getAndSet(null)?.invoke()
    }
    fun finish() {
        if (ended.compareAndSet(false, true)) {
            try { cancellation.getAndSet(null)?.invoke() } finally { finished() }
        }
    }
    private companion object { const val DEADLINE_MS = 8_000L }
}

private object ComponentDeadlines {
    private val timer = ScheduledThreadPoolExecutor(1, { task ->
        Thread(task, "tg-component-deadline").apply { isDaemon = true }
    }).apply { removeOnCancelPolicy = true }
    fun schedule(delay: Long, action: () -> Unit): () -> Unit {
        val future = timer.schedule(action, delay, TimeUnit.MILLISECONDS)
        return { future.cancel(false) }
    }
}
