package ru.tgwatch

import android.app.Activity
import android.content.Context
import android.widget.Toast

/** User actions select exactly one goal. The automation receiver only uses the separate check-existing path. */
internal object TargetActions {
    fun start(activity: Activity, id: TargetId) {
        val app = activity.applicationContext
        val revision = TargetPreferences.fence.snapshot(id).revision
        val definition = TargetPreferences.storedDefinition(app, id).copy(enabled = true)
        submit(activity, id) {
            TargetSettingWrites.definition(app, definition, revision).also { if (it) reconcile(app, id) }
        }
    }

    fun stop(ctx: Context, id: TargetId) { TargetPreferences.stop(ctx, id); reconcile(ctx, id) }
    fun check(ctx: Context, id: TargetId): Boolean = MonitorService.requestRunningCheck(ctx, id)

    fun reconcile(ctx: Context, changedId: TargetId = TargetId.TELEGRAM) {
        when {
            !Prefs.isEnabled(ctx) || TargetId.entries.none { TargetPreferences.storedDefinition(ctx, it).enabled } -> {
                Prefs.setEnabled(ctx, false)
                MonitorService.stop(ctx)
            }
            MonitorService.running -> MonitorService.send(ctx, MonitorService.ACTION_SETTINGS, changedId)
            else -> runCatching { MonitorService.start(ctx) }.onFailure {
                if (it is Error) throw it
                WatchdogReceiver.schedule(ctx)
            }
        }
        StatusWidget.updateAll(ctx)
    }

    fun submit(activity: Activity, id: TargetId, operation: () -> Boolean) {
        if (!TargetSettingsExecutor.submit(activity, id, operation))
            Toast.makeText(activity, R.string.target_settings_busy, Toast.LENGTH_SHORT).show()
    }
}
