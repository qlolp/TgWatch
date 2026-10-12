package ru.tgwatch

/** Immutable, unique checks ordered by time, all belonging to one current epoch. */
internal class PreparedChecks(private val rows: List<Observation>, val epoch: Long) : List<Observation> by rows

/** Canonical retained rows and current-epoch checks; callers serialize access and bump revision on mutation. */
internal class HistoryPreparationCache {
    data class Prepared(val rows: List<Observation>, val checks: List<Observation>,
        val epoch: Long, val latestAt: Long?)

    private var prepared: Prepared? = null
    private var revision = Long.MIN_VALUE
    private var cutoff = Long.MIN_VALUE
    private var nextExpiry = Long.MIN_VALUE

    fun clear() { prepared = null }

    /** Retention changed the owning list to these exact rows without changing their content. */
    fun acceptRevision(value: Long) { revision = value }

    fun prepare(samples: List<Observation>, revision: Long, from: Long): Prepared {
        prepared?.let {
            if (this.revision == revision && from >= cutoff && from < nextExpiry) {
                cutoff = from
                return it
            }
        }
        val rows = Timeline.retained(samples, from)
        val epoch = rows.lastOrNull()?.clockEpoch ?: 0L
        val checks = PreparedChecks(rows.filter { it.clockEpoch == epoch && !it.isEpochMetadata }, epoch)
        return Prepared(rows, checks, epoch, rows.lastOrNull()?.at).also {
            prepared = it
            this.revision = revision
            cutoff = from
            // Expired epoch metadata stays indefinitely; it must not force preparation on every read.
            nextExpiry = rows.asSequence().map { row -> row.until }.filter { end -> end > from }
                .minOrNull() ?: Long.MAX_VALUE
        }
    }
}
