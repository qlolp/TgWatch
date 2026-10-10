package ru.tgwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager

/** Event notifications are separate from the silent, ongoing foreground-service status. */
class EventNotifications(private val ctx: Context, private val manager: NotificationManager) {
    companion object {
        const val SILENT_CHANNEL = "events_silent_v1"
        const val SOUND_CHANNEL = "events_sound_v1"
        const val OUTAGE_ID = 2
        const val RECOVERY_ID = 3
    }
    init {
        manager.createNotificationChannel(NotificationChannel(SILENT_CHANNEL, "События без звука",
            NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        manager.createNotificationChannel(NotificationChannel(SOUND_CHANNEL, "События со звуком",
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
            enableVibration(false)
        })
    }
    fun clearRecovery() { manager.cancel(RECOVERY_ID) }
    fun clearOutage() { manager.cancel(OUTAGE_ID) }
    fun recovered(event: RecoveryEvent, sound: Boolean, allowed: Boolean) {
        manager.cancel(OUTAGE_ID)
        manager.notify(RECOVERY_ID, notification("Telegram снова доступен",
            "Наблюдаемый сбой длился ${recoveryDurationStr(event.durationMs)}", sound && allowed, R.drawable.ic_stat_ok))
    }
    fun outage(reason: String, sound: Boolean, allowed: Boolean) {
        clearRecovery()
        manager.notify(OUTAGE_ID, notification("Telegram недоступен", reason, sound && allowed, R.drawable.ic_stat_fail))
    }
    private fun notification(title: String, text: String, audible: Boolean, icon: Int): Notification {
        val open = PendingIntent.getActivity(ctx, 4, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(ctx, if (audible) SOUND_CHANNEL else SILENT_CHANNEL)
            .setSmallIcon(icon).setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true)
            .setContentIntent(open).setCategory(Notification.CATEGORY_STATUS)
        // Suppress child alerts even if the user later changes this channel's sound.
        if (!audible) builder.setGroup("tgwatch_silent_events")
            .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        return builder.build()
    }
}
