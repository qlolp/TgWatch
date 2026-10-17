package ru.tgwatch

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * "Mute for 1 hour" on alert notifications. Not exported: only the alert's own
 * PendingIntent reaches it. The mute goes through Prefs.alertsAllowed, like quiet hours.
 */
class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_MUTE) return
        muteNow(context)
    }

    companion object {
        private const val ACTION_MUTE = "ru.tgwatch.action.MUTE_ALERTS"
        private const val REQUEST_CODE = 42

        /** Mutes alerts for an hour (alert action and app-icon shortcut); returns the end time, or null. */
        fun muteNow(context: Context): String? {
            val now = System.currentTimeMillis()
            if (!Prefs.muteAlerts(context, now)) {
                EventLog.add(context, Lang.t("Не удалось сохранить заглушение оповещений", "Could not save alert mute"))
                return null
            }
            // The alert has been handled; the ongoing status notification still shows the outage.
            context.getSystemService(NotificationManager::class.java)?.let {
                it.cancel(EventNotifications.OUTAGE_ID)
                it.cancel(EventNotifications.PARTIAL_ID)
                it.cancel(EventNotifications.SLOW_ID)
            }
            val until = timeStr(AlertMute.until(now))
            EventLog.add(context, Lang.t("Оповещения заглушены до $until", "Alerts muted until $until"))
            return until
        }

        fun mutePending(ctx: Context): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, REQUEST_CODE,
                Intent(ctx, AlertActionReceiver::class.java).setAction(ACTION_MUTE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }
}
