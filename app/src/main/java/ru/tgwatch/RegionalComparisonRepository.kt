package ru.tgwatch

import android.content.Context
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

internal data class RegionalComparisonHistory(val pairs: List<RegionalComparisonPair> = emptyList(),
    val loading: Boolean = true, val error: Boolean = false)

/** Uses the journal's one worker/fence, including an atomic restore clear across both files. */
internal object RegionalComparisonRepository {
    private class Store(file: File) {
        val disk = RegionalComparisonStorage(file)
        val requested = AtomicBoolean()
        @Volatile var snapshot = RegionalComparisonHistory()
        fun read(): Boolean {
            val result = runCatching { disk.read() }
            snapshot = result.fold({ RegionalComparisonHistory(it, false) },
                { snapshot.copy(loading = false, error = true) })
            return result.isSuccess
        }
        fun clear() {
            disk.write(emptyList())
            snapshot = RegionalComparisonHistory(loading = false)
            requested.set(true)
        }
    }
    private var store: Store? = null
    fun fileFor(ctx: Context): File = File(ctx.applicationContext.createDeviceProtectedStorageContext()
        .filesDir, "regional-comparisons-v1.bin")
    @Synchronized private fun store(ctx: Context): Store = store ?: Store(fileFor(ctx)).also { store = it }

    fun snapshot(ctx: Context): RegionalComparisonHistory {
        val current = store(ctx)
        if (current.requested.compareAndSet(false, true)) {
            RegionalRepository.transaction(ctx, RegionalRepository.generation(ctx), current::read)
                .whenComplete { accepted, failure ->
                    if (failure != null || accepted != true) current.requested.set(false)
                }
        }
        return current.snapshot
    }

    fun save(ctx: Context, pair: RegionalComparisonPair, generation: Long): CompletableFuture<Boolean> {
        if (runCatching { RegionalComparisonCodec.validate(pair) }.isFailure)
            return CompletableFuture.completedFuture(false)
        val current = store(ctx)
        return RegionalRepository.transaction(ctx, generation) {
            val readable = !current.snapshot.loading && !current.snapshot.error || current.read()
            if (!readable) false else {
                val pairs = (current.snapshot.pairs + pair).takeLast(RegionalComparisonCodec.MAX_PAIRS)
                val written = runCatching { current.disk.write(pairs) }.isSuccess
                current.snapshot = if (written) RegionalComparisonHistory(pairs, false)
                    else current.snapshot.copy(error = true)
                current.requested.set(true)
                written
            }
        }
    }

    fun clear(ctx: Context, generation: Long): CompletableFuture<Boolean> =
        RegionalRepository.transaction(ctx, generation) { store(ctx).clear(); true }

    /** Caller holds the journal's generation lock inside the background restore transaction. */
    fun clearForRestore(ctx: Context) { store(ctx).clear() }
}
