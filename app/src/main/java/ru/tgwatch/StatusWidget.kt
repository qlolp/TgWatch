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

        private fun build(ctx: Context): RemoteViews {
            val s = MonitorService.state
            val now = System.currentTimeMillis()
            val running = MonitorService.running || Prefs.isEnabled(ctx)
            val stale = s.isStale(now, Prefs.intervalSec(ctx))

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
                    title = "Проверяю…"
                    subtitle = "Telegram"
                }
                stale -> {
                    bg = R.drawable.bg_widget_idle
                    icon = R.drawable.ic_stat_wait
                    title = when (s.status) {
                        MonitorService.Status.OK -> "Был доступен"
                        MonitorService.Status.TG_DOWN -> "Был недоступен"
                        else -> "Не было интернета"
                    }
                    subtitle = "Проверено ${agoStr(s.checkedAt, now)}"
                }
                s.status == MonitorService.Status.OK -> {
                    bg = R.drawable.bg_widget_ok
                    icon = R.drawable.ic_stat_ok
                    title = "Telegram доступен"
                    subtitle = "${s.latencyMs} мс · ${timeStr(s.checkedAt)}"
                }
                s.status == MonitorService.Status.TG_DOWN -> {
                    bg = R.drawable.bg_widget_fail
                    icon = R.drawable.ic_stat_fail
                    title = "Telegram недоступен"
                    subtitle = "С ${timeStr(s.since)}"
                }
                else -> {
                    bg = R.drawable.bg_widget_offline
                    icon = R.drawable.ic_stat_offline
                    title = "Нет интернета"
                    subtitle = "С ${timeStr(s.since)}"
                }
            }

            val open = PendingIntent.getActivity(
                ctx, 10,
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return RemoteViews(ctx.packageName, R.layout.widget_status).apply {
                setInt(R.id.widgetRoot, "setBackgroundResource", bg)
                setImageViewResource(R.id.widgetIcon, icon)
                setTextViewText(R.id.widgetTitle, title)
                setTextViewText(R.id.widgetSubtitle, subtitle)
                setOnClickPendingIntent(R.id.widgetRoot, open)
            }
        }
    }
}
