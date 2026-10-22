package ru.tgwatch

internal const val HISTORY_DAY_MS = 86_400_000L
internal const val HISTORY_MINUTE_MS = 60_000L
internal const val HISTORY_COARSE_MS = 300_000L
internal const val RETENTION_WEEK_DAYS = 7
internal const val RETENTION_MONTH_DAYS = 30
internal const val RETENTION_QUARTER_DAYS = 90

/**
 * Tiered retention: raw checks for a week, minute buckets for a month,
 * five-minute buckets for a quarter. Rollup only changes representation
 * of already recorded checks; it never invents or drops a performed check
 * inside the retained window.
 */
enum class Retention(
    val days: Int,
    val rawMs: Long,
    val minuteMs: Long,
    val coarseMs: Long,
) {
    WEEK(RETENTION_WEEK_DAYS, RETENTION_WEEK_DAYS * HISTORY_DAY_MS, 0L, 0L),
    MONTH(RETENTION_MONTH_DAYS, RETENTION_WEEK_DAYS * HISTORY_DAY_MS,
        RETENTION_MONTH_DAYS * HISTORY_DAY_MS, 0L),
    QUARTER(RETENTION_QUARTER_DAYS, RETENTION_WEEK_DAYS * HISTORY_DAY_MS,
        RETENTION_MONTH_DAYS * HISTORY_DAY_MS, RETENTION_QUARTER_DAYS * HISTORY_DAY_MS);

    val totalMs: Long get() = maxOf(rawMs, minuteMs, coarseMs)

    companion object {
        const val DAY_MS = HISTORY_DAY_MS
        const val MINUTE_MS = HISTORY_MINUTE_MS
        const val COARSE_MS = HISTORY_COARSE_MS
    }
}

/**
 * Check counts for one aligned time bucket.
 * `ok+fail+offline+partial+unknown` equals the number of source checks in the bucket.
 */
data class HistoryBucket(
    val clockEpoch: Long,
    val startMs: Long,
    val resolutionMs: Long,
    val ok: Int = 0,
    val fail: Int = 0,
    val offline: Int = 0,
    val partial: Int = 0,
    val unknown: Int = 0,
    val latencySum: Long = 0L,
    val latencyCount: Int = 0,
) {
    init {
        require(resolutionMs == Retention.MINUTE_MS || resolutionMs == Retention.COARSE_MS) {
            "Unsupported bucket resolution"
        }
        require(startMs >= 0L && startMs % resolutionMs == 0L) { "Bucket must start on a resolution boundary" }
        require(ok >= 0 && fail >= 0 && offline >= 0 && partial >= 0 && unknown >= 0) { "Negative check count" }
        require(latencySum >= 0L && latencyCount >= 0) { "Negative latency total" }
        require(latencyCount <= ok) { "Latency samples may only come from OK checks" }
    }

    val total: Int get() = ok + fail + offline + partial + unknown
    val endMs: Long get() = startMs + resolutionMs

    fun plus(kind: String, latencyMs: Long): HistoryBucket {
        val status = Timeline.canonicalKind(kind).takeIf { it in Timeline.kinds } ?: "UNKNOWN"
        val measured = status == "OK" && latencyMs >= 0L
        return copy(
            ok = ok + if (status == "OK") 1 else 0,
            fail = fail + if (status == "TG_DOWN") 1 else 0,
            offline = offline + if (status == "NO_NETWORK") 1 else 0,
            partial = partial + if (status == "PARTIAL") 1 else 0,
            unknown = unknown + if (status == "UNKNOWN") 1 else 0,
            latencySum = if (measured) latencySum + latencyMs else latencySum,
            latencyCount = latencyCount + if (measured) 1 else 0,
        )
    }

    fun merge(other: HistoryBucket): HistoryBucket {
        require(clockEpoch == other.clockEpoch && startMs == other.startMs && resolutionMs == other.resolutionMs) {
            "Buckets must share epoch and window"
        }
        return copy(
            ok = ok + other.ok,
            fail = fail + other.fail,
            offline = offline + other.offline,
            partial = partial + other.partial,
            unknown = unknown + other.unknown,
            latencySum = latencySum + other.latencySum,
            latencyCount = latencyCount + other.latencyCount,
        )
    }

    companion object {
        fun bucketOf(sample: Observation, resolutionMs: Long): HistoryBucket {
            require(resolutionMs == Retention.MINUTE_MS || resolutionMs == Retention.COARSE_MS)
            val start = sample.at - floorMod(sample.at, resolutionMs)
            return HistoryBucket(sample.clockEpoch, start, resolutionMs).plus(sample.kind, sample.latencyMs)
        }

        private fun floorMod(value: Long, modulus: Long): Long {
            val rem = value % modulus
            return if (rem < 0L) rem + modulus else rem
        }
    }
}

/**
 * Representation of already-recorded checks under one retention policy.
 * Raw rows stay in the current Observation form; older checks become buckets.
 */
data class RolledHistory(
    val raw: List<Observation>,
    val minutes: List<HistoryBucket>,
    val coarse: List<HistoryBucket>,
) {
    val isEmpty: Boolean get() = raw.isEmpty() && minutes.isEmpty() && coarse.isEmpty()
}

/** Write-time ceilings checked before a durable replacement. */
data class HistoryBounds(val maxRows: Int, val maxBytes: Long, val expectedRows: Int)

object HistoryRollup {
    fun bounds(retention: Retention): HistoryBounds {
        val minuteRows = if (retention.minuteMs <= 0L) 0 else (retention.minuteMs / Retention.MINUTE_MS).toInt()
        val coarseRows = if (retention.coarseMs <= 0L) 0 else (retention.coarseMs / Retention.COARSE_MS).toInt()
        val expected = RAW_ROW_BUDGET + minuteRows + coarseRows
        return HistoryBounds(
            maxRows = maxOf(RAW_ROW_BUDGET, expected),
            maxBytes = HistoryCompaction.MAX_DISK_BYTES,
            expectedRows = expected,
        )
    }

    /**
     * Keep recent checks raw, bucket the rest, drop only what is older than [Retention.totalMs].
     * Epoch markers survive so a rolled-back clock does not resurrect archived samples.
     */
    fun rollup(rows: List<Observation>, now: Long, retention: Retention): RolledHistory {
        val unique = Timeline.unique(rows)
        val currentEpoch = unique.maxOfOrNull { it.clockEpoch } ?: 0L
        val cutoffs = Cutoffs(
            raw = now - retention.rawMs,
            minute = now - retention.minuteMs,
            coarse = now - retention.coarseMs,
            minuteEnabled = retention.minuteMs > 0L,
            coarseEnabled = retention.coarseMs > 0L,
        )
        val raw = mutableListOf<Observation>()
        val minuteBuckets = linkedMapOf<Pair<Long, Long>, HistoryBucket>()
        val coarseBuckets = linkedMapOf<Pair<Long, Long>, HistoryBucket>()
        var latestMarker: Observation? = null
        for (sample in unique) {
            if (sample.isEpochMetadata) {
                val previousMarker = latestMarker
                if (sample.clockEpoch == currentEpoch &&
                    (previousMarker == null || sample.at >= previousMarker.at)) {
                    latestMarker = sample
                }
                continue
            }
            place(sample, cutoffs, raw, minuteBuckets, coarseBuckets)
        }
        appendEpochMarker(latestMarker, raw)
        return RolledHistory(
            raw = raw.sortedWith(compareBy<Observation> { it.clockEpoch }.thenBy { it.at }),
            minutes = minuteBuckets.values.sortedWith(compareBy<HistoryBucket> { it.clockEpoch }.thenBy { it.startMs }),
            coarse = coarseBuckets.values.sortedWith(compareBy<HistoryBucket> { it.clockEpoch }.thenBy { it.startMs }),
        )
    }

    private data class Cutoffs(
        val raw: Long,
        val minute: Long,
        val coarse: Long,
        val minuteEnabled: Boolean,
        val coarseEnabled: Boolean,
    )

    private fun place(
        sample: Observation,
        cutoffs: Cutoffs,
        raw: MutableList<Observation>,
        minutes: MutableMap<Pair<Long, Long>, HistoryBucket>,
        coarse: MutableMap<Pair<Long, Long>, HistoryBucket>,
    ) {
        when {
            sample.until > cutoffs.raw -> raw += sample
            cutoffs.minuteEnabled && sample.until > cutoffs.minute ->
                addBucket(minutes, HistoryBucket.bucketOf(sample, Retention.MINUTE_MS))
            cutoffs.coarseEnabled && sample.until > cutoffs.coarse ->
                addBucket(coarse, HistoryBucket.bucketOf(sample, Retention.COARSE_MS))
        }
    }

    private fun appendEpochMarker(marker: Observation?, raw: MutableList<Observation>) {
        if (marker != null && raw.none { it.clockEpoch == marker.clockEpoch && it.at == marker.at }) {
            raw += marker
        }
    }

    /** Expand buckets to one dominant-kind Observation per bucket for duration-based readers. */
    fun asObservations(rolled: RolledHistory): List<Observation> {
        val fromBuckets = (rolled.minutes + rolled.coarse).map { bucket ->
            val kind = dominantKind(bucket)
            val latency = if (bucket.ok > 0 && bucket.latencyCount > 0)
                LatencyTotal(bucket.latencySum).average(bucket.latencyCount) else -1L
            Observation(bucket.startMs, bucket.endMs, kind, latency, bucket.clockEpoch)
        }
        return (rolled.raw + fromBuckets).sortedWith(compareBy<Observation> { it.clockEpoch }.thenBy { it.at })
    }

    fun dominantKind(bucket: HistoryBucket): String {
        val candidates = listOf(
            "OK" to bucket.ok,
            "TG_DOWN" to bucket.fail,
            "NO_NETWORK" to bucket.offline,
            "PARTIAL" to bucket.partial,
            "UNKNOWN" to bucket.unknown,
        )
        val best = candidates.maxBy { it.second }
        return if (best.second <= 0) "UNKNOWN" else best.first
    }

    private fun addBucket(target: MutableMap<Pair<Long, Long>, HistoryBucket>, bucket: HistoryBucket) {
        val key = bucket.clockEpoch to bucket.startMs
        val previous = target[key]
        target[key] = if (previous == null) bucket else previous.merge(bucket)
    }

    /** Raw checks stay at Observation granularity; buckets replace only older tiers. */
    private const val RAW_ROW_BUDGET = 65_000
}
