package ru.tgwatch

import java.io.Closeable
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One reused, queue-free waiter; the separate EXTRA transport pool retains physically occupied workers. */
class ExtraProbeCoordinator : Closeable {
    class Token internal constructor(
        internal val owner: ExtraProbeCoordinator,
        internal val generation: Long,
        val revision: Long,
        val networkHandle: Long,
    )

    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, SynchronousQueue(),
        { r -> Thread(r, "tg-extra-results").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private var generation = 0L
    private var closed = false
    private var current: Token? = null
    private var pending: FutureTask<Unit>? = null

    @Synchronized fun begin(revision: Long, networkHandle: Long): Token {
        invalidate()
        return Token(this, generation, revision, networkHandle).also { if (!closed) current = it }
    }

    /** Call from the service worker again at delivery, after checking enabled/stop/restore state. */
    @Synchronized fun accepts(token: Token, revision: Long, networkHandle: Long?): Boolean =
        !closed && current === token && token.owner === this && token.generation == generation &&
            token.revision == revision && token.networkHandle == networkHandle

    @Synchronized fun submit(token: Token, operation: () -> List<ProbeOutcome>,
        deliver: (List<ProbeOutcome>) -> Unit): Boolean {
        if (!accepts(token, token.revision, token.networkHandle) || pending != null) return false
        val task = FutureTask<Unit> {
            val results = operation()
            if (!Thread.currentThread().isInterrupted && accepts(token, token.revision, token.networkHandle)) {
                deliver(results)
            }
        }
        pending = task
        return try {
            executor.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            pending = null
            false
        }
    }

    @Synchronized fun invalidate() {
        generation++
        current = null
        pending?.cancel(true)
        pending = null
    }

    @Synchronized override fun close() {
        closed = true
        invalidate()
        executor.shutdownNow()
    }
}
