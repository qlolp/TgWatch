package ru.tgwatch

import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

/** Status-card text and icons for the Telegram primary target. */
internal class TelegramStatusCard(
    private val views: Views,
    private val animate: (Int) -> Unit,
) {
    data class Views(
        val ivStatus: ImageView,
        val tvTitle: TextView,
        val tvSince: TextView,
        val tvReason: TextView,
        val tvLatency: TextView,
        val tvChecked: TextView,
        val btnResumeAlerts: Button,
    )

    private val ivStatus get() = views.ivStatus
    private val tvTitle get() = views.tvTitle
    private val tvSince get() = views.tvSince
    private val tvReason get() = views.tvReason
    private val tvLatency get() = views.tvLatency
    private val tvChecked get() = views.tvChecked
    private val btnResumeAlerts get() = views.btnResumeAlerts
    fun render(activity: android.app.Activity, s: MonitorService.State, now: Long,
        running: Boolean, stale: Boolean) {
        var color = activity.getColor(R.color.status_idle)
        if (!running) {
            ivStatus.setImageResource(R.drawable.ic_stat_pause)
            tvTitle.text = activity.renderString(R.string.copy_main_monitoring_is_off)
            tvSince.text = activity.renderString(R.string.copy_main_checks_are_stopped)
            tvReason.text = activity.renderString(R.string.copy_main_tap_start_and_the)
        } else if (stale) {
            renderStale(activity, s, now)
        } else {
            color = renderCurrent(activity, s, now)
        }
        val mutedUntil = Prefs.alertsMutedUntil(activity, now)
        btnResumeAlerts.visibility = if (mutedUntil > 0L) android.view.View.VISIBLE else android.view.View.GONE
        if (running && mutedUntil > 0L) tvReason.append(activity.renderString(
            R.string.copy_main_alerts_muted_until, timeStr(activity, mutedUntil)))
        ivStatus.contentDescription = tvTitle.text
        animate(color)
        tvLatency.text = if (running && !stale && s.status == MonitorService.Status.OK)
            activity.renderString(R.string.copy_main_ms, s.latencyMs) else "—"
        tvChecked.text = if (running) agoStr(activity, s.checkedAt, now) else "—"
    }

    private fun renderStale(activity: android.app.Activity, s: MonitorService.State, now: Long) {
        ivStatus.setImageResource(R.drawable.ic_stat_wait)
        tvTitle.text = when (s.status) {
            MonitorService.Status.OK -> activity.renderString(R.string.copy_main_last_known_reachable)
            MonitorService.Status.PARTIAL -> activity.renderString(
                R.string.copy_main_last_known_partially_reachable)
            MonitorService.Status.TG_DOWN -> activity.renderString(R.string.copy_main_last_known_unreachable)
            MonitorService.Status.NO_NETWORK -> activity.renderString(R.string.copy_main_last_known_no_internet)
            MonitorService.Status.UNKNOWN -> activity.renderString(R.string.copy_main_checking)
        }
        tvSince.text = activity.renderString(R.string.copy_main_checked_waiting_for_a,
            agoStr(activity, s.checkedAt, now))
        tvReason.text = activity.renderString(R.string.copy_main_monitoring_is_delayed_android)
    }

    private fun renderCurrent(activity: android.app.Activity, s: MonitorService.State, now: Long): Int {
        var color = activity.getColor(R.color.status_idle)
        when (s.status) {
            MonitorService.Status.OK -> {
                color = activity.getColor(R.color.status_ok)
                ivStatus.setImageResource(R.drawable.ic_stat_ok)
                tvTitle.text = activity.renderString(R.string.copy_main_telegram_is_responding)
            }
            MonitorService.Status.PARTIAL -> {
                color = activity.getColor(R.color.status_offline)
                ivStatus.setImageResource(R.drawable.ic_stat_wait)
                tvTitle.text = activity.renderString(R.string.copy_main_telegram_is_partially_reachable)
            }
            MonitorService.Status.TG_DOWN -> {
                color = activity.getColor(R.color.status_fail)
                ivStatus.setImageResource(R.drawable.ic_stat_fail)
                tvTitle.text = activity.renderString(R.string.copy_main_telegram_is_unreachable)
            }
            MonitorService.Status.NO_NETWORK -> {
                color = activity.getColor(R.color.status_offline)
                ivStatus.setImageResource(R.drawable.ic_stat_offline)
                tvTitle.text = activity.renderString(R.string.copy_main_no_internet)
            }
            MonitorService.Status.UNKNOWN -> {
                ivStatus.setImageResource(R.drawable.ic_stat_wait)
                tvTitle.text = if (s.checkedAt > 0) activity.renderString(
                    R.string.copy_main_reachability_unknown) else activity.renderString(
                        R.string.copy_main_checking)
            }
        }
        tvSince.text = if (s.status == MonitorService.Status.UNKNOWN) {
            if (s.checkedAt > 0) activity.renderString(R.string.copy_main_checked, agoStr(activity, s.checkedAt, now))
            else activity.renderString(R.string.copy_main_first_check_in_progress)
        } else {
            activity.renderString(R.string.copy_main_for_since,
                durationStr(activity, now - s.since), timeStr(activity, s.since))
        }
        tvReason.text = reason(activity, s)
        return color
    }

    private fun reason(activity: android.app.Activity, s: MonitorService.State): String {
        val base = when (s.status) {
            MonitorService.Status.OK -> activity.renderString(R.string.copy_main_mtproto_and_web_resources)
            MonitorService.Status.PARTIAL -> s.reason
            MonitorService.Status.TG_DOWN -> activity.renderString(R.string.copy_main_reason, s.reason)
            MonitorService.Status.NO_NETWORK -> activity.renderString(R.string.copy_main_reason, s.reason)
            MonitorService.Status.UNKNOWN -> s.reason.ifEmpty {
                activity.renderString(R.string.copy_main_checking_web_resources_and)
            }
        }
        return if (s.lastVibrationAt > 0L) base + activity.renderString(
            R.string.copy_main_last_vibration, timeStr(activity, s.lastVibrationAt)) else base
    }
}
