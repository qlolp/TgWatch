package ru.tgwatch

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper

/** Serial disk operations never hold the short publication/configuration monitor. */
internal object TargetSettingWrites {
    private const val MAX_ENDPOINT_CHARS = 256
    fun definition(ctx: Context, value: TargetDefinition): Boolean = change(ctx, value.id) {
        val before = TargetPreferences.storedDefinition(ctx, value.id)
        require(value.requiresEndpoint == before.requiresEndpoint) { "Use the endpoint transaction" }
        synchronized(TargetPreferences) {
            val current = if (TargetPreferences.fence.snapshot(value.id).revision == it.revision) value
                else value.copy(enabled = false)
            val edit = Prefs.sp(ctx).edit()
            write(edit, current)
            if (current.enabled) edit.putBoolean("enabled", true)
            edit.apply()
        }
        if (!Prefs.sp(ctx).edit().commit()) {
            synchronized(TargetPreferences) {
                val rollback = if (TargetPreferences.fence.snapshot(value.id).revision == it.revision) before
                    else before.copy(enabled = false)
                write(Prefs.sp(ctx).edit(), rollback).apply()
            }
            error("Cannot save target settings")
        }
    }

    fun endpoint(ctx: Context, id: TargetId, raw: String): Boolean {
        val normalized = HttpsUrlPolicy.normalize(raw, MAX_ENDPOINT_CHARS, allowQuery = false)
        val valid = id in setOf(TargetId.MATRIX, TargetId.CUSTOM) && (raw.isBlank() || normalized != null)
        if (!valid) return false
        return change(ctx, id) { TargetConfigTransactions.change(ctx, id, normalized) }
    }

    private fun change(ctx: Context, id: TargetId, operation: (TargetSettingsFence.Token) -> Unit): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper())
        val token = synchronized(TargetPreferences) {
            if (BackupManager.hasPending() || TargetConfigTransactions.isPending(id)) null
            else TargetPreferences.fence.begin(id)
        } ?: return false
        AutomationReceiver.invalidateState()
        return try {
            HistorySessions.queue(ctx).exclusive { operation(token) }.get()
            true
        } catch (_: Exception) { false }
        finally { TargetPreferences.fence.finish(token); AutomationReceiver.invalidateState() }
    }

    internal fun write(edit: SharedPreferences.Editor, value: TargetDefinition): SharedPreferences.Editor {
        fun key(suffix: String) = TargetPreferences.key(value.id, suffix)
        if (value.id == TargetId.TELEGRAM) edit.putInt("interval_sec", value.intervalSec)
            .putString("power_profile", "CUSTOM").putBoolean("notify_outage", value.alerts.notifyOutage)
            .putBoolean("notify_recovery", value.alerts.notifyRecovery)
            .putBoolean("notify_slow", value.alerts.notifySlow)
        return edit.putBoolean(key("enabled"), value.enabled).putInt(key("interval"), value.intervalSec)
            .putBoolean(key("requires_endpoint"), value.requiresEndpoint)
            .putBoolean(key("notify_outage"), value.alerts.notifyOutage)
            .putBoolean(key("notify_recovery"), value.alerts.notifyRecovery)
            .putBoolean(key("notify_slow"), value.alerts.notifySlow)
            .putLong(key("slow_threshold"), value.alerts.slowThresholdMs)
    }
}
