package ru.tgwatch

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** One process-local presenter shared by Telegram and secondary-target EventNotifications. */
internal class IncidentAlertCoordinator private constructor(private val ctx: Context,
    private val manager: NotificationManager) {
    private val policy = IncidentGrouping()

    @Synchronized fun outage(id: TargetId, at: Long, audible: Boolean,
        sourceChannel: String = EventNotifications.SOUND_CHANNEL, individual: () -> Unit) {
        val channel = if (audible) sourceChannel else EventNotifications.SILENT_CHANNEL
        // Keep individual presentation unless its replacement channel can actually deliver.
        val decision = policy.outage(id, at, IncidentAlertPreferences.enabled(ctx) && canPost(channel))
        if (decision.cancelGroup) manager.cancel(GROUP_ID)
        when {
            decision.group != null && postGroup(decision.group, channel) -> {
                decision.cancelIndividuals.forEach {
                    manager.cancel(TargetEventNotifications.id(it, "outage"))
                    manager.cancel(TargetEventNotifications.id(it, "partial"))
                }
            }
            else -> {
                if (decision.group != null) policy.reset()
                individual()
            }
        }
    }

    @Synchronized fun clear(id: TargetId) = apply(policy.clear(id))

    @Synchronized fun disable() {
        policy.reset()
        manager.cancel(GROUP_ID)
    }

    private fun apply(update: IncidentGrouping.Update) {
        if (update.cancelGroup) manager.cancel(GROUP_ID)
        update.group?.let { postGroup(it, EventNotifications.SILENT_CHANNEL) }
    }

    private fun canPost(channel: String): Boolean = manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(channel)?.let { it.importance != NotificationManager.IMPORTANCE_NONE } == true

    private fun postGroup(ids: List<TargetId>, channel: String): Boolean {
        if (!canPost(channel)) {
            manager.cancel(GROUP_ID)
            return false
        }
        val labels = ids.joinToString { TargetNames.label(ctx, it) }
        val text = ctx.getString(R.string.incident_alert_group_text, ids.size, labels)
        val open = PendingIntent.getActivity(ctx, GROUP_ID, Intent(ctx, IncidentCenterActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_fail)
            .setContentTitle(ctx.getString(R.string.incident_alert_group_title))
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS)
            .setOnlyAlertOnce(true).build()
        manager.notify(GROUP_ID, notification)
        return true
    }

    companion object {
        const val GROUP_ID = 9
        @Volatile private var shared: IncidentAlertCoordinator? = null

        fun get(ctx: Context, manager: NotificationManager): IncidentAlertCoordinator {
            shared?.let { return it }
            return synchronized(this) {
                shared ?: IncidentAlertCoordinator(ctx.applicationContext, manager).also {
                    if (!IncidentAlertPreferences.enabled(ctx)) manager.cancel(GROUP_ID)
                    shared = it
                }
            }
        }

        fun disable(ctx: Context) {
            shared?.disable() ?: (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(GROUP_ID)
        }

        @Synchronized internal fun resetForTests() { shared = null }
    }
}
