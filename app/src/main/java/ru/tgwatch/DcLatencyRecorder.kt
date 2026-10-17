package ru.tgwatch

import android.content.Context
import java.io.File

/** Ordered-history-worker only. Fifteen-minute durable checkpoints bound disk traffic and crash loss. */
internal object DcLatencyRecorder {
    private var selected: File? = null
    private var buckets = DcLatencyBuckets()
    private var checkpoint = -1L
    private var lastAt = -1L
    private var epoch = 0L
    private var failure: HistoryFailure? = null

    fun record(ctx: Context, outcomes: List<ProbeOutcome>, at: Long, generation: String?) {
        try { recordMeasured(ctx, outcomes, at, generation) }
        catch (_: Exception) { failure = HistoryFailure.WRITE }
    }

    private fun recordMeasured(ctx: Context, outcomes: List<ProbeOutcome>, at: Long,
        generation: String?) = synchronized(History) {
        val storage = TargetPersistence.active ?: return@synchronized
        if (generation != storage.generationId || outcomes.none { it.group == ProbeGroup.MTPROTO })
            return@synchronized
        select(File(storage.journalFile(TargetId.TELEGRAM).parentFile, "dc-latency-v1.csv"))
        val historyEpoch = History.snapshot(ctx).maxOfOrNull { it.clockEpoch } ?: 0L
        epoch = maxOf(epoch, historyEpoch)
        if (lastAt > at) epoch = if (epoch < Long.MAX_VALUE) epoch + 1 else epoch
        lastAt = at
        for (outcome in outcomes.filter { it.group == ProbeGroup.MTPROTO }) {
            val id = MtProtoEndpoints.idFor(outcome.endpoint) ?: continue
            buckets.add(id, MtProtoEndpoints.revision, epoch, at, outcome.measurement,
                outcome.latencyMs.takeIf { outcome.reachable && it >= 0L })
        }
        val next = at / DcLatencyBuckets.BUCKET_MS
        if (next != checkpoint || failure != null) {
            flush()
            if (failure == null) checkpoint = next
        }
    }

    fun read(): HistoryRead<List<DcLatencyBucket>> = synchronized(History) {
        val storage = TargetPersistence.active ?: return@synchronized HistoryRead.Ready(emptyList())
        select(File(storage.journalFile(TargetId.TELEGRAM).parentFile, "dc-latency-v1.csv"))
        val values = buckets.snapshot()
        failure?.let { HistoryRead.Unavailable(values.takeIf { it.isNotEmpty() }, it) } ?: HistoryRead.Ready(values)
    }

    fun flush() {
        val file = selected ?: return
        failure = try { DcLatencyStorage.write(file, buckets.snapshot()); null }
            catch (_: Exception) { HistoryFailure.WRITE }
    }

    private fun select(file: File) {
        if (selected == file) return
        selected = file
        failure = null
        val rows = try { DcLatencyStorage.read(file) } catch (_: Exception) {
            failure = HistoryFailure.READ
            emptyList()
        }
        buckets = DcLatencyBuckets(rows)
        epoch = rows.maxOfOrNull { it.clockEpoch } ?: 0L
        lastAt = rows.filter { it.clockEpoch == epoch }.maxOfOrNull { it.bucketStartMs } ?: -1L
        checkpoint = -1L
    }
}
