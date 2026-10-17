package ru.tgwatch

/** Service assessment coverage excludes offline time; all three parts share the selected window. */
data class CoverageStats(val assessedMs: Long, val offlineMs: Long, val unknownMs: Long,
    val windowMs: Long, val percent: Double?) {
    companion object {
        const val DAY_MS = 86_400_000L
        private const val MAX_PERCENT = 100.0
        fun from(stats: TimeStats, windowMs: Long): CoverageStats {
            require(windowMs >= 0)
            val assessed = listOf(stats.okMs, stats.downMs, stats.partialMs).fold(0L) { total, value ->
                total + value.coerceIn(0L, windowMs - total)
            }
            val offline = stats.offlineMs.coerceIn(0L, windowMs - assessed)
            val percent = if (assessed == 0L) null else assessed.toDouble() / windowMs * MAX_PERCENT
            return CoverageStats(assessed, offline, windowMs - assessed - offline, windowMs, percent)
        }
    }
}
