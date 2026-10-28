package ru.tgwatch

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/** Узкий контракт службы: только необходимое состояние и действия, без доступа к остальным полям. */
internal interface OngoingNotificationHost {
    val isDestroyed: Boolean
    val historySession: Long
    fun currentState(): MonitorService.State
    fun acceptsRunningCheck(): Boolean
    fun servicePending(requestCode: Int, action: String): PendingIntent
}

/** Отображение постоянного уведомления и ограниченное фоновое обновление статистики. */
internal class OngoingNotificationController(
    private val context: Context,
    private val notificationManager: () -> NotificationManager,
    private val notificationId: Int,
    private val host: OngoingNotificationHost,
) {
    private val stats = DailyStatsCache()
    private val gate = RedrawGate()
    private val statisticsPending = AtomicBoolean()

    /** Строит уведомление для startForeground и фиксирует его как уже показанное. */
    fun buildForForeground(): Notification {
        val view = view(host.currentState())
        val notification = build(view)
        gate.shouldDraw(view, SystemClock.elapsedRealtime())
        return notification
    }

    fun scheduleStatisticsRefresh() {
        val refreshDue = stats.needsRefresh(History.version, System.currentTimeMillis())
        if (!host.acceptsRunningCheck() || !refreshDue) return
        if (!statisticsPending.compareAndSet(false, true)) return
        val admitted = HistorySessions.queue(context).submit(host.historySession) {
            try {
                if (host.acceptsRunningCheck()) {
                    refreshStats()
                    post(host.currentState())
                }
            } finally { statisticsPending.set(false) }
        }
        if (!admitted) statisticsPending.set(false)
    }

    fun refreshStats(now: Long = System.currentTimeMillis()) {
        if (!stats.needsRefresh(History.version, now)) return
        val read = History.readStats(context, now = now)
        if (read is HistoryRead.Ready) stats.get(History.version, now) { read.value }
    }

    /** [periodic] — тик раз в 15 с: одинаковое содержимое повторно не отправляем в шторку. */
    @Suppress("TooGenericExceptionCaught") // Notification publication is best-effort across OEM implementations.
    fun post(s: MonitorService.State, periodic: Boolean = false) {
        if (host.isDestroyed) return
        val view = view(s)
        if (!gate.shouldDraw(view, SystemClock.elapsedRealtime()) && periodic) return
        try {
            notificationManager().notify(notificationId, build(view))
        } catch (e: Exception) {
            Log.w(TAG, "notify", e)
        }
        if (host.isDestroyed) notificationManager().cancel(notificationId)
    }

    /** Complete presentation key, derived only from the immutable state and cached aggregates. */
    private fun view(s: MonitorService.State, now: Long = System.currentTimeMillis()): OngoingView =
        if (TargetPreferences.definition(context, TargetId.TELEGRAM).enabled)
            OngoingNotification.view(s, stats.peek() ?: EMPTY_STATS, now)
        else if (RegionalPreferences.onlyRegional(context))
            OngoingNotification.regional(context, now)
        else OngoingNotification.targets(TargetId.entries.filter { it != TargetId.TELEGRAM &&
            Prefs.isEnabled(context) && TargetPreferences.storedDefinition(context, it).enabled }
            .map { TargetStateStore.current(context, it) }, now, SystemClock.elapsedRealtime())

    private fun build(view: OngoingView): Notification =
        OngoingNotification.build(context, view, host::servicePending)

    private companion object {
        const val TAG = "TgWatch"
        val EMPTY_STATS = TimeStats(0, 0, 0, 0, 0, 0, 0, -1)
    }
}
