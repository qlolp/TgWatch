package ru.tgwatch

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews

/** Виджет на рабочем столе: цветная плашка «Telegram доступен · 120 мс». */
class StatusWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        MonitorService.restorePersistedState(ctx)
        val views = build(ctx)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    @Suppress("TooGenericExceptionCaught") // A denied foreground start is not fatal here.
    override fun onEnabled(ctx: Context) {
        super.onEnabled(ctx)
        if (Prefs.isEnabled(ctx) && !MonitorService.running) {
            try {
                MonitorService.start(ctx)
            } catch (e: Exception) {
                Log.w("TgWatch", "widget start: " + e.javaClass.simpleName)
            }
        }
        WatchdogReceiver.schedule(ctx)
        updateAll(ctx)
    }

    companion object {
        /** Перерисовать все виджеты. Дёшево: если виджетов нет, ничего не делает. */
        fun updateAll(ctx: Context) {
            try {
                val manager = AppWidgetManager.getInstance(ctx) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(ctx, StatusWidget::class.java))
                if (ids.isEmpty()) return
                manager.updateAppWidget(ids, build(ctx))
            } catch (e: Exception) {
                Log.w("TgWatch", "widget", e)
            }
        }

        @Suppress("TooGenericExceptionCaught") // Best-effort start; failures are logged.
        internal fun handleClick(ctx: Context) {
            if (Prefs.isEnabled(ctx)) {
                try {
                    MonitorService.send(ctx, MonitorService.ACTION_CHECK_NOW)
                } catch (e: Exception) {
                    Log.w("TgWatch", "widget check: " + e.javaClass.simpleName)
                    try {
                        MonitorService.start(ctx)
                    } catch (startFailure: Exception) {
                        Log.w("TgWatch", "widget start: " + startFailure.javaClass.simpleName)
                        openApp(ctx)
                    }
                }
                updateAll(ctx)
            } else {
                openApp(ctx)
            }
        }

        private fun openApp(ctx: Context) {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }

        private fun build(ctx: Context): RemoteViews {
            val s = MonitorService.state
            val now = System.currentTimeMillis()
            val running = MonitorService.running || Prefs.isEnabled(ctx)
            val stale = s.isStale(now)

            val bg: Int
            val icon: Int
            val title: String
            val subtitle: String
            when {
                !running -> {
                    bg = R.drawable.bg_widget_idle
                    icon = R.drawable.ic_stat_pause
                    title = Lang.t("Мониторинг выключен", "Monitoring is off")
                    subtitle = Lang.t("Нажми, чтобы открыть", "Tap to open")
                }
                s.status == MonitorService.Status.UNKNOWN -> {
                    bg = R.drawable.bg_widget_idle
                    icon = R.drawable.ic_stat_wait
                    title = if (s.checkedAt > 0) Lang.t("Нет свежего подтверждения", "No fresh confirmation") else Lang.t("Проверяю…", "Checking…")
                    subtitle = "Telegram"
                }
                stale -> {
                    bg = R.drawable.bg_widget_idle
                    icon = R.drawable.ic_stat_wait
                    title = when (s.status) {
                        MonitorService.Status.OK -> Lang.t("Был доступен", "Was reachable")
                        MonitorService.Status.TG_DOWN -> Lang.t("Был недоступен", "Was unreachable")
                        MonitorService.Status.PARTIAL -> Lang.t("Был частично доступен", "Was partially reachable")
                        else -> Lang.t("Не было интернета", "Had no internet")
                    }
                    subtitle = if (now < s.checkedAt) Lang.t("Часы изменились · проверка ${timeStr(s.checkedAt)}", "Clock changed · checked ${timeStr(s.checkedAt)}")
                        else Lang.t("Проверено ${agoStr(s.checkedAt, now)} · нажми", "Checked ${agoStr(s.checkedAt, now)} · tap")
                }
                s.status == MonitorService.Status.PARTIAL -> {
                    bg = R.drawable.bg_widget_offline
                    icon = R.drawable.ic_stat_wait
                    title = Lang.t("Частично доступен", "Partially reachable")
                    subtitle = Lang.t("Нажми проверить", "Tap to check")
                }
                s.status == MonitorService.Status.OK -> {
                    bg = R.drawable.bg_widget_ok
                    icon = R.drawable.ic_stat_ok
                    title = Lang.t("Telegram доступен", "Telegram is reachable")
                    subtitle = Lang.t("${s.latencyMs} мс · нажми проверить", "${s.latencyMs} ms · tap to check")
                }
                s.status == MonitorService.Status.TG_DOWN -> {
                    bg = R.drawable.bg_widget_fail
                    icon = R.drawable.ic_stat_fail
                    title = Lang.t("Telegram недоступен", "Telegram is unreachable")
                    subtitle = Lang.t("С ${timeStr(s.since)} · нажми", "Since ${timeStr(s.since)} · tap")
                }
                else -> {
                    bg = R.drawable.bg_widget_offline
                    icon = R.drawable.ic_stat_offline
                    title = Lang.t("Нет интернета", "No internet")
                    subtitle = Lang.t("С ${timeStr(s.since)} · нажми", "Since ${timeStr(s.since)} · tap")
                }
            }

            val click = PendingIntent.getBroadcast(
                ctx, 10,
                Intent(ctx, WidgetClickReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return RemoteViews(ctx.packageName, R.layout.widget_status).apply {
                setInt(R.id.widgetRoot, "setBackgroundResource", bg)
                setImageViewResource(R.id.widgetIcon, icon)
                setContentDescription(R.id.widgetIcon, title)
                setTextViewText(R.id.widgetTitle, title)
                setTextViewText(R.id.widgetSubtitle, subtitle)
                setTextViewText(R.id.widgetLastSuccess,
                    LastSuccessPolicy.label(LastSuccessStore.lastSuccessAt(ctx), now))
                setOnClickPendingIntent(R.id.widgetRoot, click)
            }
        }
    }
}

