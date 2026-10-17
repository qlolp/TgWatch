package ru.tgwatch

/** Nearest-rank percentiles of confirmed samples, never failed-request zeroes. */
data class LatencySummary(val count: Int, val meanMs: Long?, val p50Ms: Long?, val p95Ms: Long?,
    val smallSample: Boolean) {
    companion object {
        private const val SMALL_SAMPLE = 20
        private const val PERCENT = 100L
        private const val MEDIAN = 50L
        private const val HIGH_PERCENTILE = 95L
        fun exact(samples: List<Long>): LatencySummary {
            val sorted = samples.filter { it >= 0L }.sorted()
            if (sorted.isEmpty()) return LatencySummary(0, null, null, null, false)
            val total = LatencyTotal().also { sum -> sorted.forEach(sum::add) }
            fun rank(percent: Long) = sorted[((percent * sorted.size + PERCENT - 1) / PERCENT - 1).toInt()]
            return LatencySummary(sorted.size, total.average(sorted.size), rank(MEDIAN), rank(HIGH_PERCENTILE),
                sorted.size < SMALL_SAMPLE)
        }
    }
}
