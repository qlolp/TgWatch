package ru.tgwatch

import android.content.Context
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import java.io.File

/** Portable data crosses this boundary only after full schema/authentication validation. */
object BackupManager {
    @Volatile private var replacingHistory = false
    private val settingKeys = setOf("power_profile", "interval_sec", "keep_awake", "vibrate",
        "vibrate_partial", "vibrate_offline", "vibrate_recovery", "notify_recovery", "event_sound",
        "vibration_pattern", "quiet_hours", "quiet_start_hour", "quiet_end_hour",
        "quiet_start_minute", "quiet_end_minute", "notify_outage", "notify_partial",
        "sound_outage", "sound_partial", "sound_recovery")
    private val transientKeys = setOf("last_status", "last_checked", "last_since", "last_latency",
        "last_reason", "last_vibe", "last_diagnostics", "last_expected_sec", "last_success_at")

    private fun pending(ctx: Context) = File(ctx.applicationContext.createDeviceProtectedStorageContext()
        .filesDir, "restore-pending-v1.json")

    private fun publishedPending(ctx: Context) = File(ctx.applicationContext.createDeviceProtectedStorageContext()
        .filesDir, "restore.pending")

    private fun hasJournal(ctx: Context): Boolean = pending(ctx).exists() || publishedPending(ctx).exists()
    fun hasPending(ctx: Context): Boolean = replacingHistory || hasJournal(ctx)

    @Synchronized fun snapshot(ctx: Context): BackupSnapshot = BackupSnapshot(System.currentTimeMillis(), linkedMapOf(
        "power_profile" to (Prefs.profile(ctx)?.name ?: "CUSTOM"),
        "interval_sec" to Prefs.intervalSec(ctx), "keep_awake" to Prefs.keepAwake(ctx),
        "vibrate" to Prefs.vibrateEnabled(ctx), "vibrate_partial" to Prefs.vibratePartial(ctx),
        "vibrate_offline" to Prefs.vibrateOffline(ctx), "vibrate_recovery" to Prefs.vibrateOnRecovery(ctx),
        "notify_recovery" to Prefs.notifyRecovery(ctx), "event_sound" to Prefs.eventSound(ctx),
        "vibration_pattern" to Prefs.alarmPattern(ctx).name, "quiet_hours" to Prefs.quietHoursEnabled(ctx),
        "quiet_start_hour" to Prefs.quietStartHour(ctx), "quiet_end_hour" to Prefs.quietEndHour(ctx),
        "quiet_start_minute" to Prefs.quietStartMinute(ctx), "quiet_end_minute" to Prefs.quietEndMinute(ctx),
        "notify_outage" to Prefs.notifyFor(ctx, "TG_DOWN"), "notify_partial" to Prefs.notifyFor(ctx, "PARTIAL"),
        "sound_outage" to Prefs.soundFor(ctx, "TG_DOWN"), "sound_partial" to Prefs.soundFor(ctx, "PARTIAL"),
        "sound_recovery" to Prefs.soundFor(ctx, "RECOVERY")),
        History.snapshot(ctx), EventLog.all(ctx).toList())

    @Synchronized fun export(ctx: Context, password: CharArray): ByteArray {
        check(!hasPending(ctx)) { "Complete pending restore before export" }
        return PortableBackupCodec.encrypt(snapshot(ctx), password)
    }

    fun decrypt(bytes: ByteArray, password: CharArray): BackupSnapshot {
        return PortableBackupCodec.decrypt(bytes, password)
    }

    /** CSV changes history; capture current configuration/log after the service has fully stopped. */
    @Synchronized fun importHistory(ctx: Context, observations: List<Observation>) {
        check(Looper.myLooper() != Looper.getMainLooper())
        check(!hasPending(ctx))
        val validated = BackupCodec.decode(BackupCodec.encode(BackupSnapshot(0L, emptyMap(), observations, emptyList())))
        replacingHistory = true
        try {
            check(Prefs.sp(ctx).edit().putBoolean("enabled", false).commit())
            WatchdogReceiver.cancel(ctx)
            MonitorService.stopAndAwait(ctx)
            restore(ctx, snapshot(ctx).copy(observations = validated.observations))
        } finally { replacingHistory = false }
    }

    @Synchronized fun restore(ctx: Context, snapshot: BackupSnapshot) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Restore must run off the UI thread" }
        // Encode/decode before changing enabled/service/history: validates and detaches mutable input.
        val payload = BackupCodec.encode(snapshot)
        BackupCodec.decode(payload)
        check(!hasJournal(ctx)) { "Complete pending restore before replacing data" }
        try {
            RestoreTransaction(pending(ctx)).stage(payload)
            recoverPending(ctx)
        } finally { payload.fill(0) }
    }

    /** Called at process startup before any component, or by a background restore worker. */
    @Synchronized fun recoverPending(ctx: Context): Boolean {
        if (!hasPending(ctx)) return false
        check(Prefs.sp(ctx).edit().putBoolean("enabled", false).commit())
        WatchdogReceiver.cancel(ctx)
        MonitorService.stopAndAwait(ctx)
        RestoreTransaction(pending(ctx)).migrateLegacy(publishedPending(ctx),
            { bytes -> BackupCodec.encode(PortableBackupCodec.decodePublishedPayload(bytes)) },
            { bytes -> BackupCodec.decode(bytes) })
        return RestoreTransaction(pending(ctx)).recover { payload ->
            val restored = BackupCodec.decode(payload)
            History.replaceSnapshot(ctx, restored.observations)
            val edit = Prefs.sp(ctx).edit().putBoolean("enabled", false)
            (settingKeys + transientKeys).forEach { edit.remove(it) }
            restored.settings.forEach { (key, value) ->
                when (value) {
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is String -> edit.putString(key, value)
                    else -> error("Invalid setting")
                }
            }
            check(edit.commit()) { "Could not restore settings" }
            EventLog.replace(ctx, restored.log)
            LastSuccessStore.clear(ctx)
            MonitorService.clearRestoredState()
            val preferencesDirectory = File(ctx.applicationContext.createDeviceProtectedStorageContext()
                .dataDir, "shared_prefs")
            val descriptor = Os.open(preferencesDirectory.absolutePath, OsConstants.O_RDONLY, 0)
            try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
            StatusWidget.updateAll(ctx)
        }
    }
}
