package ru.tgwatch

import android.content.Context

internal data class IncidentDisplay(val version: Long, val at: Long, val cards: List<String>) {
    companion object {
        fun load(ctx: Context, days: Int): IncidentDisplay = synchronized(History) {
            val now = System.currentTimeMillis()
            val locale = ctx.resources.configuration.locales[0]
            val cards = History.incidents(ctx, days, now).map { IncidentPresentation.describe(it, locale) }
            IncidentDisplay(History.version, now, cards)
        }
    }
}
