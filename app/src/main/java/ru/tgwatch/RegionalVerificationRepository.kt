package ru.tgwatch

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.CompletableFuture

/** Only explicit reports enter this store. Historical rows are never used to populate live notes. */
internal object RegionalVerificationRepository {
    private val state = RegionalVerificationState()

    fun report(ctx: Context, service: RegionalService, layer: RegionalVerificationLayer,
        outcome: RegionalVerificationOutcome): CompletableFuture<Boolean> {
        val generation = RegionalRepository.generation(ctx)
        val connection = RegionalContext.active(ctx)
        val note = runCatching { RegionalVerification(service, layer, outcome,
            System.currentTimeMillis().coerceAtLeast(0), SystemClock.elapsedRealtime(), connection) }
            .getOrElse { return CompletableFuture.completedFuture(false) }
        return record(ctx, note, generation)
    }

    fun record(ctx: Context, note: RegionalVerification, generation: Long): CompletableFuture<Boolean> =
        RegionalRepository.recordEntries(ctx, listOf(note.entry()), includeContext = true,
            expectedGeneration = generation).thenCompose { saved ->
            if (!saved) CompletableFuture.completedFuture(false) else
                RegionalRepository.transaction(ctx, generation) {
                    val same = RegionalContext.active(ctx).token == note.connection.token
                    if (same && !BackupManager.hasPending()) state.record(note)
                    true
                }
        }

    fun current(ctx: Context, service: RegionalService, layer: RegionalVerificationLayer): RegionalVerification? =
        if (BackupManager.hasPending()) null else state.current(service, layer, RegionalContext.active(ctx),
            System.currentTimeMillis(), SystemClock.elapsedRealtime())

    fun reset() { state.reset() }
}
