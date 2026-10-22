package ru.tgwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

/** One configurable channel; only confirmed explicit service evidence may enter it. */
internal class RegionalNotifications(private val ctx: Context, private val channel: String = CHANNEL) {
    private val manager = ctx.getSystemService(NotificationManager::class.java)
    init {
        manager.createNotificationChannel(NotificationChannel(channel, ctx.getString(R.string.regional_alert_channel),
            NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun post(result: RegionalResult): Boolean {
        val now = System.currentTimeMillis()
        if (result.status != RegionalStatus.REGION_RESTRICTED ||
            !RegionalNotificationPolicy.allowed(ctx, channel, now)) return false
        val open = PendingIntent.getActivity(ctx, BASE_ID, Intent(ctx, RegionalServicesActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val message = ctx.getString(R.string.regional_alert_message, result.service.title)
        val notification = Notification.Builder(ctx, channel)
            .setSmallIcon(Icon.createWithResource(ctx, R.drawable.ic_stat_wait))
            .setContentTitle(ctx.getString(R.string.regional_alert_title, result.service.title))
            .setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build()
        return runCatching { manager.notify(id(result.service), notification); true }.getOrDefault(false)
    }

    fun clear(service: RegionalService) { manager.cancel(id(service)) }
    fun clearAll() { RegionalService.entries.forEach(::clear) }
    private fun id(service: RegionalService) = BASE_ID + service.ordinal
    companion object {
        const val CHANNEL = "regional_restrictions_v1"
        private const val BASE_ID = 700
    }
}
