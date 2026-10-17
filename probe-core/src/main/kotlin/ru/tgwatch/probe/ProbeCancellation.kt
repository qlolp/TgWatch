package ru.tgwatch.probe

import java.io.Closeable
import java.util.concurrent.*

class ProbeCancellation : Closeable {
    private var cancelled = false
    private val actions = mutableListOf<() -> Unit>()
    fun register(action: () -> Unit) {
        val closeNow = synchronized(this) { if (cancelled) true else { actions.add(action); false } }
        if (closeNow) clean(action)
    }
    @Synchronized fun check() {
        if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedException("cancelled")
    }
    override fun close() {
        val copy = synchronized(this) {
            cancelled = true
            actions.toList().also { actions.clear() }
        }
        copy.forEach(::clean)
    }
    companion object {
        // A broken platform disconnect must not block the batch coordinator or grow a queue.
        private val cleanup = ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, SynchronousQueue(),
            { r -> Thread(r, "tg-probe-close").apply { isDaemon = true } })
        private fun clean(action: () -> Unit) {
            try { cleanup.execute { try { action() } catch (_: Exception) {} } }
            catch (_: RejectedExecutionException) { /* Worker finally/transport timeout still owns cleanup. */ }
        }
    }
}
