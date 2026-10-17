package ru.tgwatch

import android.content.Context
import java.io.File

/** A cached handle, installed only while holding History's monitor on the recovery worker. */
internal object TargetPersistence {
    @Volatile private var current: TargetStorage? = null
    val active: TargetStorage? get() = current
    fun storage(): TargetStorage = checkNotNull(current) { "Target storage is not ready" }

    fun initialize(ctx: Context) = synchronized(History) {
        if (current != null) return@synchronized
        val directory = File(ctx.applicationContext.createDeviceProtectedStorageContext().filesDir, "targets-v1")
        val storage = TargetStorage.open(directory, elapsedMs = android.os.SystemClock::elapsedRealtime)
        if (!storage.isInitialized) {
            val legacy = History.snapshot(ctx)
            storage.replaceGeneration(TargetBackupSnapshot(System.currentTimeMillis(), emptyMap(),
                TargetPreferences.definitions(ctx), TargetId.entries.associateWith { id ->
                    if (id == TargetId.TELEGRAM) legacy.map { TargetObservation(publicRow(it)) } else emptyList()
                }, emptyList()), supersedeLegacy = false)
        }
        current = storage
        History.invalidateTargetCache(storage.legacyMigrationComplete)
    }

    fun telegramRows(ctx: Context): List<Observation> {
        val revision = TargetPreferences.endpointRevision(ctx, TargetId.TELEGRAM)
        return storage().read(TargetId.TELEGRAM).filter { it.endpointRevision == revision }.map {
            if (it.row.kind == "DOWN") it.row.copy(kind = "TG_DOWN") else it.row
        }
    }

    fun replaceTelegram(ctx: Context, rows: List<Observation>, replaceAll: Boolean = false) {
        val store = storage()
        val revision = TargetPreferences.endpointRevision(ctx, TargetId.TELEGRAM)
        val archived = if (replaceAll) emptyList() else store.read(TargetId.TELEGRAM)
            .filter { it.endpointRevision != revision }
        val all = archived + rows.map { TargetObservation(publicRow(it), revision) }
        store.replaceRows(TargetId.TELEGRAM,
            TargetJournal.retained(all, Long.MIN_VALUE, TargetBackupCodec.TELEGRAM_ROWS))
    }

    fun appendTelegram(ctx: Context, row: Observation, sync: Boolean) {
        storage().append(TargetId.TELEGRAM,
            TargetObservation(publicRow(row), TargetPreferences.endpointRevision(ctx, TargetId.TELEGRAM)), sync)
    }

    fun replaceGeneration(ctx: Context, data: TargetBackupSnapshot,
        supersedeLegacy: Boolean = true) = synchronized(History) {
        initialize(ctx)
        storage().replaceGeneration(data, supersedeLegacy)
        History.invalidateTargetCache(storage().legacyMigrationComplete)
    }

    internal fun publicRow(row: Observation): Observation =
        if (row.kind == "TG_DOWN") row.copy(kind = "DOWN") else row
}
