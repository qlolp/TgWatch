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

internal data class OngoingContent(val title: String, val text: String, val channel: String,
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

    fun createChannels(manager: NotificationManager) {
        val ok = NotificationChannel(CHANNEL_OK, Lang.t("Telegram доступен", "Telegram is reachable"),
            NotificationManager.IMPORTANCE_LOW).apply {
            description = Lang.t("Постоянный значок, когда Telegram отвечает",
                "Persistent icon while Telegram is responding")
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val alert = NotificationChannel(CHANNEL_ALERT, Lang.t("Telegram недоступен", "Telegram is unreachable"),
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = Lang.t("Тревожный значок, когда связи нет", "Alert icon when there is no connection")
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
        val uptimeText = if (uptime >= 0) " · Telegram ${formatPercent(uptime)}" else ""
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
                state.checkedAtMs <= 0L -> Lang.t("ожидание проверки", "awaiting check")
                stale -> Lang.t("устарело", "stale")
                else -> when (state.status) {
                    TargetStatus.OK -> Lang.t("доступен", "reachable")
                    TargetStatus.DOWN -> Lang.t("недоступен", "unreachable")
                    TargetStatus.PARTIAL -> Lang.t("частично доступен", "partially reachable")
                    TargetStatus.NO_NETWORK -> Lang.t("нет интернета", "no internet")
                    TargetStatus.UNKNOWN -> Lang.t("не определено", "unknown")
                }
            }
            "${TargetNames.label(state.id)}: $label"
        }
        val title = if (states.isEmpty()) Lang.t("Мониторинг", "Monitoring")
            else states.joinToString(" · ") { TargetNames.label(it.id) }
        return OngoingView(OngoingContent(title, lines.joinToString("\n"), CHANNEL_OK,
            R.drawable.ic_stat_wait, COLOR_OK, false), states.maxOfOrNull { it.checkedAtMs }
                ?.takeIf { it > 0L } ?: now, false, null, selected)
    }

    private fun content(state: State, now: Long, uptime: String, stale: Boolean): OngoingContent = when {
        state.status == Status.UNKNOWN -> OngoingContent(
            if (state.checkedAt > 0) Lang.t("Доступность не определена", "Reachability unknown")
            else Lang.t("Проверяю связь с Telegram…", "Checking the connection to Telegram…"),
            state.reason.ifEmpty { MonitorService.CHECK_URL }, CHANNEL_OK, R.drawable.ic_stat_wait, COLOR_OK, false)
        stale -> OngoingContent(Lang.t("Мониторинг задерживается", "Monitoring is delayed"),
            Lang.t("Последняя проверка ${agoStr(state.checkedAt, now)}; нужен свежий результат",
                "Last check ${agoStr(state.checkedAt, now)}; a fresh result is needed"),
            CHANNEL_OK, R.drawable.ic_stat_wait, COLOR_STALE, false)
        state.status == Status.PARTIAL -> OngoingContent(
            Lang.t("Telegram частично доступен", "Telegram is partially reachable"), state.reason,
            CHANNEL_OK, R.drawable.ic_stat_wait, COLOR_OFFLINE, false)
        state.status == Status.OK -> OngoingContent(
            Lang.t("Telegram отвечает · ${state.latencyMs} мс", "Telegram is responding · ${state.latencyMs} ms"),
            Lang.t("Проверено в ${timeStr(state.checkedAt)}$uptime", "Checked at ${timeStr(state.checkedAt)}$uptime"),
            CHANNEL_OK, R.drawable.ic_stat_ok, COLOR_OK, false)
        state.status == Status.TG_DOWN -> OngoingContent(Lang.t("Telegram НЕДОСТУПЕН", "Telegram is UNREACHABLE"),
            Lang.t("Нет связи с ${timeStr(state.since)} · ${state.reason}",
                "No connection since ${timeStr(state.since)} · ${state.reason}"),
            CHANNEL_ALERT, R.drawable.ic_stat_fail_blink, COLOR_FAIL, true)
        else -> OngoingContent(Lang.t("Нет подключения к интернету", "No internet connection"),
            Lang.t("С ${timeStr(state.since)} · ${state.reason}", "Since ${timeStr(state.since)} · ${state.reason}"),
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
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(Notification.BigTextStyle().bigText(content.text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(view.whenMs)
            .setUsesChronometer(view.chronometer)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp)
            .setDeleteIntent(check)
            .addAction(action(ctx, R.drawable.ic_stat_ok, Lang.t("Проверить", "Check"), check))
            .addAction(action(ctx, R.drawable.ic_stat_pause, Lang.t("Остановить", "Stop"), stop))
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
