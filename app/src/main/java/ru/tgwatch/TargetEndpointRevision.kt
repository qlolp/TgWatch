package ru.tgwatch

/** The metadata row makes a changed endpoint unmeasured without discarding its archived observations. */
object TargetEndpointRevision {
    data class Changed(val revision: Long, val observations: List<TargetObservation>)

    fun change(rows: List<TargetObservation>, currentRevision: Long, nowMs: Long, limit: Int): Changed {
        require(currentRevision >= 0 && nowMs >= 0 && limit > 0)
        require(rows.all { it.endpointRevision <= currentRevision })
        rows.forEach(TargetBackupCodec::validateRow)
        val rebased = if (currentRevision == Long.MAX_VALUE) rebase(rows, currentRevision) else currentRevision to rows
        val revision = rebased.first
        val original = rebased.second
        val cohort = original.filter { it.endpointRevision == revision }.map { it.row }
        val clock = Timeline.clockRollback(cohort, nowMs)
        val epoch = clock.maxOfOrNull { it.clockEpoch } ?: 0L
        val next = revision + 1
        val updated = original.filter { it.endpointRevision != revision } +
            clock.map { TargetObservation(it, revision) } +
            TargetObservation(Observation(nowMs, nowMs, "UNKNOWN", clockEpoch = epoch), next)
        return Changed(next, TargetJournal.retained(updated, nowMs - RetentionPolicy.totalMs(), limit))
    }

    private fun rebase(rows: List<TargetObservation>, current: Long): Pair<Long, List<TargetObservation>> {
        val identities = (rows.map { it.endpointRevision } + current).distinct().sorted()
            .mapIndexed { index, before -> before to index.toLong() }.toMap()
        return identities.getValue(current) to rows.map {
            it.copy(endpointRevision = identities.getValue(it.endpointRevision))
        }
    }
}
