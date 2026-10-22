package ru.tgwatch

import java.io.IOException

/** Bounded worker-owned memory avoids reparsing a journal for every check and retains failed writes for repair. */
internal class TargetJournalWriter(private val source: (TargetId) -> List<TargetObservation>,
    private val replace: (TargetId, List<TargetObservation>) -> Unit,
    private val append: (TargetId, TargetObservation, Boolean) -> Unit,
    private val bytes: (TargetId) -> Long, private val elapsed: () -> Long) {
    private class Cached(var rows: List<TargetObservation>) {
        var physicalRows = rows.size
        var failure: HistoryFailure? = null
        var prunedWall: Long? = null
        val compaction = HistoryCompaction()
        val sync = HistoryStorage.AppendSyncPolicy()
    }
    private val entries = mutableMapOf<TargetId, Cached>()
    @Volatile var revisions: Map<TargetId, Long> = TargetId.entries.associateWith { 0L }
        private set

    fun read(id: TargetId): HistoryRead<List<TargetObservation>> = try {
        val current = cache(id)
        current.failure?.let { HistoryRead.Unavailable(current.rows, it) } ?: HistoryRead.Ready(current.rows)
    } catch (_: IOException) { HistoryRead.Unavailable(null, HistoryFailure.READ) }

    fun record(id: TargetId, row: Observation, revision: Long) {
        TargetBackupCodec.validateRow(TargetObservation(row, revision))
        val current = cache(id)
        val own = current.rows.filter { it.endpointRevision == revision }.map { it.row }
        val prepared = Timeline.clockRollback(own, row.at)
        val observation = TargetObservation(
            row.copy(clockEpoch = prepared.maxOfOrNull { it.clockEpoch } ?: 0L), revision)
        val updated = current.rows.filter { it.endpointRevision != revision } +
            prepared.map { TargetObservation(it, revision) } + observation
        val retained = TargetJournal.retained(updated, row.at - RetentionPolicy.totalMs(),
            TargetBackupCodec.rowLimit(id))
        if (retained != updated) {
            current.prunedWall = maxOf(current.prunedWall ?: row.at, row.at)
            current.compaction.onPruned()
        }
        current.rows = retained
        changed(id)
        val clockRewound = current.prunedWall?.let { row.at < it } == true || prepared !== own
        write(id, current, observation, clockRewound)
    }

    fun prepare(id: TargetId, revision: Long, now: Long) {
        val current = cache(id)
        val own = current.rows.filter { it.endpointRevision == revision }.map { it.row }
        val rolled = Timeline.clockRollback(own, now)
        val all = current.rows.filter { it.endpointRevision != revision } +
            rolled.map { TargetObservation(it, revision) }
        val retained = TargetJournal.retained(all, now - RetentionPolicy.totalMs(), TargetBackupCodec.rowLimit(id))
        val rewoundAfterPrune = current.prunedWall?.let { now < it } == true
        if (retained != all) {
            current.prunedWall = maxOf(current.prunedWall ?: now, now)
            current.compaction.onPruned()
        }
        if (current.rows != retained) { current.rows = retained; changed(id) }
        if (rolled !== own || rewoundAfterPrune) {
            try { replace(id, current.rows); replaced(id, current.rows) }
            catch (failure: IOException) {
                current.compaction.failedWrite(); current.failure = HistoryFailure.WRITE; throw failure
            }
        }
    }

    private fun write(id: TargetId, current: Cached, observation: TargetObservation, force: Boolean) {
        val now = elapsed()
        try {
            if (force || current.compaction.needsReplacement(now, current.physicalRows + 1, bytes(id))) {
                replace(id, current.rows)
                replaced(id, current.rows)
                current.sync.saved(observation.row.kind, now, true)
            } else {
                val required = current.sync.needsSync(observation.row.kind, now)
                append(id, observation, required)
                current.physicalRows++
                current.compaction.savedAppend()
                current.sync.saved(observation.row.kind, now, required)
            }
            current.failure = null
        } catch (failure: IOException) {
            current.compaction.failedWrite()
            current.failure = HistoryFailure.WRITE
            throw failure
        }
    }

    fun replaced(id: TargetId, rows: List<TargetObservation>) {
        entries[id]?.let {
            if (it.rows != rows) changed(id)
            it.rows = rows.toList()
            it.physicalRows = rows.size
            it.failure = null
            it.prunedWall = null
            it.compaction.savedReplacement(elapsed())
        }
    }
    fun clear() { entries.clear(); TargetId.entries.forEach(::changed) }
    private fun changed(id: TargetId) { revisions = revisions + (id to Math.addExact(revisions.getValue(id), 1L)) }
    private fun cache(id: TargetId): Cached = entries.getOrPut(id) { Cached(source(id)) }
}
