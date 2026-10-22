package ru.tgwatch

import android.content.Context
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One bounded disk worker; UI only sees immutable cached snapshots. Restore fences queued writes. */
internal object RegionalRepository {
    private const val QUEUE_SIZE = 64
    private const val WAIT_SECONDS = 10L
    private const val PRUNE_MS = 60_000L
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(QUEUE_SIZE),
        { task -> Thread(task, "regional-history").apply { isDaemon = true } })
    private class Owner(val file: File) {
        val journal = RegionalJournalSession(RegionalJournalStorage(file))
        val loadRequested = AtomicBoolean()
        val lastPrune = AtomicLong()
        @Volatile var busy = false
    }
    private var owner: Owner? = null

    fun fileFor(ctx: Context): File = File(ctx.applicationContext.createDeviceProtectedStorageContext()
        .filesDir, "regional-events-v1.txt")
    @Synchronized private fun owner(ctx: Context): Owner = owner ?: Owner(fileFor(ctx)).also { owner = it }

    fun snapshot(ctx: Context): RegionalJournalSnapshot {
        val current = owner(ctx)
        ensureLoaded(current)
        val state = current.journal.snapshot
        return if (current.busy) state.copy(error = RegionalJournalError.BUSY) else state
    }

    fun recordEntry(ctx: Context, row: RegionalJournalEntry): CompletableFuture<Boolean> =
        recordEntries(ctx, listOf(row))

    fun recordEntries(ctx: Context, rows: List<RegionalJournalEntry>,
        includeContext: Boolean = false): CompletableFuture<Boolean> {
        val current = owner(ctx)
        val ticket = current.journal.generation
        val captured = rows.toList()
        return queue(current) {
            val now = System.currentTimeMillis().coerceAtLeast(0)
            current.journal.load(now)
            val first = captured.firstOrNull()
            if (includeContext && first != null && current.journal.snapshot.rows.lastOrNull()?.scope != first.scope) {
                current.journal.append(ticket, first.copy(kind = RegionalEventKind.NETWORK, service = null,
                    status = null, confirmed = false, httpCode = null, failure = null), now)
            }
            captured.map { current.journal.append(ticket, it, now) }.all { it }
        }
    }

    private fun ensureLoaded(current: Owner) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (current.loadRequested.compareAndSet(false, true)) {
            current.lastPrune.set(now)
            queue(current) { current.journal.load(System.currentTimeMillis().coerceAtLeast(0)); true }
                .whenComplete { accepted, failure ->
                    if (failure != null || accepted != true) current.loadRequested.set(false)
                }
        } else {
            val previous = current.lastPrune.get()
            if ((now < previous || now - previous >= PRUNE_MS) && current.lastPrune.compareAndSet(previous, now)) {
                val ticket = current.journal.generation
                queue(current) { current.journal.prune(ticket, System.currentTimeMillis().coerceAtLeast(0)) }
            }
        }
    }

    /** Called from the already-background restore transaction, never from a UI click. */
    fun clearForRestore(ctx: Context) {
        val current = owner(ctx)
        current.journal.clear()
        current.loadRequested.set(true)
        RegionalContext.invalidate()
    }

    fun clear(ctx: Context): CompletableFuture<Boolean> {
        val current = owner(ctx)
        val ticket = current.journal.generation
        return queue(current) {
            current.journal.clearIfCurrent(ticket).also { cleared ->
                if (cleared) { current.loadRequested.set(true); RegionalContext.invalidate() }
            }
        }
    }
    fun awaitIdle(ctx: Context) { queue(owner(ctx)) { true }.get(WAIT_SECONDS, TimeUnit.SECONDS) }

    private fun queue(current: Owner, action: () -> Boolean): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()
        try {
            worker.execute {
                current.busy = false
                runCatching(action).fold(future::complete, future::completeExceptionally)
            }
        } catch (_: RejectedExecutionException) {
            current.busy = true
            future.complete(false)
        }
        return future
    }
}
