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
    private val policy = SlowAlert()
    private val display = SlowAlertPresentation()
    private val settings = Prefs.sp(ctx)
    private val manager = ctx.getSystemService(NotificationManager::class.java)
    private var latencyMs = -1L
    private var closed = false
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        val muteChanged = key?.startsWith("alert_mute_") == true || key == "alerts_muted_until"
        val policyChanged = key == null || key == "notify_slow" || key.startsWith("quiet_")
        if (muteChanged || policyChanged) reconcile()
    }
    init { notifications.clearSlow(); settings.registerOnSharedPreferenceChangeListener(listener) }

    @Synchronized fun onCheck(status: String, latency: Long, elapsed: Long) {
        if (closed) return
        val action = policy.onCheck(status, latency, elapsed)
        if (status == "OK" && latency >= 0) latencyMs = latency
        when (action) {
            SlowAlert.Action.ALERT -> log(Lang.t("Telegram тормозит: ответ за $latency мс",
                "Telegram is slow: $latency ms response"))
            SlowAlert.Action.CLEAR -> if (status == "OK")
                log(Lang.t("Telegram больше не тормозит", "Telegram is no longer slow"))
            SlowAlert.Action.NONE -> Unit
        }
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
        val state = SlowDisplayState(policy.active, enabled, muted, quiet, live)
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
