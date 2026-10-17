package ru.tgwatch

import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** One physical worker; stop/recovery barriers never compete with the bounded record queue. */
class HistorySessionQueue(
    private val executor: Executor,
    private val endSession: (Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Transition(val operation: () -> Unit, val done: CompletableFuture<Unit>)
    private val lock = Any()
    private val records = ArrayDeque<() -> Unit>()
    private val transitions = ArrayDeque<Transition>()
    private var session = 0L
    private var closed = true
    private var hasRecords = false
    private var draining = false
    private var activeTransition = false
    private var lastBarrier = CompletableFuture.completedFuture(Unit)
    @Volatile var backpressured = false
        private set

    fun open(): Long = synchronized(lock) {
        if (!closed) close(session, clock())
        session++
        closed = false
        hasRecords = false
        session
    }

    fun submit(session: Long, operation: () -> Unit): Boolean = synchronized(lock) {
        val eligible = session == this.session && !closed && !activeTransition && transitions.isEmpty()
        if (!eligible || records.size >= MAX_RECORDS) {
            backpressured = true
            return false
        }
        records.addLast(operation)
        hasRecords = true
        scheduleLocked().also { if (!it) backpressured = true }
    }

    fun close(session: Long, atMs: Long): CompletableFuture<Unit> = synchronized(lock) {
        if (session != this.session || closed) return lastBarrier
        closed = true
        if (!hasRecords) return lastBarrier
        enqueueTransition { endSession(atMs) }
    }

    fun exclusive(operation: () -> Unit): CompletableFuture<Unit> = synchronized(lock) {
        enqueueTransition(operation)
    }

    fun closedBarrier(): CompletableFuture<Unit> = synchronized(lock) { lastBarrier }

    fun pendingRecords(): Int = synchronized(lock) { records.size }
    fun pendingTransitions(): Int = synchronized(lock) { transitions.size + if (activeTransition) 1 else 0 }

    private fun enqueueTransition(operation: () -> Unit): CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()
        if (transitions.size + (if (activeTransition) 1 else 0) >= MAX_TRANSITIONS) {
            future.completeExceptionally(RejectedExecutionException("History transition is busy"))
            return future
        }
        transitions.addLast(Transition(operation, future))
        lastBarrier = future
        scheduleLocked()
        return future
    }

    private fun scheduleLocked(): Boolean {
        if (draining) return true
        draining = true
        return try {
            executor.execute(::drain)
            true
        } catch (e: RejectedExecutionException) {
            draining = false
            records.clear()
            while (transitions.isNotEmpty()) transitions.removeFirst().done.completeExceptionally(e)
            false
        }
    }

    private fun drain() {
        while (true) {
            val task: () -> Unit
            val barrier: CompletableFuture<Unit>?
            synchronized(lock) {
                if (records.isNotEmpty()) {
                    task = records.removeFirst()
                    barrier = null
                } else if (transitions.isNotEmpty()) {
                    val next = transitions.removeFirst()
                    task = next.operation
                    barrier = next.done
                    activeTransition = true
                } else {
                    draining = false
                    return
                }
            }
            val result = runCatching(task)
            synchronized(lock) {
                activeTransition = false
                if (result.isSuccess) {
                    if (barrier != null) backpressured = false
                } else backpressured = true
            }
            if (barrier != null) result.fold({ barrier.complete(Unit) }, { barrier.completeExceptionally(it) })
        }
    }

    companion object {
        private const val MAX_RECORDS = 64
        private const val MAX_TRANSITIONS = 2
    }
}
