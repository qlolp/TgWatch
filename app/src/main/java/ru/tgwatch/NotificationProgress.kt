package ru.tgwatch

import kotlin.math.roundToInt

/** The same time denominator as TimeStats; unknown/offline time is excluded. */
object NotificationProgress {
    const val MAX_PERCENT = 100
    fun percent(stats: TimeStats): Int? = stats.uptimePercent.takeIf { it.isFinite() && it >= 0 }
        ?.roundToInt()?.coerceIn(0, MAX_PERCENT)
}
