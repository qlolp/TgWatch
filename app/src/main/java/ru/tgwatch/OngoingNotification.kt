package ru.tgwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import ru.tgwatch.MonitorService.State
import ru.tgwatch.MonitorService.Status

internal data class OngoingContent(val title: TextValue, val text: TextValue, val channel: String,
    val icon: Int, val color: Int, val colorized: Boolean)
internal data class OngoingView(val notif: OngoingContent, val whenMs: Long,
    val chronometer: Boolean, val progress: Int?, val targetId: TargetId = TargetId.TELEGRAM)

/** Builds from immutable cached inputs; never reads history or owns service lifecycle. */
internal object OngoingNotification {
    private const val CHANNEL_OK = "status_ok_v2"
    private const val CHANNEL_ALERT = "status_alert_v2"
    private const val COLOR_OK = -14_508_327 // #229ED9
    private const val COLOR_FAIL = -1_754_827 // #E53935
    private const val COLOR_OFFLINE = -689_152 // #F57C00
    private const val COLOR_STALE = -7_694_685 // #8A96A3

    fun createChannels(ctx: Context, manager: NotificationManager) {
        val ok = NotificationChannel(CHANNEL_OK, AndroidText.resolve(ctx,
            textOf(TextKey.COPY_ONGOING_NOTIFICATION_TELEGRAM_IS_REACHABLE)),
            NotificationManager.IMPORTANCE_LOW).apply {
            description = AndroidText.resolve(ctx,
                textOf(TextKey.COPY_ONGOING_NOTIFICATION_PERSISTENT_ICON_WHILE_TELEGRAM))
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val alert = NotificationChannel(CHANNEL_ALERT, AndroidText.resolve(ctx,
            textOf(TextKey.COPY_ONGOING_NOTIFICATION_TELEGRAM_IS_UNREACHABLE)),
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = AndroidText.resolve(ctx, textOf(TextKey.COPY_ONGOING_NOTIFICATION_ALERT_ICON_WHEN_THERE))
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(ok)
        manager.createNotificationChannel(alert)
        runCatching { manager.deleteNotificationChannel("status_v1") }
    }

    fun view(state: State, stats: TimeStats, now: Long): OngoingView {
        val stale = state.isStale(now)
        val uptime = stats.uptimePercent
        val uptimeText = if (uptime >= 0) textLiteral(" · Telegram ") + textOf(TextKey.PERCENT,
            TextArgument.Decimal(uptime)) else textLiteral("")
        return OngoingView(content(state, now, uptimeText, stale),
            whenMs = if (state.bad && !stale) state.since else if (state.checkedAt > 0L) state.checkedAt else now,
            chronometer = state.bad && !stale,
            progress = NotificationProgress.percent(stats))
    }

    /** Each row names its own goal; no combined uptime or invented outage start is displayed. */
    fun targets(states: List<TargetSnapshot>, now: Long, elapsed: Long): OngoingView {
        val selected = states.firstOrNull()?.id ?: TargetId.TELEGRAM
        val lines = states.map { state ->
            val stale = if (state.checkedElapsedMs > 0L)
                ProbeRules.isStale(state.checkedElapsedMs, elapsed, state.expectedIntervalSec)
                else ProbeRules.isStale(state.checkedAtMs, now, state.expectedIntervalSec)
            val label = when {
                state.checkedAtMs <= 0L -> textOf(TextKey.COPY_ONGOING_NOTIFICATION_AWAITING_CHECK)
                stale -> textOf(TextKey.COPY_ONGOING_NOTIFICATION_STALE)
                else -> when (state.status) {
                    TargetStatus.OK -> textOf(TextKey.COPY_ONGOING_NOTIFICATION_REACHABLE)
                    TargetStatus.DOWN -> textOf(TextKey.COPY_ONGOING_NOTIFICATION_UNREACHABLE)
                    TargetStatus.PARTIAL -> textOf(TextKey.COPY_ONGOING_NOTIFICATION_PARTIALLY_REACHABLE)
                    TargetStatus.NO_NETWORK -> textOf(TextKey.COPY_ONGOING_NOTIFICATION_NO_INTERNET)
                    TargetStatus.UNKNOWN -> textOf(TextKey.COPY_ONGOING_NOTIFICATION_UNKNOWN)
                }
            }
            TargetNames.text(state.id) + ": " + label
        }
        val title = if (states.isEmpty()) textOf(TextKey.COPY_ONGOING_NOTIFICATION_MONITORING)
            else textJoin(states.map { TargetNames.text(it.id) }, " · ")
        return OngoingView(OngoingContent(title, textLines(lines), CHANNEL_OK,
            R.drawable.ic_stat_wait, COLOR_OK, false), states.maxOfOrNull { it.checkedAtMs }
                ?.takeIf { it > 0L } ?: now, false, null, selected)
    }

    private fun content(state: State, now: Long, uptime: TextValue, stale: Boolean): OngoingContent = when {
        state.status == Status.UNKNOWN -> OngoingContent(
            if (state.checkedAt > 0) textOf(TextKey.COPY_ONGOING_NOTIFICATION_REACHABILITY_UNKNOWN)
            else textOf(TextKey.COPY_ONGOING_NOTIFICATION_CHECKING_THE_CONNECTION_TO),
            textLiteral(state.reason.ifEmpty { MonitorService.CHECK_URL }), CHANNEL_OK, R.drawable.ic_stat_wait,
                COLOR_OK, false)
        stale -> OngoingContent(textOf(TextKey.COPY_ONGOING_NOTIFICATION_MONITORING_IS_DELAYED),
            textOf(TextKey.COPY_ONGOING_NOTIFICATION_LAST_CHECK_A_FRESH, textArgument(ageText(state.checkedAt, now))),
            CHANNEL_OK, R.drawable.ic_stat_wait, COLOR_STALE, false)
        state.status == Status.PARTIAL -> OngoingContent(
            textOf(TextKey.COPY_ONGOING_NOTIFICATION_TELEGRAM_IS_PARTIALLY_REACHABLE), textLiteral(state.reason),
            CHANNEL_OK, R.drawable.ic_stat_wait, COLOR_OFFLINE, false)
        state.status == Status.OK -> OngoingContent(
            textOf(TextKey.COPY_ONGOING_NOTIFICATION_TELEGRAM_IS_RESPONDING_MS, textArgument(state.latencyMs)),
            textOf(TextKey.COPY_ONGOING_NOTIFICATION_CHECKED_AT, textArgument(timeText(state.checkedAt)),
                textArgument(uptime)),
            CHANNEL_OK, R.drawable.ic_stat_ok, COLOR_OK, false)
        state.status == Status.TG_DOWN -> OngoingContent(
            textOf(TextKey.COPY_ONGOING_NOTIFICATION_TELEGRAM_IS_UNREACHABLE_F3AB95),
            textOf(TextKey.COPY_ONGOING_NOTIFICATION_NO_CONNECTION_SINCE, textArgument(timeText(state.since)),
                textArgument(state.reason)),
            CHANNEL_ALERT, R.drawable.ic_stat_fail_blink, COLOR_FAIL, true)
        else -> OngoingContent(textOf(TextKey.COPY_ONGOING_NOTIFICATION_NO_INTERNET_CONNECTION),
            textOf(TextKey.COPY_ONGOING_NOTIFICATION_SINCE, textArgument(timeText(state.since)),
                textArgument(state.reason)),
            CHANNEL_ALERT, R.drawable.ic_stat_offline_blink, COLOR_OFFLINE, true)
    }

    fun build(ctx: Context, view: OngoingView, servicePending: (Int, String) -> PendingIntent): Notification {
        val openApp = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .setData(android.net.Uri.parse("tgwatch://ongoing/${view.targetId.wire}"))
                .putExtra(TargetAutomation.EXTRA_TARGET_ID, view.targetId.wire)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun pending(request: Int, action: String): PendingIntent {
            if (view.targetId == TargetId.TELEGRAM) return servicePending(request, action)
            val intent = Intent(ctx, MonitorService::class.java).setAction(action)
                .setData(android.net.Uri.parse("tgwatch://ongoing/${view.targetId.wire}/$action"))
                .putExtra(TargetAutomation.EXTRA_TARGET_ID, view.targetId.wire)
            return PendingIntent.getForegroundService(ctx, request, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val check = pending(CHECK_REQUEST, MonitorService.ACTION_CHECK_NOW)
        val stop = pending(STOP_REQUEST, MonitorService.ACTION_STOP)
        val content = view.notif
        val builder = Notification.Builder(ctx, content.channel)
            .setSmallIcon(content.icon)
            .setColor(content.color)
            .setColorized(content.colorized)
            .setContentTitle(AndroidText.resolve(ctx, content.title))
            .setContentText(AndroidText.resolve(ctx, content.text))
            .setStyle(Notification.BigTextStyle().bigText(AndroidText.resolve(ctx, content.text)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(view.whenMs)
            .setUsesChronometer(view.chronometer)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp)
            .setDeleteIntent(check)
            .addAction(action(ctx, R.drawable.ic_stat_ok, AndroidText.resolve(ctx,
                textOf(TextKey.COPY_ONGOING_NOTIFICATION_CHECK)), check))
            .addAction(action(ctx, R.drawable.ic_stat_pause, AndroidText.resolve(ctx,
                textOf(TextKey.COPY_ONGOING_NOTIFICATION_STOP)), stop))
        view.progress?.let { builder.setProgress(NotificationProgress.MAX_PERCENT, it, false) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return builder.build()
    }

    private fun action(ctx: Context, icon: Int, label: String, pending: PendingIntent) =
        Notification.Action.Builder(Icon.createWithResource(ctx, icon), label, pending).build()

    private const val CHECK_REQUEST = 1
    private const val STOP_REQUEST = 2
}
