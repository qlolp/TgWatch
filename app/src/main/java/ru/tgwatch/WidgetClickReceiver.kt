package ru.tgwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Taps on the widget. Not exported: only the widget's own PendingIntent reaches it,
 * so other apps cannot start checks through the exported widget provider.
 */
class WidgetClickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val data = intent?.dataString
        if (data == null) { StatusWidget.handleClick(context); return }
        val identity = WidgetIdentity.parse(data) ?: return
        if (identity.action == "check") StatusWidget.handleClick(context, identity)
        else mute(context, identity)
    }
    private fun mute(context: Context, identity: WidgetIdentity) {
        if (!StatusWidget.current(context, identity)) return
        if (!TargetPreferences.definition(context, identity.targetId).enabled || !MonitorService.running) {
            StatusWidget.open(context, identity.targetId)
            return
        }
        val pending = goAsync()
        val completion = ComponentCompletion({ pending.finish() })
        val app = context.applicationContext
        val accepted = TargetSettingsExecutor.background(identity.targetId, {
            completion.current && StatusWidget.current(app, identity) &&
                AlertActionReceiver.muteNow(app, identity.targetId) != null
        }, { saved ->
            if (saved && completion.current) Handler(Looper.getMainLooper()).post {
                Toast.makeText(app, R.string.widget_muted, Toast.LENGTH_SHORT).show()
            }
            StatusWidget.updateAll(app)
            completion.finish()
        })
        if (!accepted) completion.finish()
    }
}
