package ru.tgwatch

import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Bounded logical waiters. Transport ownership remains in ProbeBatch, independently of cancellation. */
internal class TargetDispatch(private val delivery: Executor, private val changed: () -> Unit) : Closeable {
    private val executor = ThreadPoolExecutor(WAITERS, WAITERS, 0L, TimeUnit.MILLISECONDS, SynchronousQueue(),
        { r -> Thread(r, "tg-target-wait").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val pending = mutableMapOf<TargetId, FutureTask<TargetReport>>()
    private var closed = false
    val busy: Boolean get() = synchronized(this) { pending.isNotEmpty() }

    @Synchronized fun submit(attempt: TargetAttempt, operation: () -> TargetReport,
        completed: (Result<TargetReport>) -> Unit): Boolean {
        if (closed || attempt.id in pending) return false
        val task = object : FutureTask<TargetReport>(operation) {
            override fun done() {
                delivery.execute { finish(attempt.id, this, completed) }
            }
        }
        pending[attempt.id] = task
        return try { executor.execute(task); true }
        catch (_: RejectedExecutionException) { task.cancel(false); false }
    }

    private fun finish(id: TargetId, task: FutureTask<TargetReport>, completed: (Result<TargetReport>) -> Unit) {
        val current = synchronized(this) { !closed && pending[id] === task }
        if (current) {
            // Optional target failure must not terminate the primary owner's Handler.
            runCatching { completed(result(task)) }
            synchronized(this) { if (pending[id] === task) pending.remove(id) }
            changed()
        }
    }

    private fun result(task: FutureTask<TargetReport>): Result<TargetReport> = try { Result.success(task.get()) }
        catch (e: CancellationException) { Result.failure(e) }
        catch (e: ExecutionException) { Result.failure(e) }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); Result.failure(e) }

    fun cancel(id: TargetId) { synchronized(this) { pending[id] }?.cancel(true) }
    fun cancelAll() { synchronized(this) { pending.values.toList() }.forEach { it.cancel(true) } }
    override fun close() {
        val tasks = synchronized(this) {
            closed = true
            pending.values.toList().also { pending.clear() }
        }
        tasks.forEach { it.cancel(true) }
        executor.shutdownNow()
    }
    private companion object { const val WAITERS = 3 }
}
