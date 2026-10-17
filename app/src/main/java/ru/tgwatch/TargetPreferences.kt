package ru.tgwatch

import android.content.Context
import android.os.UserManager

/** Separates public target flags from optional credential endpoint addresses. Legacy EXTRA is not opt-in. */
object TargetPreferences {
    internal const val PRIVATE_FILE = "targets-private-v1"
    private const val MAX_ENDPOINT_CHARS = 256

    fun definitions(ctx: Context): List<TargetDefinition> = TargetId.entries.map { definition(ctx, it) }

    fun definition(ctx: Context, id: TargetId): TargetDefinition {
        val defaults = TargetDefinition.default(id)
        val preferences = Prefs.sp(ctx)
        val telegram = id == TargetId.TELEGRAM
        val interval = (if (telegram) Prefs.intervalSec(ctx) else
            preferences.getInt(key(id, "interval"), defaults.intervalSec))
            .coerceIn(if (telegram) TargetDefinition.MIN_TELEGRAM_SEC else TargetDefinition.MIN_TARGET_SEC,
                if (telegram) TargetDefinition.MAX_TELEGRAM_SEC else TargetDefinition.MAX_TARGET_SEC)
        return TargetDefinition(id, Prefs.isEnabled(ctx) && preferences.getBoolean(key(id, "enabled"), telegram),
            interval, preferences.getBoolean(key(id, "requires_endpoint"), defaults.requiresEndpoint),
            TargetAlertSettings(if (telegram) Prefs.notifyFor(ctx, "TG_DOWN") else
                preferences.getBoolean(key(id, "notify_outage"), defaults.alerts.notifyOutage),
                if (telegram) Prefs.notifyRecovery(ctx) else
                    preferences.getBoolean(key(id, "notify_recovery"), defaults.alerts.notifyRecovery),
                if (telegram) Prefs.notifyFor(ctx, "SLOW") else
                    preferences.getBoolean(key(id, "notify_slow"), defaults.alerts.notifySlow),
                preferences.getLong(key(id, "slow_threshold"), defaults.alerts.slowThresholdMs)
                    .coerceIn(TargetAlertSettings.MIN_SLOW_MS, TargetAlertSettings.MAX_SLOW_MS)
                    .let { it / TargetAlertSettings.SLOW_STEP_MS * TargetAlertSettings.SLOW_STEP_MS }))
    }

    fun endpoint(ctx: Context, id: TargetId): String? {
        val app = ctx.applicationContext
        if (!app.getSystemService(UserManager::class.java).isUserUnlocked ||
            Prefs.sp(ctx).getBoolean(key(id, "endpoint_review"), false)) return null
        val raw = app.getSharedPreferences(PRIVATE_FILE, Context.MODE_PRIVATE).getString(id.wire, null)
        return HttpsUrlPolicy.normalize(raw, MAX_ENDPOINT_CHARS, allowQuery = false)
    }

    fun endpointRevision(ctx: Context, id: TargetId): Long =
        Prefs.sp(ctx).getLong(key(id, "endpoint_revision"), 0L).coerceAtLeast(0L)

    internal fun key(id: TargetId, suffix: String) = "target.${id.wire}.$suffix"
}
