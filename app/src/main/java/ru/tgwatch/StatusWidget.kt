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

    override fun onEnabled(ctx: Context) {
        super.onEnabled(ctx)
        if (Prefs.isEnabled(ctx) && !MonitorService.running) {
            try {
                MonitorService.start(ctx)
            } catch (_: Exception) {
            }
        }
        WatchdogReceiver.schedule(ctx)
        updateAll(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_CLICK) {
            handleClick(ctx)
            return
        }
        super.onReceive(ctx, intent)
    }

    companion object {
        const val ACTION_CLICK = "ru.tgwatch.action.WIDGET_CLICK"

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

        private fun handleClick(ctx: Context) {
            if (Prefs.isEnabled(ctx)) {
                try {
                    MonitorService.send(ctx, MonitorService.ACTION_CHECK_NOW)
                } catch (_: Exception) {
                    try {
                        MonitorService.start(ctx)
                    } catch (_: Exception) {
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
            val stale = s.isStale(now, Prefs.intervalSec(ctx), Prefs.keepAwake(ctx))

            val bg: Int
            val icon: Int
            val title: String
            val subtitle: String
            when {
                !running -> {
                    bg = R.drawable.bg_widget_idle
                    icon = R.drawable.ic_stat_pause
                    title = "Мониторинг выключен"
                    subtitle = "Нажми, чтобы открыть"
                }
                s.status == MonitorService.Status.UNKNOWN -> {
                    bg = R.drawable.bg_widget_idle
                    icon = R.drawable.ic_stat_wait
                    title = if (s.checkedAt > 0) "Нет свежего подтверждения" else "Проверяю…"
                    subtitle = "Telegram"
                }
                stale -> {
                    bg = R.drawable.bg_widget_idle
                    icon = R.drawable.ic_stat_wait
                    title = when (s.status) {
                        MonitorService.Status.OK -> "Был доступен"
                        MonitorService.Status.TG_DOWN -> "Был недоступен"
                        MonitorService.Status.PARTIAL -> "Был частично доступен"
                        else -> "Не было интернета"
                    }
                    subtitle = "Проверено ${agoStr(s.checkedAt, now)} · нажми"
                }
                s.status == MonitorService.Status.PARTIAL -> {
                    bg = R.drawable.bg_widget_offline
                    icon = R.drawable.ic_stat_wait
                    title = "Частично доступен"
                    subtitle = "Нажми проверить"
                }
                s.status == MonitorService.Status.OK -> {
                    bg = R.drawable.bg_widget_ok
                    icon = R.drawable.ic_stat_ok
                    title = "Telegram доступен"
                    subtitle = "${s.latencyMs} мс · нажми проверить"
                }
                s.status == MonitorService.Status.TG_DOWN -> {
                    bg = R.drawable.bg_widget_fail
                    icon = R.drawable.ic_stat_fail
                    title = "Telegram недоступен"
                    subtitle = "С ${timeStr(s.since)} · нажми"
                }
                else -> {
                    bg = R.drawable.bg_widget_offline
                    icon = R.drawable.ic_stat_offline
                    title = "Нет интернета"
                    subtitle = "С ${timeStr(s.since)} · нажми"
                }
            }

            val click = PendingIntent.getBroadcast(
                ctx, 10,
                Intent(ctx, StatusWidget::class.java).setAction(ACTION_CLICK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return RemoteViews(ctx.packageName, R.layout.widget_status).apply {
                setInt(R.id.widgetRoot, "setBackgroundResource", bg)
                setImageViewResource(R.id.widgetIcon, icon)
                setTextViewText(R.id.widgetTitle, title)
                setTextViewText(R.id.widgetSubtitle, subtitle)
                setOnClickPendingIntent(R.id.widgetRoot, click)
            }
        }
    }
}

