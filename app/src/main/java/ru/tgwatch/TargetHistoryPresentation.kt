package ru.tgwatch

/** Selected-goal presentation consumes current endpoint/clock cohorts; it never reads credential text. */
internal object TargetHistoryPresentation {
    private const val MINUTE_MS = 60_000L
    fun version(id: TargetId): Long = if (id == TargetId.TELEGRAM) History.version
        else TargetPersistence.active?.revisions?.get(id) ?: 0L

    fun summary(ctx: android.content.Context, id: TargetId, stats: TimeStats, days: Int): String {
        val value = History.describeStats(stats, days, id)
        // Network kinds are recorded for the primary Telegram check only.
        if (id != TargetId.TELEGRAM) return AndroidText.resolve(ctx, textLines(listOf(TargetNames.text(id), value)))
        val byNetwork = NetworkStats.describe(ctx, days)
        return AndroidText.resolve(ctx, if (byNetwork == null) value else textLines(listOf(value, byNetwork)))
    }

    fun minutes(rows: List<Observation>, count: Int, now: Long): List<History.Minute> {
        val from = now / MINUTE_MS - count + 1
        val bins = sortedMapOf<Long, History.Minute>()
        for (row in Timeline.currentChecks(rows)) {
            if (row.at / MINUTE_MS < from || row.at > now) continue
            val minute = bins.getOrPut(row.at / MINUTE_MS) { History.Minute(row.at / MINUTE_MS) }
            when (TargetStatus.fromWire(row.kind)) {
                TargetStatus.OK -> minute.addLatency(row.latencyMs)
                TargetStatus.DOWN -> minute.fail++
                TargetStatus.PARTIAL -> minute.partial++
                TargetStatus.NO_NETWORK -> minute.offline++
                else -> minute.unknown++
            }
        }
        return bins.values.toList()
    }
}
