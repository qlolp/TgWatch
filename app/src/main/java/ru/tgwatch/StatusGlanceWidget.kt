package ru.tgwatch

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.runBlocking

/** Declarative home-screen widget (Glance). Classic [StatusWidget] remains available. */
class StatusGlanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Lang.update(context)
        MonitorService.restorePersistedState(context)
        val s = MonitorService.state
        val now = System.currentTimeMillis()
        val running = MonitorService.running || Prefs.isEnabled(context)
        val stale = s.isStale(now, Prefs.intervalSec(context), Prefs.keepAwake(context))
        val (title, subtitle, bg) = glanceCopy(running, stale, s, now)
        provideContent {
            GlanceTheme {
                GlanceStatus(title, subtitle, bg)
            }
        }
    }

    companion object {
        fun updateAll(ctx: Context) {
            try {
                runBlocking { StatusGlanceWidget().updateAll(ctx) }
            } catch (_: Exception) {
            }
        }
    }
}

@Composable
private fun GlanceStatus(title: String, subtitle: String, bg: Color) {
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(bg))
            .padding(14.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = title,
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                text = subtitle,
                style = TextStyle(
                    color = ColorProvider(Color(0xE6FFFFFF)),
                    fontSize = 12.sp,
                ),
            )
        }
    }
}

private fun glanceCopy(
    running: Boolean,
    stale: Boolean,
    s: MonitorService.State,
    now: Long,
): Triple<String, String, Color> {
    val ok = Color(0xFF229ED9)
    val fail = Color(0xFFE53935)
    val offline = Color(0xFFF57C00)
    val idle = Color(0xFF8A96A3)
    return when {
        !running -> Triple(
            Lang.t("Мониторинг выключен", "Monitoring is off"),
            Lang.t("Нажми, чтобы открыть", "Tap to open"),
            idle,
        )
        stale -> Triple(
            Lang.t("Мониторинг задерживается", "Monitoring is delayed"),
            Lang.t("Проверено ${agoStr(s.checkedAt, now)}", "Checked ${agoStr(s.checkedAt, now)}"),
            idle,
        )
        s.status == MonitorService.Status.OK -> Triple(
            Lang.t("Telegram доступен", "Telegram available"),
            Lang.t("${s.latencyMs} мс", "${s.latencyMs} ms"),
            ok,
        )
        s.status == MonitorService.Status.PARTIAL -> Triple(
            Lang.t("Частично доступен", "Partially available"),
            s.reason.take(40).ifEmpty { "—" },
            offline,
        )
        s.status == MonitorService.Status.TG_DOWN -> Triple(
            Lang.t("Telegram недоступен", "Telegram unreachable"),
            Lang.t("С ${timeStr(s.since)}", "Since ${timeStr(s.since)}"),
            fail,
        )
        s.status == MonitorService.Status.NO_NETWORK -> Triple(
            Lang.t("Нет интернета", "No internet"),
            Lang.t("С ${timeStr(s.since)}", "Since ${timeStr(s.since)}"),
            offline,
        )
        else -> Triple(
            Lang.t("Проверяю…", "Checking…"),
            "Telegram",
            idle,
        )
    }
}

class StatusGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatusGlanceWidget()
}
