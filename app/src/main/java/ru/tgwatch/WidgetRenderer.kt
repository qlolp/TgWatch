package ru.tgwatch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.RemoteViews

/** Worker rendering consumes only a public immutable snapshot; no history or credential reads. */
internal object WidgetRenderer {
    private const val GRAPH_WIDTH = 360
    private const val GRAPH_HEIGHT = 48
    private const val LARGE_FONT_SCALE = 1f
    fun render(ctx: Context, snapshot: WidgetSnapshot, expanded: Boolean): RemoteViews {
        val layout = if (expanded) R.layout.widget_status_expanded else R.layout.widget_status
        val title = title(ctx, snapshot)
        val age = age(ctx, snapshot)
        val latency = latency(ctx, snapshot)
        return RemoteViews(ctx.packageName, layout).apply {
            setInt(R.id.widgetRoot, "setBackgroundResource", background(snapshot))
            setImageViewResource(R.id.widgetIcon, if (!snapshot.fresh) R.drawable.ic_stat_wait
                else if (snapshot.state.status == TargetStatus.OK) R.drawable.ic_stat_ok else R.drawable.ic_stat_fail)
            setContentDescription(R.id.widgetIcon, title)
            setTextViewText(R.id.widgetTitle, title)
            setTextViewText(R.id.widgetSubtitle, "$age · $latency")
            setTextViewText(R.id.widgetLastSuccess,
                AndroidText.resolve(ctx, LastSuccessPolicy.label(snapshot.lastSuccessAt, System.currentTimeMillis())))
            val success = AndroidText.resolve(ctx,
                LastSuccessPolicy.label(snapshot.lastSuccessAt, System.currentTimeMillis()))
            setContentDescription(R.id.widgetRoot, announcement(ctx, snapshot) + " · " + age + " · " + latency + " · " +
                success + " · " +
                ctx.getString(R.string.widget_check_goal, TargetNames.label(ctx, snapshot.target)))
            if (snapshot.widgetId > 0 && snapshot.token != null) {
                val check = WidgetIdentity(snapshot.widgetId, snapshot.target, "check", snapshot.token)
                setOnClickPendingIntent(R.id.widgetRoot, StatusWidget.pending(ctx, check))
                if (expanded) {
                    setOnClickPendingIntent(R.id.widgetCheck, StatusWidget.pending(ctx, check))
                    setContentDescription(R.id.widgetCheck,
                        ctx.getString(R.string.widget_check_goal, TargetNames.label(ctx, snapshot.target)))
                    setOnClickPendingIntent(R.id.widgetMute, StatusWidget.pending(ctx, check.copy(action = "mute")))
                }
            }
            if (expanded) showExpanded(ctx, snapshot) else showCompact(ctx)
        }
    }
    private fun RemoteViews.showCompact(ctx: Context) {
        val metadata = if (ctx.resources.configuration.fontScale > LARGE_FONT_SCALE) View.GONE else View.VISIBLE
        setViewVisibility(R.id.widgetSubtitle, metadata)
        setViewVisibility(R.id.widgetLastSuccess, metadata)
    }
    private fun background(snapshot: WidgetSnapshot) = if (!snapshot.fresh) R.drawable.bg_widget_idle
        else when (snapshot.state.status) {
            TargetStatus.OK -> R.drawable.bg_widget_ok
            TargetStatus.DOWN -> R.drawable.bg_widget_fail
            else -> R.drawable.bg_widget_offline
        }
    private fun title(ctx: Context, value: WidgetSnapshot): String {
        val name = TargetNames.label(ctx, value.target)
        return when {
            value.token == null -> ctx.getString(R.string.widget_configure)
            value.locked -> ctx.getString(R.string.widget_locked, name)
            !value.running -> ctx.getString(R.string.widget_goal_off, name)
            !value.fresh -> ctx.getString(R.string.widget_goal_stale, name)
            else -> measured(ctx, value)
        }
    }
    private fun measured(ctx: Context, value: WidgetSnapshot): String =
        ctx.getString(R.string.target_title, TargetNames.label(ctx, value.target), ctx.getString(when (
            value.state.status) {
            TargetStatus.OK -> R.string.target_ok
            TargetStatus.DOWN -> R.string.target_down
            TargetStatus.PARTIAL -> R.string.target_partial
            TargetStatus.NO_NETWORK -> R.string.target_offline
            TargetStatus.UNKNOWN -> R.string.target_unknown
        }))
    private fun RemoteViews.showExpanded(ctx: Context, value: WidgetSnapshot) {
        val coverage = value.aggregate?.coverage?.let({ formatPercent(ctx, it) }) ?: "—"
        val coverageLabel = ctx.getString(R.string.widget_coverage_day, coverage)
        setTextViewText(R.id.widgetCoverage, coverageLabel)
        val age = age(ctx, value)
        setTextViewText(R.id.widgetSubtitle, ctx.getString(R.string.widget_age_coverage, age, coverage))
        val metadata = if (ctx.resources.configuration.fontScale > LARGE_FONT_SCALE) View.GONE else View.VISIBLE
        setViewVisibility(R.id.widgetSubtitle, metadata)
        setViewVisibility(R.id.widgetLastSuccess, metadata)
        setViewVisibility(R.id.widgetCoverage, View.GONE)
        setViewVisibility(R.id.widgetMute, if (value.running && !value.locked) View.VISIBLE else View.GONE)
        setContentDescription(R.id.widgetMute,
            ctx.getString(R.string.widget_mute_goal, TargetNames.label(ctx, value.target)))
        val bins = value.aggregate?.bins.orEmpty()
        setImageViewBitmap(R.id.widgetGraph, graph(ctx, bins))
        setContentDescription(R.id.widgetGraph, ctx.getString(R.string.widget_graph_description,
            bins.count { it == TargetStatus.OK }, bins.count { it == TargetStatus.UNKNOWN }))
        setContentDescription(R.id.widgetRoot, announcement(ctx, value) + " · " + age + " · " + coverageLabel + " · " +
            AndroidText.resolve(ctx, LastSuccessPolicy.label(value.lastSuccessAt, System.currentTimeMillis())) + " · " +
            latency(ctx, value))
    }
    private fun age(ctx: Context, value: WidgetSnapshot) = value.state.checkedAtMs.takeIf { it > 0 }
        ?.let { agoStr(ctx, it, System.currentTimeMillis()) } ?: "—"
    private fun latency(ctx: Context, value: WidgetSnapshot) = value.state.latencyMs?.takeIf { value.fresh }
        ?.let { ctx.getString(R.string.target_latency, it) } ?: "—"
    private fun announcement(ctx: Context, value: WidgetSnapshot): String {
        val title = title(ctx, value)
        if (value.state.checkedAtMs <= 0) return title
        val result = ctx.getString(R.string.widget_last_result, measured(ctx, value))
        return if (value.fresh) result else "$title · $result"
    }
    private fun graph(ctx: Context, bins: List<TargetStatus>): Bitmap {
        val bitmap = Bitmap.createBitmap(GRAPH_WIDTH, GRAPH_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        if (bins.isEmpty()) { canvas.drawColor(ctx.getColor(R.color.chart_empty)); return bitmap }
        bins.forEachIndexed { index, status ->
            paint.color = ctx.getColor(when (status) {
                TargetStatus.OK -> R.color.status_ok
                TargetStatus.DOWN -> R.color.status_fail
                TargetStatus.NO_NETWORK, TargetStatus.PARTIAL -> R.color.status_offline
                TargetStatus.UNKNOWN -> R.color.chart_empty
            })
            canvas.drawRect(index * GRAPH_WIDTH.toFloat() / bins.size, 0f,
                (index + 1) * GRAPH_WIDTH.toFloat() / bins.size, GRAPH_HEIGHT.toFloat(), paint)
        }
        return bitmap
    }
}
