package ru.tgwatch

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

enum class RecoveryState { INITIALIZING, READY, FAILED }

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
                val recovered = runCatching(recover).isSuccess
                val actions = synchronized(lock) {
                    state = if (recovered) RecoveryState.READY else RecoveryState.FAILED
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
