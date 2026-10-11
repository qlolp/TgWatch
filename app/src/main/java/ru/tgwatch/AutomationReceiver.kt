package ru.tgwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Exported automation entry for Tasker / Termux / Shortcuts.
 * Callers must hold [PERMISSION] (normal protection — install-time grant).
 */
class AutomationReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent?) {
        Lang.update(ctx)
        when (intent?.action) {
            ACTION_EXTERNAL_CHECK_NOW -> {
                Prefs.setEnabled(ctx, true)
                try {
                    MonitorService.send(ctx, MonitorService.ACTION_CHECK_NOW)
                } catch (e: Exception) {
                    Log.w(TAG, "external check", e)
                }
            }
            ACTION_EXTERNAL_START -> {
                Prefs.setEnabled(ctx, true)
                try {
                    MonitorService.start(ctx)
                } catch (e: Exception) {
                    Log.w(TAG, "external start", e)
                }
            }
            ACTION_EXTERNAL_STOP -> {
                Prefs.setEnabled(ctx, false)
                MonitorService.stop(ctx)
            }
            ACTION_EXTERNAL_QUERY -> {
                MonitorService.restorePersistedState(ctx)
                ctx.sendBroadcast(MonitorService.stateIntent(ctx), PERMISSION)
            }
            else -> Unit
        }
    }

    companion object {
        private const val TAG = "TgWatch"
        const val PERMISSION = "ru.tgwatch.permission.AUTOMATION"
        const val ACTION_EXTERNAL_CHECK_NOW = "ru.tgwatch.action.EXTERNAL_CHECK_NOW"
        const val ACTION_EXTERNAL_START = "ru.tgwatch.action.EXTERNAL_START"
        const val ACTION_EXTERNAL_STOP = "ru.tgwatch.action.EXTERNAL_STOP"
        const val ACTION_EXTERNAL_QUERY = "ru.tgwatch.action.EXTERNAL_QUERY"
    }
}
