package ru.tgwatch

/** Selected-goal presentation consumes current endpoint/clock cohorts; it never reads credential text. */
internal object TargetHistoryPresentation {
    private const val MINUTE_MS = 60_000L
    fun version(id: TargetId): Long = if (id == TargetId.TELEGRAM) History.version
        else TargetPersistence.active?.revisions?.get(id) ?: 0L

    fun summary(id: TargetId, stats: TimeStats, days: Int): String {
        val legacy = History.describeStats(stats, days)
        return if (id == TargetId.TELEGRAM) legacy
            else TargetNames.label(id) + "\n" + legacy.replace("Telegram", TargetNames.label(id))
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
