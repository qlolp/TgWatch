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
        val interval = preferences.getInt(key(id, "interval"),
            if (telegram) Prefs.intervalSec(ctx) else defaults.intervalSec)
            .coerceIn(if (telegram) TargetDefinition.MIN_TELEGRAM_SEC else TargetDefinition.MIN_TARGET_SEC,
                if (telegram) TargetDefinition.MAX_TELEGRAM_SEC else TargetDefinition.MAX_TARGET_SEC)
        return TargetDefinition(id, preferences.getBoolean(key(id, "enabled"), telegram && Prefs.isEnabled(ctx)),
            interval, preferences.getBoolean(key(id, "requires_endpoint"), defaults.requiresEndpoint),
            TargetAlertSettings(preferences.getBoolean(key(id, "notify_outage"),
                if (telegram) Prefs.notifyFor(ctx, "TG_DOWN") else defaults.alerts.notifyOutage),
                preferences.getBoolean(key(id, "notify_recovery"),
                    if (telegram) Prefs.notifyRecovery(ctx) else defaults.alerts.notifyRecovery),
                preferences.getBoolean(key(id, "notify_slow"),
                    if (telegram) Prefs.notifyFor(ctx, "SLOW") else defaults.alerts.notifySlow)))
    }

    fun endpoint(ctx: Context, id: TargetId): String? {
        val app = ctx.applicationContext
        if (!app.getSystemService(UserManager::class.java).isUserUnlocked) return null
        val raw = app.getSharedPreferences(PRIVATE_FILE, Context.MODE_PRIVATE).getString(id.wire, null)
        return HttpsUrlPolicy.normalize(raw, MAX_ENDPOINT_CHARS, allowQuery = false)
    }

    internal fun key(id: TargetId, suffix: String) = "target.${id.wire}.$suffix"
}
