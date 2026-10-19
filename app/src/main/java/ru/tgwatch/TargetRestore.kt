package ru.tgwatch

import android.content.Context
import android.os.Looper
import android.os.UserManager
import java.io.File

/** A pending marker gates all observers until histories, public settings and log agree. */
internal object TargetRestore {
    fun requireUnlocked(ctx: Context) {
        if (!ctx.applicationContext.getSystemService(UserManager::class.java).isUserUnlocked)
            throw RecoveryDeferredException()
    }

    @Synchronized fun stage(ctx: Context, imported: TargetBackupImport) {
        check(Looper.myLooper() != Looper.getMainLooper())
        requireUnlocked(ctx)
        BackupJournals.refresh(ctx)
        check(!BackupJournals.pending) { "Complete pending restore before replacing data" }
        val bytes = TargetBackupCodec.encode(imported.snapshot)
        try { BackupJournals.transaction(ctx).stage(bytes, imported.legacyEndpoint, imported.metadata) }
        finally { bytes.fill(0); BackupJournals.refresh(ctx) }
    }

    fun apply(ctx: Context, imported: TargetBackupImport, cleared: Set<String>,
        syncPreferences: (File) -> Unit = HistoryStorage::syncDirectory) = synchronized(History) {
        val preferences = Prefs.sp(ctx).all.toMap()
        val privatePreferences = ctx.applicationContext.getSharedPreferences(
            TargetPreferences.PRIVATE_FILE, Context.MODE_PRIVATE)
        val oldPrivate = privatePreferences.all.toMap()
        val applied = runCatching {
            // A settings job captured before replacement cannot reactivate or reconfigure restored data.
            synchronized(TargetPreferences) { TargetId.entries.forEach(TargetPreferences.fence::bump) }
            val complete = imported.snapshot.copy(targets = TargetId.entries.map { id ->
                imported.snapshot.targets.firstOrNull { it.id == id }
                    ?: TargetDefinition.default(id).copy(enabled = false)
            }, observations = TargetId.entries.associateWith { imported.snapshot.observations[it].orEmpty() })
            TargetPersistence.replaceGeneration(ctx, complete) {
                val edit = Prefs.sp(ctx).edit().putBoolean("enabled", false).putBoolean(TargetPowerSettings.KEY, false)
                cleared.forEach(edit::remove)
                preferences.keys.filter { it.startsWith("target.") }.forEach(edit::remove)
                imported.snapshot.globalSettings.forEach { (key, value) -> put(edit, key, value) }
                for (id in TargetId.entries) {
                    val definition = imported.snapshot.targets.firstOrNull { it.id == id }
                        ?: TargetDefinition.default(id)
                    definitions(edit, definition)
                    val revision = imported.snapshot.observations[id].orEmpty()
                        .maxOfOrNull { it.endpointRevision } ?: 0L
                    edit.putLong(TargetPreferences.key(id, "endpoint_revision"), revision)
                    edit.putBoolean(TargetPreferences.key(id, "endpoint_review"),
                        review(preferences, imported, definition))
                }
                check(edit.commit()) { "Cannot restore target settings" }
                imported.legacyEndpoint?.let { check(PrivateEndpoints.setLegacy(ctx, it, duringRecovery = true)) }
                EventLog.replace(ctx, imported.snapshot.log)
                LastSuccessStore.clear(ctx)
                MonitorService.clearRestoredState()
                syncPreferences(File(ctx.applicationContext.createDeviceProtectedStorageContext()
                    .dataDir, "shared_prefs"))
            }
        }
        applied.exceptionOrNull()?.let { failure ->
            if (failure is Error) throw failure
            // A recoverable runtime failure leaves old public data observable only after a successful retry.
            val rollback = runCatching {
                val edit = Prefs.sp(ctx).edit().clear()
                preferences.forEach { (key, value) -> if (value != null) put(edit, key, value) }
                check(edit.commit())
                val privateEdit = privatePreferences.edit().clear()
                oldPrivate.forEach { (key, value) -> if (value != null) put(privateEdit, key, value) }
                check(privateEdit.commit())
                HistoryStorage.syncDirectory(File(ctx.applicationContext.dataDir, "shared_prefs"))
                EventLog.invalidate()
            }
            rollback.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        BackupJournals.removeLegacy(ctx)
        TargetPersistence.storage().cleanupRetired()
        StatusWidget.updateAll(ctx)
        DailySummaryReceiver.schedule(ctx)
    }

    private fun review(preferences: Map<String, *>, imported: TargetBackupImport,
        definition: TargetDefinition): Boolean =
        if (definition.id in imported.metadata.reviewTargets) definition.requiresEndpoint
        else preferences[TargetPreferences.key(definition.id, "endpoint_review")] as? Boolean
            ?: definition.requiresEndpoint

    private fun definitions(edit: android.content.SharedPreferences.Editor, definition: TargetDefinition) {
        fun key(suffix: String) = TargetPreferences.key(definition.id, suffix)
        if (definition.id == TargetId.TELEGRAM) edit.putInt("interval_sec", definition.intervalSec)
            .putBoolean("notify_outage", definition.alerts.notifyOutage)
            .putBoolean("notify_recovery", definition.alerts.notifyRecovery)
            .putBoolean("notify_slow", definition.alerts.notifySlow)
        edit.putBoolean(key("enabled"), false).putInt(key("interval"), definition.intervalSec)
            .putBoolean(key("requires_endpoint"), definition.requiresEndpoint)
            .putBoolean(key("notify_outage"), definition.alerts.notifyOutage)
            .putBoolean(key("notify_recovery"), definition.alerts.notifyRecovery)
            .putBoolean(key("notify_slow"), definition.alerts.notifySlow)
            .putLong(key("slow_threshold"), definition.alerts.slowThresholdMs)
    }

    private fun put(edit: android.content.SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is String -> edit.putString(key, value)
            is Set<*> -> edit.putStringSet(key, value.map { it as String }.toSet())
            else -> error("Invalid setting")
        }
    }
}
