package ru.tgwatch

import android.content.Context
import android.os.UserManager

/** Separates public target flags from optional credential endpoint addresses. Legacy EXTRA is not opt-in. */
object TargetPreferences {
    internal const val PRIVATE_FILE = "targets-private-v1"
    val fence = TargetSettingsFence()

    fun definitions(ctx: Context): List<TargetDefinition> = TargetId.entries.map { definition(ctx, it) }

    fun definition(ctx: Context, id: TargetId): TargetDefinition = storedDefinition(ctx, id).let {
        it.copy(enabled = it.enabled && Prefs.isEnabled(ctx) && !fence.snapshot(id).changing &&
            !TargetConfigTransactions.isPending(id))
    }

    internal fun storedDefinition(ctx: Context, id: TargetId): TargetDefinition {
        val defaults = TargetDefinition.default(id)
        val preferences = Prefs.sp(ctx)
        val telegram = id == TargetId.TELEGRAM
        val interval = (if (telegram) Prefs.intervalSec(ctx) else
            preferences.getInt(key(id, "interval"), defaults.intervalSec))
            .coerceIn(if (telegram) TargetDefinition.MIN_TELEGRAM_SEC else TargetDefinition.MIN_TARGET_SEC,
                if (telegram) TargetDefinition.MAX_TELEGRAM_SEC else TargetDefinition.MAX_TARGET_SEC)
        val enabled = preferences.getBoolean(key(id, "enabled"), telegram) && TargetSlots.present(ctx, id)
        return TargetDefinition(id, enabled,
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
        return configuration(ctx, id)?.url
    }

    internal fun storedEndpoint(ctx: Context, id: TargetId): String? = readPrivateProbeConfig(ctx, id)?.url

    fun endpointRevision(ctx: Context, id: TargetId): Long =
        Prefs.sp(ctx).getLong(key(id, "endpoint_revision"), 0L).coerceAtLeast(0L)

    fun setDefinition(ctx: Context, definition: TargetDefinition): Boolean =
        TargetSettingWrites.definition(ctx, definition)
    fun setEndpoint(ctx: Context, id: TargetId, raw: String): Boolean = TargetSettingWrites.endpoint(ctx, id, raw)
    fun stop(ctx: Context, id: TargetId) {
        synchronized(this) {
            fence.bump(id)
            Prefs.sp(ctx).edit().putBoolean(key(id, "enabled"), false).apply()
        }
        AutomationReceiver.invalidateState()
        WebhookNotifier.forget(id)
    }

    internal fun key(id: TargetId, suffix: String) = "target.${id.wire}.$suffix"
}

/** Worker-only convenience; alert persistence stays with the alert settings adapter. */
fun TargetPreferences.setSlowThreshold(ctx: Context, id: TargetId, thresholdMs: Long): Boolean =
    TargetAlertPreferences.setSlowThreshold(ctx, id, thresholdMs)

internal fun TargetPreferences.configuration(ctx: Context, id: TargetId): PrivateProbeConfig? {
    val changing = fence.snapshot(id).changing || TargetConfigTransactions.isPending(id)
    return if (changing) null else readPrivateProbeConfig(ctx, id)
}

private fun readPrivateProbeConfig(ctx: Context, id: TargetId): PrivateProbeConfig? {
    val app = ctx.applicationContext
    if (!app.getSystemService(UserManager::class.java).isUserUnlocked ||
        Prefs.sp(ctx).getBoolean(TargetPreferences.key(id, "endpoint_review"), false)) return null
    val raw = app.getSharedPreferences(TargetPreferences.PRIVATE_FILE, Context.MODE_PRIVATE).getString(id.wire, null)
    return raw?.let { runCatching { PrivateProbeConfigCodec.decode(it) }.getOrNull() }
        ?.takeIf { id.isCustom || it.condition == null }
}
