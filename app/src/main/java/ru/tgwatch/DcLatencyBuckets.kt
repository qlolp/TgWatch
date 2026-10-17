package ru.tgwatch

import java.math.BigInteger

/** Fixed local histogram; the final bin represents responses above ten seconds. */
@Suppress("LongParameterList") // Immutable, validated on-disk metric record.
data class DcLatencyBucket(val endpointId: String, val endpointRevision: Long, val clockEpoch: Long,
    val bucketStartMs: Long, val successCount: Long, val exactSum: BigInteger, val minMs: Long?, val maxMs: Long?,
    val errorCount: Long, val notMeasuredCount: Long, val histogram: List<Long>, val available: Boolean = true) {
    fun percentileRange(percent: Int): LongRange? {
        require(percent in 1..PERCENT)
        if (!available || successCount == 0L) return null
        val rank = BigInteger.valueOf(successCount).multiply(BigInteger.valueOf(percent.toLong()))
            .add(BigInteger.valueOf(PERCENT - 1L)).divide(BigInteger.valueOf(PERCENT.toLong()))
        var count = BigInteger.ZERO
        var result: LongRange? = null
        for (index in histogram.indices) {
            count += BigInteger.valueOf(histogram[index])
            if (count >= rank) {
                result = (if (index == 0) 0L else UPPER[index - 1] + 1)..(UPPER.getOrNull(index) ?: Long.MAX_VALUE)
                break
            }
        }
        return result
    }
    companion object {
        private const val PERCENT = 100
        @Suppress("MagicNumber") // Published fixed millisecond histogram boundaries.
        val UPPER = listOf(10L, 25L, 50L, 100L, 200L, 400L, 600L, 1000L, 2000L, 3000L, 6000L, 10000L)
        fun empty(id: String, revision: Long, epoch: Long, start: Long) =
            DcLatencyBucket(id, revision, epoch, start, 0, BigInteger.ZERO, null, null, 0, 0,
                List(UPPER.size + 1) { 0L })
    }
}

class DcLatencyBuckets(initial: List<DcLatencyBucket> = emptyList()) {
    private val rows = initial.toMutableList()
    @Suppress("LongParameterList") // Explicit provenance is part of the approved recording contract.
    fun add(endpointId: String, endpointRevision: Long, clockEpoch: Long, atMs: Long,
        measurement: ProbeMeasurement, latencyMs: Long?) {
        require(validId(endpointId) && endpointRevision >= 0 && clockEpoch >= 0 && atMs >= 0)
        val start = atMs / BUCKET_MS * BUCKET_MS
        val index = rows.indexOfFirst { it.endpointId == endpointId && it.endpointRevision == endpointRevision &&
            it.clockEpoch == clockEpoch && it.bucketStartMs == start }
        val old = rows.getOrNull(index) ?: DcLatencyBucket.empty(endpointId, endpointRevision, clockEpoch, start)
        val next = update(old, measurement, latencyMs)
        if (index < 0) rows.add(next) else rows[index] = next
        val own = rows.filter { it.endpointId == endpointId }.sortedWith(
            compareBy<DcLatencyBucket> { it.endpointRevision == endpointRevision }
                .thenBy { it.clockEpoch }.thenBy { it.bucketStartMs })
        val obsolete = own.take((own.size - PER_ENDPOINT).coerceAtLeast(0)).toSet()
        rows.removeAll { it in obsolete || (it.endpointId == endpointId && it.endpointRevision == endpointRevision &&
            it.clockEpoch == clockEpoch && it.bucketStartMs < start - KEEP_MS + BUCKET_MS) }
    }

    private fun update(old: DcLatencyBucket, measurement: ProbeMeasurement, latency: Long?): DcLatencyBucket {
        if (!old.available) return old
        return when (measurement) {
            ProbeMeasurement.SUCCESS -> when {
                latency == null || latency < 0 -> update(old, ProbeMeasurement.NOT_MEASURED, null)
                old.successCount == Long.MAX_VALUE -> old.copy(available = false)
                else -> success(old, latency)
            }
            ProbeMeasurement.FAILURE -> if (old.errorCount == Long.MAX_VALUE) old.copy(available = false)
                else old.copy(errorCount = old.errorCount + 1)
            ProbeMeasurement.NOT_MEASURED -> if (old.notMeasuredCount == Long.MAX_VALUE) old.copy(available = false)
                else old.copy(notMeasuredCount = old.notMeasuredCount + 1)
        }
    }
    private fun success(old: DcLatencyBucket, latency: Long): DcLatencyBucket {
        val bin = DcLatencyBucket.UPPER.indexOfFirst { latency <= it }.let {
            if (it < 0) DcLatencyBucket.UPPER.size else it }
        val histogram = old.histogram.toMutableList()
        histogram[bin]++
        return old.copy(successCount = old.successCount + 1, exactSum = old.exactSum + BigInteger.valueOf(latency),
            minMs = minOf(old.minMs ?: latency, latency), maxMs = maxOf(old.maxMs ?: latency, latency),
            histogram = histogram.toList())
    }
    fun snapshot(): List<DcLatencyBucket> = rows.map { it.copy(histogram = it.histogram.toList()) }
    companion object {
        const val BUCKET_MS = 900_000L
        const val KEEP_MS = 604_800_000L
        const val PER_ENDPOINT = 672
        const val MAX_ROWS = 10_752
        fun validId(id: String) = Regex("dc[1-8]-v[46]").matches(id)
    }
}
