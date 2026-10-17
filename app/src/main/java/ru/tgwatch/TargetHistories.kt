package ru.tgwatch

import android.content.Context

/** Secondary publication uses the same bounded ordered worker; no disk operation delays measured state publication. */
internal object TargetHistories {
    private const val CHECK_BUDGET_MS = 15_000L
    private const val MS_PER_SECOND = 1_000L
    private const val MAX_DAYS = 7
    private val sessions = TargetSessionLedger()
    @Volatile var failures: Map<TargetId, HistoryFailure?> = emptyMap()
        private set

    fun markTelegram(session: Long) = sessions.recorded(session, TargetId.TELEGRAM)
    fun restored() { failures = emptyMap() }

    fun publish(ctx: Context, session: Long, state: TargetSnapshot,
        measured: Boolean = state.status != TargetStatus.UNKNOWN,
        recovered: (RecoveryEvent) -> Unit = {}): Boolean {
        require(state.id != TargetId.TELEGRAM)
        if (!measured) return false
        return HistorySessions.queue(ctx).submit(session) { synchronized(History) {
            sessions.recorded(session, state.id)
            val duration = state.expectedIntervalSec * MS_PER_SECOND + CHECK_BUDGET_MS
            val end = state.checkedAtMs + minOf(duration, Long.MAX_VALUE - state.checkedAtMs)
            LastSuccessStore.record(ctx, state.status.name, state.checkedAtMs, state.id)
            runCatching { TargetStateStore.persist(ctx, state) }.onFailure {
                if (it is Error) throw it
                android.util.Log.w("TgWatch", "target state checkpoint: ${it.javaClass.simpleName}")
            }
            val written = runCatching {
                TargetPersistence.storage().record(state.id,
                    Observation(state.checkedAtMs, end, state.status.name, state.latencyMs ?: -1L),
                    state.endpointRevision)
            }
            failures = failures + (state.id to if (written.isFailure) HistoryFailure.WRITE else null)
            written.getOrThrow()
            if (state.status == TargetStatus.OK)
                Timeline.recovery(rows(ctx, state.id, state.checkedAtMs), state.checkedAtMs, true)?.let(recovered)
        } }
    }

    fun end(ctx: Context, session: Long, now: Long) = synchronized(History) {
        DcLatencyRecorder.flush()
        for (id in sessions.end(session)) {
            if (id == TargetId.TELEGRAM) History.endSession(ctx, now) else {
                val closed = runCatching { closeTarget(ctx, id, now) }
                val failure = closed.exceptionOrNull()?.let {
                    if (it is HistoryUnavailableException) it.failure else HistoryFailure.WRITE
                }
                failures = failures + (id to failure)
            }
        }
    }

    private fun closeTarget(ctx: Context, id: TargetId, now: Long) {
        val revision = TargetPreferences.endpointRevision(ctx, id)
        val store = TargetPersistence.storage()
        val source = store.recordedRows(id, revision, now)
        val old = if (source is HistoryRead.Unavailable && source.failure == HistoryFailure.WRITE)
            source.lastKnown ?: source.valueOrThrow() else source.valueOrThrow()
        val epoch = old.filter { it.endpointRevision == revision }.maxOfOrNull { it.row.clockEpoch } ?: 0L
        val clipped = old.map { value ->
            val row = value.row
            if (value.endpointRevision != revision || row.clockEpoch != epoch || row.isEpochMetadata) value
            else if (row.at >= now) value.copy(row = Observation(row.at, row.at, "UNKNOWN", clockEpoch = epoch))
            else value.copy(row = row.copy(until = minOf(row.until, now)))
        }
        store.replaceRows(id, clipped)
    }

    fun rows(ctx: Context, id: TargetId, now: Long = System.currentTimeMillis()): List<Observation> =
        read(ctx, id, now).valueOrThrow()

    fun stats(ctx: Context, id: TargetId, days: Int = 1,
        now: Long = System.currentTimeMillis()): HistoryRead<TimeStats> = synchronized(History) {
        if (id == TargetId.TELEGRAM) return@synchronized History.readStats(ctx, days, now)
        val selected = read(ctx, id, now)
        fun compute(rows: List<Observation>) =
            Timeline.stats(rows, now - days.coerceIn(1, MAX_DAYS) * CoverageStats.DAY_MS, now)
        when (selected) {
            is HistoryRead.Ready -> HistoryRead.Ready(compute(selected.value))
            is HistoryRead.Unavailable -> HistoryRead.Unavailable(selected.lastKnown?.let(::compute), selected.failure)
        }
    }

    private fun read(ctx: Context, id: TargetId, now: Long): HistoryRead<List<Observation>> = synchronized(History) {
        if (id == TargetId.TELEGRAM) return@synchronized HistoryRead.Ready(History.snapshot(ctx, now))
        val blocked = TargetPreferences.fence.snapshot(id).changing || TargetConfigTransactions.isPending(id)
        if (blocked) return@synchronized HistoryRead.Unavailable(null, HistoryFailure.BACKPRESSURE)
        val revision = TargetPreferences.endpointRevision(ctx, id)
        val source = TargetPersistence.storage().recordedRows(id, revision, now)
        val own = when (source) {
            is HistoryRead.Ready -> source.value
            is HistoryRead.Unavailable -> source.lastKnown
        }?.filter { it.endpointRevision == revision }?.map { it.row }
        when (source) {
            is HistoryRead.Ready -> HistoryRead.Ready(checkNotNull(own))
            is HistoryRead.Unavailable -> HistoryRead.Unavailable(own, source.failure)
        }
    }
}
