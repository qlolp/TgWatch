package ru.tgwatch

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper

/** Serial disk operations never hold the short publication/configuration monitor. */
internal object TargetSettingWrites {
    private const val MAX_ENDPOINT_CHARS = 256
    fun definition(ctx: Context, value: TargetDefinition, expectedRevision: Long? = null): Boolean =
        change(ctx, value.id, expectedRevision) { TargetDefinitionUpdate.apply(ctx, value, it) }

    fun endpoint(ctx: Context, id: TargetId, raw: String, expectedRevision: Long? = null): Boolean {
        val normalized = HttpsUrlPolicy.normalize(raw, MAX_ENDPOINT_CHARS, allowQuery = false)
        val valid = id in setOf(TargetId.MATRIX, TargetId.CUSTOM) && (raw.isBlank() || normalized != null)
        if (!valid) return false
        return change(ctx, id, expectedRevision) { TargetConfigTransactions.change(ctx, id, normalized) }
    }

    private fun change(ctx: Context, id: TargetId, expectedRevision: Long?,
        operation: (TargetSettingsFence.Token) -> Unit): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper())
        val token = synchronized(TargetPreferences) {
            val current = expectedRevision == null || TargetPreferences.fence.snapshot(id).revision == expectedRevision
            if (!current || BackupManager.hasPending() || TargetConfigTransactions.isPending(id)) null
            else TargetPreferences.fence.begin(id)
        } ?: return false
        AutomationReceiver.invalidateState()
        return try {
            HistorySessions.queue(ctx).exclusive { synchronized(History) { operation(token) } }.get()
            true
        } catch (_: Exception) { false }
        finally { TargetPreferences.fence.finish(token); AutomationReceiver.invalidateState() }
    }

    internal fun write(edit: SharedPreferences.Editor, value: TargetDefinition): SharedPreferences.Editor {
        fun key(suffix: String) = TargetPreferences.key(value.id, suffix)
        if (value.id == TargetId.TELEGRAM) edit.putInt("interval_sec", value.intervalSec)
            .putBoolean("notify_outage", value.alerts.notifyOutage)
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
