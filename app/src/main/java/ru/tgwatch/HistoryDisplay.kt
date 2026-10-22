package ru.tgwatch

import android.content.Context

internal data class HistoryDisplayKey(val version: Long, val minute: Long, val days: Int,
    val targetId: TargetId = TargetId.TELEGRAM, val endpointRevision: Long = 0L, val generation: String? = null)
internal data class HistoryDisplay(
    val version: Long,
    val at: Long,
    val uptime: Double,
    val coverage: CoverageStats,
    val summary: String,
    val chart: ChartView.Companion.Prepared,
) {
    companion object {
        private val MAX_DAYS get() = RetentionPolicy.maxDays()
        private const val WEEK_MINUTES = 7 * 24 * 60
        private const val HOUR_MINUTES = 60

        /** The history monitor keeps chart and aggregates on the same snapshot. */
        fun load(ctx: Context, days: Int, id: TargetId = TargetId.TELEGRAM): HistoryDisplay = synchronized(History) {
            val now = System.currentTimeMillis()
            val selectedDays = days.coerceIn(1, MAX_DAYS)
            val stats = TargetHistories.stats(ctx, id, selectedDays, now).valueOrThrow()
            val uptime = stats.uptimePercent
            val coverage = CoverageStats.from(stats, selectedDays * CoverageStats.DAY_MS)
            val summary = TargetHistoryPresentation.summary(ctx, id, stats, selectedDays)
            val window = if (days == 1) HOUR_MINUTES else WEEK_MINUTES
            val minutes = if (id == TargetId.TELEGRAM) History.lastMinutes(ctx, window, now)
                else TargetHistoryPresentation.minutes(TargetHistories.rows(ctx, id, now), window, now)
            val chart = ChartView.prepare(minutes, now, window)
            HistoryDisplay(TargetHistoryPresentation.version(id), now, uptime, coverage, summary, chart)
        }
    }
}
