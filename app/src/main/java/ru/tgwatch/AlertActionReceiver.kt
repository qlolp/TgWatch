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
        val now = System.currentTimeMillis()
        Prefs.muteAlerts(context, now)
        // The tapped alert has been handled; the ongoing status notification still shows the outage.
        context.getSystemService(NotificationManager::class.java)?.let {
            it.cancel(EventNotifications.OUTAGE_ID)
            it.cancel(EventNotifications.PARTIAL_ID)
        }
        val until = timeStr(AlertMute.until(now))
        EventLog.add(context, Lang.t("Оповещения заглушены до $until", "Alerts muted until $until"))
    }

    companion object {
        private const val ACTION_MUTE = "ru.tgwatch.action.MUTE_ALERTS"
        private const val REQUEST_CODE = 42

        fun mutePending(ctx: Context): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, REQUEST_CODE,
                Intent(ctx, AlertActionReceiver::class.java).setAction(ACTION_MUTE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }
}
