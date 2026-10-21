package ru.tgwatch

import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

internal class BackupJobStopped(val phase: BackupJobPhase) : IOException("Backup operation stopped")

/** Bounded cancellation actions run away from the UI and deadline thread. */
internal class BackupJobControl(private val clock: () -> Long, private val deadline: Long?,
    private val cleanup: Executor, sensitive: List<CharArray> = emptyList()) {
    private var worker: Thread? = null
    private var stopped: BackupJobPhase? = null
    private val actions = mutableListOf<() -> Unit>()
    private val secrets = sensitive.toMutableList()

    @Synchronized fun attach() { worker = Thread.currentThread(); check() }
    @Synchronized fun check() {
        val reason = stopped ?: when {
            deadline != null && clock() >= deadline -> BackupJobPhase.TIMED_OUT
            Thread.currentThread().isInterrupted -> BackupJobPhase.CANCELLED
            else -> null
        }
        if (reason != null) throw BackupJobStopped(reason)
    }
    @Synchronized fun secret(password: CharArray) {
        if (stopped != null) password.fill('\u0000') else secrets.add(password)
        check()
    }
    fun onStop(action: () -> Unit) {
        val closeNow = synchronized(this) {
            if (stopped != null) true else { actions.add(action); false }
        }
        if (closeNow) closeAsync(action)
    }
    fun stop(reason: BackupJobPhase) {
        val pending = synchronized(this) {
            if (stopped != null) return
            stopped = reason
            secrets.forEach { it.fill('\u0000') }
            worker?.interrupt()
            actions.toList().also { actions.clear() }
        }
        pending.forEach(::closeAsync)
    }
    @Synchronized fun finish() {
        worker = null
        secrets.forEach { it.fill('\u0000') }
        secrets.clear()
        actions.clear()
        Thread.interrupted()
    }
    private fun closeAsync(action: () -> Unit) {
        try { cleanup.execute { runCatching(action) } }
        catch (_: RejectedExecutionException) { /* The physical worker retains ownership until finally. */ }
    }
}
