package ru.tgwatch

import android.app.NotificationManager
import android.content.Context

internal object RegionalNotificationPolicy {
    fun allowed(ctx: Context, channel: String, now: Long): Boolean {
        val manager = ctx.getSystemService(NotificationManager::class.java)
        val system = manager.areNotificationsEnabled() && manager.getNotificationChannel(channel)?.let {
            it.importance != NotificationManager.IMPORTANCE_NONE
        } == true && manager.currentInterruptionFilter in setOf(NotificationManager.INTERRUPTION_FILTER_ALL,
            NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
        val time = !Prefs.inQuietHoursNow(ctx, now) && Prefs.alertMuteRemaining(ctx, now) <= 0 &&
            !MaintenanceWindows.active(MaintenanceWindows.load(ctx, RegionalPreferences.MAINTENANCE), now)
        return system && time
    }
}
