package ru.tgwatch

import android.content.Context

internal data class HistoryDisplayKey(val version: Long, val minute: Long, val days: Int)
internal data class HistoryDisplay(
    val version: Long,
    val at: Long,
    val uptime: Double,
    val summary: String,
    val chart: ChartView.Companion.Prepared,
) {
    companion object {
        private const val WEEK_MINUTES = 7 * 24 * 60
        private const val HOUR_MINUTES = 60

        /** The history monitor keeps chart and aggregates on the same snapshot. */
        fun load(ctx: Context, days: Int): HistoryDisplay = synchronized(History) {
            val now = System.currentTimeMillis()
            val uptime = History.stats(ctx, 1, now).uptimePercent
            val summary = History.describeStats(History.stats(ctx, days, now), days)
            val window = if (days == 1) HOUR_MINUTES else WEEK_MINUTES
            val chart = ChartView.prepare(History.lastMinutes(ctx, window, now), now, window)
            HistoryDisplay(History.version, now, uptime, summary, chart)
        }
    }
}
