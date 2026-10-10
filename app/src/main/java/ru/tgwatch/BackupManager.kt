package ru.tgwatch

import android.content.Context
import android.os.Looper
import java.io.File

/** Portable data crosses this boundary only after full schema/authentication validation. */
object BackupManager {
    private val settingKeys = setOf("power_profile", "interval_sec", "keep_awake", "vibrate",
        "vibrate_partial", "vibrate_offline", "vibrate_recovery", "notify_recovery", "event_sound",
        "vibration_pattern", "quiet_hours", "quiet_start_hour", "quiet_end_hour",
        "quiet_start_minute", "quiet_end_minute")
    private val transientKeys = setOf("last_status", "last_checked", "last_since", "last_latency",
        "last_reason", "last_vibe", "last_diagnostics", "last_expected_sec", "last_success_at")

    private fun pending(ctx: Context) = File(ctx.applicationContext.createDeviceProtectedStorageContext()
        .filesDir, "restore-pending-v1.json")

    fun hasPending(ctx: Context): Boolean = pending(ctx).exists()

    @Synchronized fun snapshot(ctx: Context): BackupSnapshot = BackupSnapshot(System.currentTimeMillis(), linkedMapOf(
        "power_profile" to (Prefs.profile(ctx)?.name ?: "CUSTOM"),
        "interval_sec" to Prefs.intervalSec(ctx), "keep_awake" to Prefs.keepAwake(ctx),
        "vibrate" to Prefs.vibrateEnabled(ctx), "vibrate_partial" to Prefs.vibratePartial(ctx),
        "vibrate_offline" to Prefs.vibrateOffline(ctx), "vibrate_recovery" to Prefs.vibrateOnRecovery(ctx),
        "notify_recovery" to Prefs.notifyRecovery(ctx), "event_sound" to Prefs.eventSound(ctx),
        "vibration_pattern" to Prefs.alarmPattern(ctx).name, "quiet_hours" to Prefs.quietHoursEnabled(ctx),
        "quiet_start_hour" to Prefs.quietStartHour(ctx), "quiet_end_hour" to Prefs.quietEndHour(ctx),
        "quiet_start_minute" to Prefs.quietStartMinute(ctx), "quiet_end_minute" to Prefs.quietEndMinute(ctx)),
        History.snapshot(ctx), EventLog.all(ctx).toList())

    @Synchronized fun export(ctx: Context, password: CharArray): ByteArray {
        check(!hasPending(ctx)) { "Complete pending restore before export" }
        val bytes = BackupCodec.encode(snapshot(ctx))
        return try { BackupCrypto.encrypt(bytes, password) } finally { bytes.fill(0) }
    }

    fun decrypt(bytes: ByteArray, password: CharArray): BackupSnapshot {
        val plaintext = BackupCrypto.decrypt(bytes, password)
        return try { BackupCodec.decode(plaintext) } finally { plaintext.fill(0) }
    }

    /** CSV changes history; capture current configuration/log after the service has fully stopped. */
    @Synchronized fun importHistory(ctx: Context, observations: List<Observation>) {
        check(Looper.myLooper() != Looper.getMainLooper())
        check(!hasPending(ctx))
        val validated = BackupCodec.decode(BackupCodec.encode(BackupSnapshot(0L, emptyMap(), observations, emptyList())))
        check(Prefs.sp(ctx).edit().putBoolean("enabled", false).commit())
        WatchdogReceiver.cancel(ctx)
        MonitorService.stopAndAwait(ctx)
        restore(ctx, snapshot(ctx).copy(observations = validated.observations))
    }

    @Synchronized fun restore(ctx: Context, snapshot: BackupSnapshot) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Restore must run off the UI thread" }
        // Encode/decode before changing enabled/service/history: validates and detaches mutable input.
        val payload = BackupCodec.encode(snapshot)
        BackupCodec.decode(payload)
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
            StatusWidget.updateAll(ctx)
        }
    }
}
