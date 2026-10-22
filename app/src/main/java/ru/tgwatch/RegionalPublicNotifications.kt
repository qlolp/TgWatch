package ru.tgwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.SystemClock

internal class RegionalPublicNotifications(private val ctx: Context, private val channel: String = CHANNEL) {
    private val manager = ctx.getSystemService(NotificationManager::class.java)
    init {
        manager.createNotificationChannel(NotificationChannel(channel, ctx.getString(R.string.regional_watch_channel),
            NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun post(result: RegionalResult, request: RegionalWatchMetadata): Boolean {
        if (result.status != RegionalStatus.WEB_REACHABLE ||
            !RegionalNotificationPolicy.allowed(ctx, channel, System.currentTimeMillis())) return false
        val open = PendingIntent.getActivity(ctx, id(result.service), Intent(ctx, RegionalServicesActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val message = ctx.getString(R.string.regional_watch_message)
        val previous = manager.activeNotifications.firstOrNull { it.id == id(result.service) }
        if (previous != null && previous.notification.extras.getLong(REQUEST_ARMED, -1) != request.request.armedElapsed)
            manager.cancel(id(result.service))
        val notification = Notification.Builder(ctx, channel)
            .setSmallIcon(Icon.createWithResource(ctx, R.drawable.ic_stat_ok))
            .setContentTitle(ctx.getString(R.string.regional_watch_title, result.service.title))
            .setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
            .addExtras(Bundle().apply { putLong(REQUEST_ARMED, request.request.armedElapsed) })
            .setContentIntent(open).setOnlyAlertOnce(true).setAutoCancel(true).build()
        return runCatching {
            if (!current(result, request)) false else { manager.notify(id(result.service), notification); true }
        }.getOrDefault(false)
    }
    private fun current(result: RegionalResult, request: RegionalWatchMetadata): Boolean {
        val now = SystemClock.elapsedRealtime()
        val time = result.checkedElapsed > request.request.armedElapsed && result.checkedElapsed <= now &&
            now - result.checkedElapsed <= RegionalMonitor.SLEEP_MS * 2
        val identity = result.networkHandle ==
            ctx.getSystemService(ConnectivityManager::class.java).activeNetwork?.networkHandle &&
            result.configurationRevision == RegionalPreferences.revision(ctx)
        val armed = Prefs.isEnabled(ctx) && RegionalWatchPreferences.current(ctx, result.service) == request
        return time && identity && armed
    }
    fun clear(service: RegionalService) { manager.cancel(id(service)) }
    private fun id(service: RegionalService) = BASE_ID + service.ordinal
    companion object {
        const val CHANNEL = "regional_public_response_v1"
        const val REQUEST_ARMED = "regional_watch_armed"
        private const val BASE_ID = 900
    }
}
