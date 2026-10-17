package ru.tgwatch

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

internal class TargetStatusUi(private val activity: Activity, private val color: (Int) -> Unit) {
    private data class Display(val state: TargetSnapshot, val active: Boolean, val stale: Boolean, val pending: Boolean)

    fun render(id: TargetId, now: Long) {
        if (id == TargetId.TELEGRAM) return
        val definition = TargetPreferences.definition(activity, id)
        val state = TargetStateStore.current(activity, id)
        val active = MonitorService.running && definition.enabled
        val pending = TargetSettingsExecutor.busy(id) || TargetConfigTransactions.isPending(id)
        val display = Display(state, active, stale(state, now), pending)
        val title = title(display)
        showText(display, title, now)
        showIcon(display, title)
        text(R.id.tvDiagnostics).text = title + "\n" +
            activity.getString(R.string.target_interval, definition.intervalSec)
        val muted = TargetAlertPreferences.remaining(activity, id) > 0L
        activity.findViewById<Button>(R.id.btnResumeAlerts).visibility = if (muted) View.VISIBLE else View.GONE
    }

    private fun stale(state: TargetSnapshot, now: Long): Boolean = if (state.checkedElapsedMs > 0L)
        ProbeRules.isStale(state.checkedElapsedMs, android.os.SystemClock.elapsedRealtime(), state.expectedIntervalSec)
        else ProbeRules.isStale(state.checkedAtMs, now, state.expectedIntervalSec)

    private fun title(display: Display): String {
        val name = TargetNames.label(display.state.id)
        val measured = activity.getString(R.string.target_title, name,
            activity.getString(label(display.state.status)))
        return when {
            display.pending -> activity.getString(R.string.target_settings_busy)
            !display.active -> activity.getString(R.string.target_off, name)
            display.state.checkedAtMs == 0L -> activity.getString(R.string.target_checking, name)
            display.stale -> activity.getString(R.string.target_stale, measured)
            else -> measured
        }
    }

    private fun showText(display: Display, title: String, now: Long) {
        val state = display.state
        text(R.id.tvTitle).text = title
        text(R.id.tvSince).text = if (state.checkedAtMs > 0L)
            activity.getString(R.string.target_checked, agoStr(state.checkedAtMs, now)) else ""
        text(R.id.tvReason).setText(when {
            display.pending -> R.string.target_pending_help
            !display.active -> R.string.target_idle_help
            TargetHistories.failures[state.id] != null -> R.string.target_history_failed
            else -> help(state.id)
        })
        text(R.id.tvLatency).text = state.latencyMs?.takeIf { display.active && !display.stale }
            ?.let { activity.getString(R.string.target_latency, it) } ?: "—"
        text(R.id.tvChecked).text = if (display.active) agoStr(state.checkedAtMs, now) else "—"
    }

    private fun showIcon(display: Display, title: String) {
        val icon = when {
            !display.active -> R.drawable.ic_stat_pause
            display.stale -> R.drawable.ic_stat_wait
            else -> when (display.state.status) {
                TargetStatus.OK -> R.drawable.ic_stat_ok
                TargetStatus.DOWN -> R.drawable.ic_stat_fail
                TargetStatus.NO_NETWORK -> R.drawable.ic_stat_offline
                else -> R.drawable.ic_stat_wait
            }
        }
        activity.findViewById<ImageView>(R.id.ivStatus).apply { contentDescription = title; setImageResource(icon) }
        color(activity.getColor(background(display)))
    }

    private fun background(display: Display): Int =
        if (!display.active || display.stale) R.color.status_idle else when (display.state.status) {
            TargetStatus.OK -> R.color.status_ok
            TargetStatus.DOWN -> R.color.status_fail
            TargetStatus.NO_NETWORK, TargetStatus.PARTIAL -> R.color.status_offline
            else -> R.color.status_idle
        }

    private fun text(id: Int) = activity.findViewById<TextView>(id)
    private fun label(status: TargetStatus): Int = when (status) {
        TargetStatus.OK -> R.string.target_ok
        TargetStatus.DOWN -> R.string.target_down
        TargetStatus.PARTIAL -> R.string.target_partial
        TargetStatus.NO_NETWORK -> R.string.target_offline
        TargetStatus.UNKNOWN -> R.string.target_unknown
    }
    private fun help(id: TargetId) = when (id) {
        TargetId.MATRIX -> R.string.target_matrix_help
        TargetId.WHATSAPP -> R.string.target_whatsapp_help
        else -> R.string.target_custom_help
    }
}
