package ru.tgwatch

/** Observation completeness counts offline observations as data, unlike service-assessment coverage. */
data class ObservationCoverage(
    val observedMs: Long,
    val unknownMs: Long,
    val windowMs: Long,
    val percent: Double?,
) {
    val complete: Boolean get() = observedMs > 0L && unknownMs == 0L

    companion object {
        private const val PERCENT = 100.0
        fun from(stats: TimeStats, windowMs: Long): ObservationCoverage {
            require(windowMs >= 0L)
            val assessed = listOf(stats.okMs, stats.downMs, stats.partialMs)
                .fold(0L, ::saturatingAdd)
            val observed = saturatingAdd(assessed, stats.offlineMs)
            val percent = if (windowMs == 0L) null else observed.coerceIn(0L, windowMs) * PERCENT / windowMs
            return ObservationCoverage(observed, stats.unknownMs.coerceAtLeast(0L), windowMs, percent)
        }

        private fun saturatingAdd(left: Long, right: Long): Long {
            val a = left.coerceAtLeast(0L)
            val b = right.coerceAtLeast(0L)
            return if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
        }
    }
}
