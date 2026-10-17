package ru.tgwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
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
        const val SUMMARY_ID = 5
        const val SUMMARY_CHANNEL = "daily_summary_v1"
        const val SLOW_ID = 8
        const val SLOW_CHANNEL = "events_slow_v1"
        private const val CHECK_NOW_REQUEST = 6
        fun channel(event: String) = when(event) {
            "TG_DOWN" -> SOUND_CHANNEL; "PARTIAL" -> PARTIAL_CHANNEL; "RECOVERY" -> RECOVERY_CHANNEL
            else -> error("Unknown event")
        }
    }
    init {
        manager.createNotificationChannel(NotificationChannel(SILENT_CHANNEL, Lang.t("События без звука", "Silent events"),
            NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        manager.createNotificationChannel(NotificationChannel(outageChannel, Lang.t("События со звуком", "Events with sound"),
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
            enableVibration(false)
        })
        manager.createNotificationChannel(NotificationChannel(SUMMARY_CHANNEL, Lang.t("Сводка за день", "Daily summary"),
            NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        manager.createNotificationChannel(NotificationChannel(SLOW_CHANNEL, Lang.t("Telegram тормозит", "Telegram is slow"),
            NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        val legacy = manager.getNotificationChannel(outageChannel)
        manager.createNotificationChannel(NotificationChannel(partialChannel,Lang.t("Частичная доступность", "Partially reachable"),
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
            enableVibration(false)
        })
        val recoveryName = Lang.t("Восстановление связи", "Connection restored")
        val existingRecovery = manager.getNotificationChannel(recoveryChannel)
        if (existingRecovery == null) {
            manager.createNotificationChannel(NotificationChannel(recoveryChannel,recoveryName,
                legacy.importance).apply { setSound(legacy.sound,legacy.audioAttributes); enableVibration(false) })
        } else if (existingRecovery.name?.toString() != recoveryName) {
            // Only the visible name follows the UI language; importance and sound stay the user's.
            manager.createNotificationChannel(NotificationChannel(recoveryChannel,recoveryName,existingRecovery.importance))
        }
    }
    fun clearRecovery() { manager.cancel(RECOVERY_ID) }
    fun clearOutage() { manager.cancel(OUTAGE_ID); manager.cancel(PARTIAL_ID) }
    fun recovered(event: RecoveryEvent, sound: Boolean, allowed: Boolean) {
        clearOutage()
        if (blocked(recoveryChannel)) { clearRecovery(); return }
        manager.notify(RECOVERY_ID, notification(Lang.t("Telegram снова доступен", "Telegram is back"),
            Lang.t("Наблюдаемый сбой длился ${recoveryDurationStr(event.durationMs)}", "The observed outage lasted ${recoveryDurationStr(event.durationMs)}"), sound && allowed, R.drawable.ic_stat_ok,recoveryChannel))
    }
    fun outage(reason: String, sound: Boolean, allowed: Boolean) {
        clearRecovery()
        manager.cancel(PARTIAL_ID)
        if (blocked(outageChannel)) { manager.cancel(OUTAGE_ID); return }
        manager.notify(OUTAGE_ID, notification(Lang.t("Telegram недоступен", "Telegram is unreachable"), reason, sound && allowed, R.drawable.ic_stat_fail,outageChannel))
    }
    fun partial(reason: String, sound: Boolean, allowed: Boolean) {
        clearRecovery(); manager.cancel(OUTAGE_ID)
        if (blocked(partialChannel)) { manager.cancel(PARTIAL_ID); return }
        manager.notify(PARTIAL_ID,notification(Lang.t("Telegram частично доступен", "Telegram is partially reachable"),reason,sound && allowed,
            R.drawable.ic_stat_offline,partialChannel))
    }
    /** Silent: Telegram works, only slowly. Outage alerts replace it. */
    fun slow(latencyMs: Long) {
        if (blocked(SLOW_CHANNEL)) { clearSlow(); return }
        manager.notify(SLOW_ID, notification(Lang.t("Telegram тормозит", "Telegram is slow"),
            Lang.t("Серверы Telegram отвечают за $latencyMs мс несколько проверок подряд. " +
                "Сообщения и медиа могут грузиться медленно.",
                "Telegram servers have taken $latencyMs ms for several checks in a row. " +
                "Messages and media may load slowly."), true, R.drawable.ic_stat_wait, SLOW_CHANNEL))
    }
    fun clearSlow() { manager.cancel(SLOW_ID) }
    fun summary(title: String, text: String, canPost: () -> Boolean = { true }): Boolean {
        if (!manager.areNotificationsEnabled() || blocked(SUMMARY_CHANNEL)) {
            manager.cancel(SUMMARY_ID)
            return false
        }
        val prepared = notification(title, text, true, R.drawable.ic_stat_ok, SUMMARY_CHANNEL)
        // Notification/channel/PendingIntent preparation can block; recheck immediately before admission.
        return if (canPost()) { manager.notify(SUMMARY_ID, prepared); true } else false
    }
    private fun blocked(channel: String) = manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE
    private fun notification(title: String, text: String, audible: Boolean, icon: Int, channel: String): Notification {
        val open = PendingIntent.getActivity(ctx, 4, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(ctx, if (audible) channel else SILENT_CHANNEL)
            .setSmallIcon(icon).setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true)
            .setContentIntent(open).setCategory(Notification.CATEGORY_STATUS)
        if (channel == outageChannel || channel == partialChannel) {
            // Problem alerts only. Same check-now path as the ongoing status notification's "Check" action.
            val checkNow = PendingIntent.getForegroundService(ctx, CHECK_NOW_REQUEST,
                Intent(ctx, MonitorService::class.java).setAction(MonitorService.ACTION_CHECK_NOW),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat_ok),
                Lang.t("Проверить сейчас", "Check now"), checkNow).build())
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat_pause),
                Lang.t("Заглушить на час", "Mute for 1 hour"), AlertActionReceiver.mutePending(ctx)).build())
        }
        // Suppress child alerts even if the user later changes this channel's sound.
        if (!audible || channel == SUMMARY_CHANNEL || channel == SLOW_CHANNEL) builder.setGroup("tgwatch_silent_events")
            .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        return builder.build()
    }
}
