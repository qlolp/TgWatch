package ru.tgwatch

import android.content.Context
import android.os.Looper
import android.os.UserManager
import java.io.File

/** Accepted endpoint changes replay forward; only the affected goal is parked while private IO is unavailable. */
internal object TargetConfigTransactions {
    @Volatile private var pending: Set<TargetId> = emptySet()
    fun isPending(id: TargetId): Boolean = id in pending
    val anyPending: Boolean get() = pending.isNotEmpty()

    fun discover(ctx: Context) {
        pending = TargetId.entries.filter { marker(ctx, it).exists() }.toSet()
    }

    fun resume(ctx: Context) {
        val unlocked = ctx.applicationContext.getSystemService(UserManager::class.java).isUserUnlocked
        if (!unlocked || pending.isEmpty()) return
        HistorySessions.queue(ctx).exclusive { synchronized(History) {
            pending.toList().forEach { runCatching { replay(ctx, it) } }
        } }
    }

    fun retry(ctx: Context, id: TargetId): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper())
        return if (!isPending(id)) true else {
            val token = TargetPreferences.fence.begin(id) ?: return false
            try {
                HistorySessions.queue(ctx).exclusive { synchronized(History) { replay(ctx, id) } }.get()
                !isPending(id)
            }
            catch (_: Exception) { false }
            finally { TargetPreferences.fence.finish(token) }
        }
    }

    /** Invoked only by the ordered worker, after all earlier accepted observations have drained. */
    fun change(ctx: Context, id: TargetId, endpoint: String?) {
        TargetRestore.requireUnlocked(ctx)
        val store = TargetPersistence.storage()
        val old = TargetPreferences.storedDefinition(ctx, id)
        val now = System.currentTimeMillis()
        val revision = TargetPreferences.endpointRevision(ctx, id)
        val rows = store.recordedRows(id, revision, now).valueOrThrow()
        val next = TargetEndpointRevision.change(rows, revision, now, TargetBackupCodec.rowLimit(id))
        val definition = old.copy(requiresEndpoint = id == TargetId.CUSTOM || endpoint != null,
            enabled = old.enabled && (id != TargetId.CUSTOM || endpoint != null))
        val data = TargetBackupSnapshot(System.currentTimeMillis(), emptyMap(), listOf(definition),
            mapOf(id to next.observations), emptyList())
        val bytes = TargetBackupCodec.encode(data)
        try {
            transaction(ctx, id).stage(bytes, endpoint, PrivateRestoreMetadata(setOf(id), store.generationId))
        } finally {
            bytes.fill(0)
            discover(ctx)
        }
        replay(ctx, id)
    }

    private fun replay(ctx: Context, id: TargetId) {
        TargetRestore.requireUnlocked(ctx)
        try {
            transaction(ctx, id).recoverWithMetadata { bytes, endpoint, metadata ->
                val data = TargetBackupCodec.decode(bytes)
                require(data.targets.size == 1 && data.targets.single().id == id && metadata.baseGenerationId != null)
                val store = TargetPersistence.storage()
                if (metadata.baseGenerationId != store.generationId) {
                    check(Prefs.sp(ctx).edit().putBoolean(TargetPreferences.key(id, "endpoint_review"), true).commit())
                } else apply(ctx, id, data, endpoint)
            }
        } finally { discover(ctx) }
    }

    private fun apply(ctx: Context, id: TargetId, data: TargetBackupSnapshot, endpoint: String?) {
        val rows = data.observations.getValue(id)
        val definition = data.targets.single()
        TargetPersistence.storage().replaceRows(id, rows)
        val credentials = ctx.applicationContext.getSharedPreferences(
            TargetPreferences.PRIVATE_FILE, Context.MODE_PRIVATE)
        check(credentials.edit().putString(id.wire, endpoint).commit()) { "Cannot save private endpoint" }
        synchronized(TargetPreferences) {
            // A stop received during private IO wins over the enabled flag captured at preparation time.
            val enabled = TargetPreferences.storedDefinition(ctx, id).enabled &&
                (id != TargetId.CUSTOM || endpoint != null)
            TargetSettingWrites.write(Prefs.sp(ctx).edit(), definition.copy(enabled = enabled))
                .putLong(TargetPreferences.key(id, "endpoint_revision"), rows.maxOf { it.endpointRevision })
                .putBoolean(TargetPreferences.key(id, "endpoint_review"), false).apply()
        }
        check(Prefs.sp(ctx).edit().commit()) { "Cannot finish endpoint settings" }
        HistoryStorage.syncDirectory(File(ctx.applicationContext.dataDir, "shared_prefs"))
        val dp = ctx.applicationContext.createDeviceProtectedStorageContext()
        HistoryStorage.syncDirectory(File(dp.dataDir, "shared_prefs"))
    }

    private fun transaction(ctx: Context, id: TargetId) = PrivateRestoreTransaction(marker(ctx, id),
        File(ctx.applicationContext.filesDir, "target-config-private-${id.wire}"))
    private fun marker(ctx: Context, id: TargetId) = File(ctx.applicationContext.createDeviceProtectedStorageContext()
        .filesDir, "target-config-${id.wire}-pending")
}
