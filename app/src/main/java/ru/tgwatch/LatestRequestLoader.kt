package ru.tgwatch

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** One active preparation, one replaceable pending request, and no stale UI deliveries. */
internal class LatestRequestLoader<K, V>(
    private val worker: Executor,
    private val delivery: Executor,
    private val load: (K) -> V,
    private val completed: (K, Result<V>) -> Unit,
) {
    private data class Request<K>(val key: K, val revision: Long)
    private val lock = Any()
    private var pending: Request<K>? = null
    private var latest: Request<K>? = null
    private var active = false
    private var closed = false
    private var revision = 0L

    fun request(key: K) {
        synchronized(lock) {
            if (closed || latest?.key == key) return
            val request = Request(key, ++revision)
            latest = request
            pending = request
            if (active) return
            active = true
            try {
                worker.execute(::drain)
            } catch (_: RejectedExecutionException) {
                active = false
                pending = null
                latest = null
            }
        }
    }

    private fun drain() {
        while (true) {
            val request = synchronized(lock) {
                pending?.also { pending = null } ?: run { active = false; return }
            }
            val value = runCatching { load(request.key) }
            deliver(request, value)
        }
    }

    private fun deliver(request: Request<K>, value: Result<V>) {
        try {
            delivery.execute {
                synchronized(lock) {
                    if (!closed && latest?.revision == request.revision) completed(request.key, value)
                }
            }
        } catch (_: RejectedExecutionException) {
            // A destroyed screen may have stopped its delivery executor.
        }
    }

    fun invalidate() {
        synchronized(lock) {
            ++revision
            latest = null
            pending = null
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            invalidate()
        }
    }
}
