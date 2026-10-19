package ru.tgwatch

import android.content.Context
import java.math.BigInteger

internal data class LatencyScreen(val scalar: LatencyDisplay, val summary: String, val dcSummary: String,
    val points: List<Pair<Long, Long>>)

/** Entire formatting/aggregation/plot preparation runs on the screen's bounded worker. */
internal object LatencyPresentation {
    private const val IPV4 = 2
    private const val IPV6 = 3
    private const val FIRST_DC = 4
    private const val MAX_SERIES = 13
    private const val MEDIAN = 50
    private const val P95 = 95
    private const val COLUMNS = 512

    fun prepare(ctx: Context, id: TargetId, series: Int, scalar: LatencyDisplay,
        dc: HistoryRead<List<DcLatencyBucket>>): LatencyScreen {
        val rows = currentDc(dc, scalar, series)
        val points = if (series == 0 || id != TargetId.TELEGRAM) scalar.samples.map { it.at to it.latencyMs }
            else rows.filter { it.available && it.successCount > 0 }.map {
                it.bucketStartMs to it.exactSum.divide(BigInteger.valueOf(it.successCount)).toLong() }
        val plot = points.groupBy {
            val fraction = (it.first - scalar.fromMs).toDouble() / (scalar.untilMs - scalar.fromMs).coerceAtLeast(1)
            (fraction * COLUMNS).toInt()
        }.values.flatMap { listOf(it.minBy { p -> p.second }, it.maxBy { p -> p.second }) }
        val dcText = if (id == TargetId.TELEGRAM) dcText(ctx, dc, rows) else ""
        return LatencyScreen(scalar, summary(ctx, scalar), dcText, plot)
    }
    private fun summary(ctx: Context, value: LatencyDisplay): String = buildList {
        val summary = value.summary
        add(if (summary.count == 0) ctx.getString(R.string.latency_no_data) else ctx.getString(R.string.latency_metrics,
            summary.count, ms(ctx, summary.meanMs), ms(ctx, summary.p50Ms), ms(ctx, summary.p95Ms)))
        if (summary.smallSample) add(ctx.getString(R.string.latency_small_sample))
        add(ctx.getString(R.string.latency_counts, value.errorCount, value.missingCount,
            value.coverage.percent?.let({ formatPercent(ctx, it) }) ?: "—"))
    }.joinToString("\n")

    private fun currentDc(source: HistoryRead<List<DcLatencyBucket>>, scalar: LatencyDisplay,
        series: Int): List<DcLatencyBucket> {
        val rows = when (source) {
            is HistoryRead.Ready -> source.value
            is HistoryRead.Unavailable -> source.lastKnown.orEmpty()
        }
        val current = rows.filter { it.endpointRevision == MtProtoEndpoints.revision }
        val epoch = current.maxOfOrNull { it.clockEpoch } ?: 0L
        val id = MtProtoEndpoints.all.getOrNull(series - FIRST_DC)?.name?.let(MtProtoEndpoints::idFor)
        return current.filter { it.clockEpoch == epoch && it.bucketStartMs >= scalar.fromMs &&
            it.bucketStartMs <= scalar.untilMs && when (series) {
                IPV4 -> it.endpointId.endsWith("v4")
                IPV6 -> it.endpointId.endsWith("v6")
                in FIRST_DC..MAX_SERIES -> it.endpointId == id
                else -> true
            } }
    }
    private fun dcText(ctx: Context, source: HistoryRead<List<DcLatencyBucket>>, rows: List<DcLatencyBucket>): String =
        when {
            source is HistoryRead.Unavailable || rows.any { !it.available } ->
                ctx.getString(R.string.history_load_failed)
            rows.isEmpty() -> ctx.getString(R.string.latency_no_dc)
            else -> ctx.getString(R.string.latency_dc_local) + "\n" +
                rows.groupBy { it.endpointId }.entries.joinToString("\n") {
                    val aggregate = aggregate(it.value)
                    if (aggregate == null) ctx.getString(R.string.history_load_failed) else
                        ctx.getString(R.string.latency_dc_metrics, it.key, aggregate.successCount, aggregate.errorCount,
                            aggregate.notMeasuredCount, range(ctx, aggregate.percentileRange(MEDIAN)),
                            range(ctx, aggregate.percentileRange(P95)))
                }
        }

    private fun aggregate(rows: List<DcLatencyBucket>): DcLatencyBucket? = try {
        fun sum(values: List<Long>): Long = LatencyTotal.checkedSum(values)
            ?: throw ArithmeticException("DC counter total exceeds its supported range")
        rows.first().copy(successCount = sum(rows.map { it.successCount }),
            errorCount = sum(rows.map { it.errorCount }), notMeasuredCount = sum(rows.map { it.notMeasuredCount }),
            histogram = rows.first().histogram.indices.map { index -> sum(rows.map { it.histogram[index] }) })
    } catch (_: ArithmeticException) { null }

    private fun range(ctx: Context, value: LongRange?): String = when {
        value == null -> "—"
        value.last == Long.MAX_VALUE -> ">${ms(ctx, value.first - 1)}"
        else -> "${value.first}–${ms(ctx, value.last)}"
    }
    private fun ms(ctx: Context, value: Long?) = value?.let { ctx.getString(R.string.latency_ms, it) } ?: "—"
}
