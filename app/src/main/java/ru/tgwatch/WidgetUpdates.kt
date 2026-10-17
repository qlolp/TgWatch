package ru.tgwatch

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One physical preparation and one latest pending refresh; receivers have independent eight-second lifetimes. */
internal object WidgetUpdates {
    private data class Request(val revision: Long, val periodic: Boolean, val finished: () -> Unit)
    private val revision = AtomicLong()
    private val gates = mutableMapOf<Int, RedrawGate>()
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1))
    private var loader: LatestRequestLoader<Request, List<WidgetSnapshot>>? = null
    fun ids(ctx: Context): IntArray = runCatching { AppWidgetManager.getInstance(ctx).getAppWidgetIds(
        ComponentName(ctx, StatusWidget::class.java)) }.getOrDefault(intArrayOf())

    fun request(ctx: Context, periodic: Boolean, finished: () -> Unit = {}) {
        val app = ctx.applicationContext
        val selected = synchronized(this) {
            if (loader == null) loader = LatestRequestLoader(worker, Executor { it.run() }, { _ ->
                ids(app).map { id ->
                    val target = WidgetPreferences.target(app, id)
                    runCatching { WidgetHistoryCache.prepare(app, target) }
                    WidgetSnapshot.load(app, id)
                }
            }, { request, result ->
                try { result.getOrNull()?.let { draw(app, it, request.periodic) } }
                finally { request.finished() }
            })
            checkNotNull(loader)
        }
        // Never hold the initializer monitor while entering the loader: delivery may queue a refresh.
        selected.request(Request(revision.incrementAndGet(), periodic, finished))
    }
    private fun draw(ctx: Context, snapshots: List<WidgetSnapshot>, periodic: Boolean) {
        val owned = ids(ctx).toSet()
        synchronized(gates) { gates.keys.retainAll(owned) }
        val manager = AppWidgetManager.getInstance(ctx)
        var retry = false
        for (snapshot in snapshots) {
            val current = snapshot.widgetId in owned &&
                snapshot.token == WidgetPreferences.token(ctx, snapshot.widgetId) &&
                snapshot.target == WidgetPreferences.target(ctx, snapshot.widgetId)
            if (!current) continue
            if (snapshot != WidgetSnapshot.load(ctx, snapshot.widgetId)) retry = true
            else {
                val expanded = manager.getAppWidgetOptions(snapshot.widgetId)
                    .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) >= EXPANDED_DP
                val gate = synchronized(gates) { gates.getOrPut(snapshot.widgetId) { RedrawGate() } }
                if (gate.shouldDraw(snapshot to expanded, SystemClock.elapsedRealtime()) || !periodic)
                    runCatching {
                        manager.updateAppWidget(snapshot.widgetId, WidgetRenderer.render(ctx, snapshot, expanded))
                    }
            }
        }
        if (retry) request(ctx, false)
    }
    fun delete(ctx: Context, removed: List<Int>) {
        val app = ctx.applicationContext
        // Cancelled immutable PendingIntents already reject stale taps if this bounded write is refused.
        TargetSettingsExecutor.background(TargetId.TELEGRAM, {
            val owned = ids(app).toSet()
            removed.filter { it !in owned }.forEach { WidgetPreferences.remove(app, it) }
            true
        }, {})
        synchronized(gates) { removed.forEach(gates::remove) }
    }
    private const val EXPANDED_DP = 120
}
