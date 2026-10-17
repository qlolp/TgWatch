package ru.tgwatch

import android.content.Context
import java.io.File

/** Tests inspect the authoritative generation rather than the intentionally preserved migration source. */
internal object HistoryTestStorage {
    fun awaitReady() {
        val deadline = android.os.SystemClock.elapsedRealtime() + 20_000L
        while (RecoveryCoordinator.state == RecoveryState.INITIALIZING &&
            android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(20)
        check(RecoveryCoordinator.state == RecoveryState.READY) { "Startup recovery did not become ready" }
    }
    fun file(ctx: Context): File {
        TargetPersistence.initialize(ctx)
        return TargetPersistence.storage().journalFile(TargetId.TELEGRAM)
    }
    fun rows(ctx: Context): List<Observation> = TargetPersistence.telegramRows(ctx)
}
