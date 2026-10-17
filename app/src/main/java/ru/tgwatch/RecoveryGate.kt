package ru.tgwatch

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

enum class RecoveryState { INITIALIZING, READY, FAILED }
internal class RecoveryDeferredException : IllegalStateException("Recovery waits for credential storage")

/** A failed durable replay never opens the write/probe gate; retry uses the preserved journal. */
internal class RecoveryGate(private val executor: Executor) {
    private val lock = Any()
    private var started = false
    private val readyActions = linkedSetOf<() -> Unit>()
    @Volatile var state = RecoveryState.INITIALIZING
        private set

    fun start(recover: () -> Unit) {
        synchronized(lock) {
            if (started && state != RecoveryState.FAILED) return
            started = true
            state = RecoveryState.INITIALIZING
        }
        try {
            executor.execute {
                val result = runCatching(recover)
                val recovered = result.isSuccess
                val deferred = result.exceptionOrNull() is RecoveryDeferredException
                val actions = synchronized(lock) {
                    state = when { recovered -> RecoveryState.READY
                        deferred -> RecoveryState.INITIALIZING
                        else -> RecoveryState.FAILED }
                    if (deferred) started = false
                    if (recovered) readyActions.toList().also { readyActions.clear() } else emptyList()
                }
                actions.forEach { runCatching(it) }
            }
        } catch (_: RejectedExecutionException) {
            state = RecoveryState.FAILED
        }
    }

    fun whenReady(action: () -> Unit) {
        val ready = synchronized(lock) {
            if (state == RecoveryState.READY) true else { readyActions.add(action); false }
        }
        if (ready) action()
    }
}
