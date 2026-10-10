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
class EventNotifications(private val ctx: Context, private val manager: NotificationManager,
    private val outageChannel: String = SOUND_CHANNEL, private val partialChannel: String = PARTIAL_CHANNEL,
    private val recoveryChannel: String = RECOVERY_CHANNEL) {
    companion object {
        const val SILENT_CHANNEL = "events_silent_v1"
        const val SOUND_CHANNEL = "events_sound_v1"
        const val PARTIAL_CHANNEL = "events_partial_sound_v1"
        const val RECOVERY_CHANNEL = "events_recovery_sound_v1"
        const val OUTAGE_ID = 2
        const val RECOVERY_ID = 3
        const val PARTIAL_ID = 4
        fun channel(event: String) = when(event) {
            "TG_DOWN" -> SOUND_CHANNEL; "PARTIAL" -> PARTIAL_CHANNEL; "RECOVERY" -> RECOVERY_CHANNEL
            else -> error("Unknown event")
        }
    }
    init {
        manager.createNotificationChannel(NotificationChannel(SILENT_CHANNEL, "События без звука",
            NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        manager.createNotificationChannel(NotificationChannel(outageChannel, "События со звуком",
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
            enableVibration(false)
        })
        val legacy = manager.getNotificationChannel(outageChannel)
        manager.createNotificationChannel(NotificationChannel(partialChannel,"Частичная доступность",
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
            enableVibration(false)
        })
        if (manager.getNotificationChannel(recoveryChannel) == null)
            manager.createNotificationChannel(NotificationChannel(recoveryChannel,"Восстановление связи",
                legacy.importance).apply { setSound(legacy.sound,legacy.audioAttributes); enableVibration(false) })
    }
    fun clearRecovery() { manager.cancel(RECOVERY_ID) }
    fun clearOutage() { manager.cancel(OUTAGE_ID); manager.cancel(PARTIAL_ID) }
    fun recovered(event: RecoveryEvent, sound: Boolean, allowed: Boolean) {
        clearOutage()
        if (blocked(recoveryChannel)) { clearRecovery(); return }
        manager.notify(RECOVERY_ID, notification("Telegram снова доступен",
            "Наблюдаемый сбой длился ${recoveryDurationStr(event.durationMs)}", sound && allowed, R.drawable.ic_stat_ok,recoveryChannel))
    }
    fun outage(reason: String, sound: Boolean, allowed: Boolean) {
        clearRecovery()
        manager.cancel(PARTIAL_ID)
        if (blocked(outageChannel)) { manager.cancel(OUTAGE_ID); return }
        manager.notify(OUTAGE_ID, notification("Telegram недоступен", reason, sound && allowed, R.drawable.ic_stat_fail,outageChannel))
    }
    fun partial(reason: String, sound: Boolean, allowed: Boolean) {
        clearRecovery(); manager.cancel(OUTAGE_ID)
        if (blocked(partialChannel)) { manager.cancel(PARTIAL_ID); return }
        manager.notify(PARTIAL_ID,notification("Telegram частично доступен",reason,sound && allowed,
            R.drawable.ic_stat_offline,partialChannel))
    }
    private fun blocked(channel: String) = manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE
    private fun notification(title: String, text: String, audible: Boolean, icon: Int, channel: String): Notification {
        val open = PendingIntent.getActivity(ctx, 4, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(ctx, if (audible) channel else SILENT_CHANNEL)
            .setSmallIcon(icon).setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true)
            .setContentIntent(open).setCategory(Notification.CATEGORY_STATUS)
        // Suppress child alerts even if the user later changes this channel's sound.
        if (!audible) builder.setGroup("tgwatch_silent_events")
            .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        return builder.build()
    }
}
