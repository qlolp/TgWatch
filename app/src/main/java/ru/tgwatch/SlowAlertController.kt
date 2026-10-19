package ru.tgwatch

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock

/** Measurement policy and notification visibility have independent lifetimes. */
internal class SlowAlertController(
    private val ctx: Context,
    private val notifications: EventNotifications,
    private val running: () -> Boolean,
    private val log: (String) -> Unit,
) {
    private val policy = TargetSlowAlert { TargetPreferences.storedDefinition(ctx, TargetId.TELEGRAM).alerts }
    private val display = SlowAlertPresentation()
    private val settings = Prefs.sp(ctx)
    private val manager = ctx.getSystemService(NotificationManager::class.java)
    private var latencyMs = -1L
    private var measuredStatus = "UNKNOWN"
    private var measuredElapsed = 0L
    private var expectedIntervalSec = TargetDefinition.DEFAULT_TELEGRAM_SEC
    private var closed = false
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        val muteChanged = key?.startsWith("alert_mute_") == true || key == "alerts_muted_until"
        val policyChanged = key == null || key == "notify_slow" || key.startsWith("quiet_")
        val targetChanged = key?.startsWith(TargetPreferences.key(TargetId.TELEGRAM, "")) == true
        if (muteChanged || policyChanged || targetChanged) reconcile()
    }
    init { notifications.clearSlow(); settings.registerOnSharedPreferenceChangeListener(listener) }

    @Synchronized fun onCheck(status: String, latency: Long, elapsed: Long) {
        if (closed) return
        val snapshot = TargetSnapshot(TargetId.TELEGRAM, TargetStatus.fromWire(status) ?: TargetStatus.UNKNOWN,
            latencyMs = latency.takeIf { it >= 0L })
        val action = policy.onCheck(snapshot, true, elapsed)
        measuredStatus = status
        measuredElapsed = elapsed
        expectedIntervalSec = MonitorService.state.expectedIntervalSec
        if (status == "OK" && latency >= 0) latencyMs = latency
        when (action) {
            SlowAlert.Action.ALERT -> log(ctx.renderString(
                R.string.copy_slow_alert_controller_telegram_is_slow_ms,
                latency))
            SlowAlert.Action.CLEAR -> if (status == "OK" && latency >= 0L &&
                latency < TargetSlowAlert.clearLevel(TargetPreferences.storedDefinition(ctx, TargetId.TELEGRAM)
                    .alerts.slowThresholdMs))
                log(ctx.renderString(R.string.copy_slow_alert_controller_telegram_is_no_longer))
            SlowAlert.Action.NONE -> Unit
        }
        reconcile()
    }

    @Synchronized fun unmeasured() {
        if (closed) return
        policy.onCheck(TargetSnapshot(TargetId.TELEGRAM), false, SystemClock.elapsedRealtime())
        reconcile()
    }

    @Synchronized fun reconcile() {
        if (closed) return
        val now = System.currentTimeMillis()
        val blockedChannel = manager.getNotificationChannel(EventNotifications.SLOW_CHANNEL)?.importance ==
            NotificationManager.IMPORTANCE_NONE
        val enabled = Prefs.notifyFor(ctx, "SLOW") && manager.areNotificationsEnabled() && !blockedChannel
        val muted = Prefs.alertsMutedUntil(ctx, now) > now
        val quiet = !Prefs.alertsAllowed(ctx, now) && !muted
        val live = running()
        policy.refreshSettings()
        if (!live) policy.clear(SystemClock.elapsedRealtime())
        val fresh = measuredStatus == "OK" && measuredElapsed > 0L &&
            !ProbeRules.isStale(measuredElapsed, SystemClock.elapsedRealtime(), expectedIntervalSec)
        val state = SlowDisplayState(policy.active, enabled, muted, quiet, live && fresh)
        val action = display.reconcile(state, SystemClock.elapsedRealtime())
        if (action == SlowDisplayAction.SHOW) notifications.slow(latencyMs)
        if (action == SlowDisplayAction.CLEAR || state.blocked) notifications.clearSlow()
    }

    @Synchronized fun close() {
        closed = true
        settings.unregisterOnSharedPreferenceChangeListener(listener)
        notifications.clearSlow()
    }
}
