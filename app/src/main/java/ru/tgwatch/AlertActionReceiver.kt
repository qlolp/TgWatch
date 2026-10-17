package ru.tgwatch

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * "Mute for 1 hour" on alert notifications. Not exported: only the alert's own
 * PendingIntent reaches it. The mute goes through Prefs.alertsAllowed, like quiet hours.
 */
class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val raw = intent?.getStringExtra(TargetAutomation.EXTRA_TARGET_ID)
        val id = if (raw == null) TargetId.TELEGRAM else TargetId.fromWire(raw) ?: return
        when (intent?.action) {
            ACTION_CHECK -> TargetActions.check(context, id)
            ACTION_MUTE -> {
                val pending = goAsync()
                val completion = ComponentCompletion({ pending.finish() })
                val app = context.applicationContext
                val accepted = TargetSettingsExecutor.background(id,
                    { completion.current && muteNow(app, id) != null }, { completion.finish() })
                if (!accepted) completion.finish()
            }
        }
    }

    companion object {
        private const val ACTION_MUTE = "ru.tgwatch.action.MUTE_ALERTS"
        private const val ACTION_CHECK = "ru.tgwatch.action.CHECK_TARGET_ALERT"
        private const val REQUEST_CODE = 42

        /** Mutes alerts for an hour (alert action and app-icon shortcut); returns the end time, or null. */
        fun muteNow(context: Context, id: TargetId = TargetId.TELEGRAM): String? {
            val now = System.currentTimeMillis()
            if (!TargetAlertPreferences.mute(context, id)) {
                EventLog.add(context, Lang.t("Не удалось сохранить заглушение оповещений", "Could not save alert mute"))
                return null
            }
            // The alert has been handled; the ongoing status notification still shows the outage.
            context.getSystemService(NotificationManager::class.java)?.let {
                it.cancel(TargetEventNotifications.id(id, "outage"))
                it.cancel(TargetEventNotifications.id(id, "partial"))
                it.cancel(TargetEventNotifications.id(id, "slow"))
            }
            val until = timeStr(AlertMute.until(now))
            EventLog.add(context, TargetNames.label(id) + ": " +
                Lang.t("Оповещения заглушены до $until", "Alerts muted until $until"))
            return until
        }

        fun mutePending(ctx: Context, id: TargetId = TargetId.TELEGRAM): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, REQUEST_CODE,
                intent(ctx, id, ACTION_MUTE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        fun checkPending(ctx: Context, id: TargetId): PendingIntent = PendingIntent.getBroadcast(ctx,
            TargetEventNotifications.id(id, "outage"), intent(ctx, id, ACTION_CHECK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun intent(ctx: Context, id: TargetId, action: String) =
            Intent(ctx, AlertActionReceiver::class.java).setAction(action)
                .setData(Uri.parse("tgwatch://alert/${id.wire}/${action.substringAfterLast('.')}"))
                .putExtra(TargetAutomation.EXTRA_TARGET_ID, id.wire)
    }
}
