package ru.tgwatch

import android.content.Context
import android.os.Looper

/** Portable data crosses this boundary only after full schema/authentication validation. */
// Keep legacy and target entry points on the same restore monitor; their storage implementations are separate.
@Suppress("TooManyFunctions")
object BackupManager {
    @Volatile private var replacingHistory = false
    private val settingKeys = setOf("power_profile", "interval_sec", "keep_awake", "vibrate",
        "vibrate_partial", "vibrate_offline", "vibrate_recovery", "notify_recovery", "event_sound",
        "vibration_pattern", "quiet_hours", "quiet_start_hour", "quiet_end_hour",
        "quiet_start_minute", "quiet_end_minute", "notify_outage", "notify_partial",
        "sound_outage", "sound_partial", "sound_recovery", "daily_summary", "daily_summary_minute",
        "extra_probe_matrix", "extra_probe_whatsapp", "extra_probe_custom", "extra_probe_custom_url", "notify_slow")
    private val transientKeys = setOf("last_status", "last_checked", "last_since", "last_latency",
        "last_reason", "last_vibe", "last_diagnostics", "last_expected_sec", "last_success_at")

    fun hasPending(): Boolean = replacingHistory || BackupJournals.pending ||
        RecoveryCoordinator.state != RecoveryState.READY

    /** One `now` stamps the copy and clips the predicted coverage tail it must not carry. */
    @Synchronized fun snapshot(ctx: Context, now: Long = System.currentTimeMillis()): BackupSnapshot =
        BackupSnapshot(now, linkedMapOf(
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
        "sound_recovery" to Prefs.soundFor(ctx, "RECOVERY"),
        "daily_summary" to Prefs.dailySummary(ctx), "daily_summary_minute" to Prefs.dailySummaryMinute(ctx),
        "extra_probe_matrix" to Prefs.extraMatrix(ctx), "extra_probe_whatsapp" to Prefs.extraWhatsapp(ctx),
        "extra_probe_custom" to Prefs.extraCustom(ctx), "extra_probe_custom_url" to Prefs.extraCustomUrl(ctx),
        "notify_slow" to Prefs.notifyFor(ctx, "SLOW")),
        History.snapshot(ctx, now), EventLog.all(ctx).toList())

    @Synchronized fun export(ctx: Context, password: CharArray): ByteArray {
        check(!hasPending() && !TargetConfigTransactions.anyPending) { "Complete pending changes before export" }
        return PortableBackupCodec.encryptTargets(snapshotTargets(ctx), password)
    }

    @Synchronized fun exportTelegram(ctx: Context, password: CharArray): ByteArray {
        check(!hasPending() && !TargetConfigTransactions.anyPending)
        val data = snapshot(ctx)
        return PortableBackupCodec.encrypt(data.copy(settings = data.settings - TargetBackupCodec.LEGACY_ENDPOINT_KEY),
            password)
    }

    fun decrypt(bytes: ByteArray, password: CharArray): BackupSnapshot {
        val imported = PortableBackupCodec.decryptTargetImport(bytes, password)
        val data = imported.snapshot
        val revision = data.observations[TargetId.TELEGRAM].orEmpty().maxOfOrNull { it.endpointRevision } ?: 0L
        return BackupSnapshot(data.createdAtMs, data.globalSettings,
            data.observations[TargetId.TELEGRAM].orEmpty().filter { it.endpointRevision == revision }.map {
                if (it.row.kind == "DOWN") it.row.copy(kind = "TG_DOWN") else it.row
            }, data.log)
    }

    @Synchronized fun snapshotTargets(ctx: Context, now: Long = System.currentTimeMillis()): TargetBackupSnapshot =
        TargetArchive.snapshot(ctx, now)

    /** Legacy CSV always selects Telegram; the v3 path changes only explicitly named histories. */
    @Synchronized fun importHistory(ctx: Context, observations: List<Observation>) {
        val validated = BackupCodec.decode(BackupCodec.encode(BackupSnapshot(0L, emptyMap(), observations, emptyList())))
        importHistories(ctx, mapOf(TargetId.TELEGRAM to validated.observations.map {
            TargetObservation(TargetPersistence.publicRow(it),
                TargetPreferences.endpointRevision(ctx, TargetId.TELEGRAM))
        }))
    }

    @Synchronized fun importHistories(ctx: Context, observations: Map<TargetId, List<TargetObservation>>) {
        check(Looper.myLooper() != Looper.getMainLooper())
        check(!hasPending() && !TargetConfigTransactions.anyPending)
        require(observations.isNotEmpty())
        TargetBackupCodec.validate(TargetBackupSnapshot(0, emptyMap(),
            observations.keys.map(TargetDefinition::default), observations, emptyList()))
        replacingHistory = true
        try {
            check(Prefs.sp(ctx).edit().putBoolean("enabled", false).commit())
            WatchdogReceiver.cancel(ctx)
            MonitorService.stopAndAwait(ctx)
            val current = snapshotTargets(ctx)
            val changed = current.copy(observations = current.observations + observations)
            TargetRestore.stage(ctx, TargetBackupImport(changed,
                metadata = PrivateRestoreMetadata(observations.keys)))
            recoverPending(ctx)
        } finally { replacingHistory = false }
    }

    @Synchronized fun restore(ctx: Context, snapshot: BackupSnapshot) {
        val validated = BackupCodec.decode(BackupCodec.encode(snapshot))
        TargetRestore.stage(ctx, TargetPortableBackup.legacy(validated))
        recoverPending(ctx)
    }

    @Synchronized fun restoreTargets(ctx: Context, snapshot: TargetBackupSnapshot) {
        val bytes = TargetBackupCodec.encode(snapshot)
        val validated = try { TargetBackupCodec.decode(bytes) } finally { bytes.fill(0) }
        TargetRestore.stage(ctx, TargetBackupImport(validated))
        recoverPending(ctx)
    }

    /** Disk inspection and private migration happen only on recovery/restore workers. */
    @Synchronized fun recoverPending(ctx: Context): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper())
        BackupJournals.refresh(ctx)
        if (!BackupJournals.pending) {
            if (RecoveryCoordinator.state == RecoveryState.FAILED) RecoveryCoordinator.start(ctx)
            return false
        }
        TargetRestore.requireUnlocked(ctx)
        replacingHistory = true
        try {
            check(Prefs.sp(ctx).edit().putBoolean("enabled", false).commit())
            WatchdogReceiver.cancel(ctx)
            MonitorService.stopAndAwait(ctx)
            WebhookNotifier.reset()
            AutomationReceiver.invalidateState()
            var recovered = false
            HistorySessions.queue(ctx).exclusive {
                TargetPersistence.initialize(ctx, forRestore = true)
                BackupJournals.migrateLegacy(ctx)
                recovered = BackupJournals.transaction(ctx).recoverWithMetadata { bytes, endpoint, metadata ->
                    TargetRestore.apply(ctx, TargetBackupImport(TargetBackupCodec.decode(bytes), endpoint, metadata),
                        settingKeys + transientKeys)
                }
            }.get()
            // Requests admitted while the pending gate was closed cannot outlive the completed replacement.
            AutomationReceiver.invalidateState()
            WebhookNotifier.reset()
            BackupJournals.refresh(ctx)
            TargetConfigTransactions.resume(ctx)
            if (RecoveryCoordinator.state == RecoveryState.FAILED) RecoveryCoordinator.start(ctx)
            return recovered
        } finally { replacingHistory = false }
    }
}
