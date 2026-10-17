package ru.tgwatch

import android.content.Context

internal data class IncidentDisplay(val version: Long, val at: Long, val cards: List<String>) {
    companion object {
        private const val MAX_DAYS = 7
        fun load(ctx: Context, days: Int, id: TargetId = TargetId.TELEGRAM): IncidentDisplay = synchronized(History) {
            val now = System.currentTimeMillis()
            val locale = ctx.resources.configuration.locales[0]
            val rows = TargetHistories.rows(ctx, id, now)
            val cards = OutageEpisodes.derive(rows, now - days.coerceIn(1, MAX_DAYS) * CoverageStats.DAY_MS, now)
                .map { IncidentPresentation.describe(it, locale, targetId = id) }
            IncidentDisplay(TargetHistoryPresentation.version(id), now, cards)
        }
    }
}
