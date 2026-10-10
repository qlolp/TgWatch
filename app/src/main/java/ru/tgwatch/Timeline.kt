package ru.tgwatch

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Observation(val at: Long, val until: Long, val kind: String, val latencyMs: Long = -1L,
    val clockEpoch: Long = 0L)
data class TimeStats(val okMs: Long, val downMs: Long, val partialMs: Long, val offlineMs: Long,
    val unknownMs: Long, val longestOutageMs: Long, val checks: Int, val avgLatencyMs: Long,
    val outageCount: Int = 0, val lastOutageMs: Long = 0, val lastOutageOngoing: Boolean = false,
    val offlineCount: Int = 0, val okChecks: Int = 0, val downChecks: Int = 0,
    val partialChecks: Int = 0, val offlineChecks: Int = 0, val unknownChecks: Int = 0,
    val completedOutageCount: Int = 0, val mttrMs: Long = -1L) {
    val uptimePercent: Double get() = if (okMs + downMs + partialMs == 0L) -1.0
        else okMs * 100.0 / (okMs + downMs + partialMs)
}

data class RecoveryEvent(val durationMs: Long)

fun recoveryDurationStr(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return buildList {
        if (seconds >= 3600) add("${seconds / 3600} ч")
        if (seconds >= 60) add("${seconds / 60 % 60} мин")
        add("${seconds % 60} с")
    }.joinToString(" ")
}

/** Observations expire. Neither a dead service nor two distant failures fill a gap. */
object Timeline {
    val kinds = setOf("OK", "TG_DOWN", "PARTIAL", "NO_NETWORK", "UNKNOWN")
    /** Start a new epoch after rollback, compacting epoch IDs only if their Long range is exhausted. */
    fun clockRollback(samples: List<Observation>, now: Long): List<Observation> {
        if (current(samples).none { it.at > now }) return samples
        val epoch = samples.maxOf { it.clockEpoch }
        val archived: List<Observation>
        val nextEpoch: Long
        if (epoch == Long.MAX_VALUE) {
            val epochs = samples.map { it.clockEpoch }.distinct().sorted()
                .mapIndexed { index, old -> old to index.toLong() }.toMap()
            archived = samples.map { it.copy(clockEpoch = epochs.getValue(it.clockEpoch)) }
            nextEpoch = epochs.size.toLong()
        } else {
            archived = samples
            nextEpoch = epoch + 1
        }
        return archived + Observation(now, now + 1, "UNKNOWN", clockEpoch = nextEpoch)
    }
    fun current(samples: List<Observation>): List<Observation> {
        val epoch = samples.maxOfOrNull { it.clockEpoch } ?: 0L
        return samples.filter { it.clockEpoch == epoch }
    }
    fun segments(samples: List<Observation>, from: Long, until: Long): List<Observation> {
        if (until <= from) return emptyList()
        val epoch = samples.maxOfOrNull { it.clockEpoch } ?: 0L
        val ordered = current(samples).filter { it.until > it.at && it.at < until && it.until > from }
            .associateBy { it.at }.values.sortedBy { it.at }
        val result = mutableListOf<Observation>()
        var cursor = from
        ordered.forEachIndexed { index, sample ->
            val start = maxOf(cursor, sample.at, from)
            val next = ordered.getOrNull(index + 1)?.at ?: until
            val end = minOf(sample.until, until, next)
            if (end <= start) return@forEachIndexed
            if (start > cursor) result += Observation(cursor, start, "UNKNOWN", clockEpoch = epoch)
            result += sample.copy(at = start, until = end, kind = sample.kind.takeIf { it in kinds } ?: "UNKNOWN")
            cursor = end
        }
        if (cursor < until) result += Observation(cursor, until, "UNKNOWN", clockEpoch = epoch)
        return result
    }
    fun stats(samples: List<Observation>, from: Long, until: Long): TimeStats {
        val duration = mutableMapOf<String, Long>()
        var streak = 0L
        var longest = 0L
        var outages = 0
        var lastOutage = 0L
        var offlineCount = 0
        var previous = "UNKNOWN"
        var incidentStart: Long? = null
        var completed = 0
        var completedDuration = 0L
        for (segment in segments(samples, from, until)) {
            val length = segment.until - segment.at
            duration[segment.kind] = (duration[segment.kind] ?: 0) + length
            if (segment.kind == "TG_DOWN" && previous != "TG_DOWN") outages++
            if (segment.kind == "NO_NETWORK" && previous != "NO_NETWORK") offlineCount++
            streak = if (segment.kind == "TG_DOWN") streak + length else 0L
            if (segment.kind == "TG_DOWN") lastOutage = streak
            longest = maxOf(longest, streak)
            // An observed OK establishes the onset; first/window-clipped failures are censored.
            when (segment.kind) {
                "TG_DOWN" -> if (incidentStart == null && previous == "OK") incidentStart = segment.at
                "PARTIAL" -> Unit // PARTIAL can continue, but cannot start, a confirmed incident.
                "OK" -> {
                    incidentStart?.let { start ->
                        completed++
                        completedDuration += segment.at - start
                    }
                    incidentStart = null
                }
                else -> incidentStart = null
            }
            previous = segment.kind
        }
        val inRange = current(samples).filter { it.at >= from && it.at < until }
        val latency = inRange.filter { it.kind == "OK" && it.latencyMs >= 0 }.map { it.latencyMs }
        return TimeStats(duration["OK"] ?: 0, duration["TG_DOWN"] ?: 0, duration["PARTIAL"] ?: 0,
            duration["NO_NETWORK"] ?: 0, duration["UNKNOWN"] ?: 0, longest, inRange.size,
            // Divide before summing so valid large Long latencies cannot overflow the mean.
            if (latency.isEmpty()) -1 else latency.sumOf { it / latency.size } + latency.sumOf { it % latency.size } / latency.size,
            outages, lastOutage, previous == "TG_DOWN", offlineCount,
            inRange.count { it.kind == "OK" }, inRange.count { it.kind == "TG_DOWN" },
            inRange.count { it.kind == "PARTIAL" }, inRange.count { it.kind == "NO_NETWORK" },
            inRange.count { it.kind == "UNKNOWN" }, completed,
            if (completed == 0) -1 else completedDuration / completed)
    }
    /** Only a fresh OK closes a contiguous observed incident. Gaps are never outage time. */
    fun recovery(samples: List<Observation>, checkedAt: Long, partialEnabled: Boolean): RecoveryEvent? {
        val rows = current(samples)
        val latest = rows.maxByOrNull { it.at } ?: return null
        if (latest.at != checkedAt || latest.kind != "OK") return null
        val before = rows.filter { it.at < checkedAt }
        val previous = before.maxByOrNull { it.at } ?: return null
        val incidentKinds = setOf("TG_DOWN", "NO_NETWORK", "PARTIAL")
        if (previous.kind !in incidentKinds || previous.until < checkedAt) return null
        var elapsed = 0L
        var hardFailure = false
        for (segment in segments(before, before.minOf { it.at }, checkedAt).asReversed()) {
            if (segment.kind !in incidentKinds) break
            elapsed += segment.until - segment.at
            if (segment.kind != "PARTIAL") hardFailure = true
        }
        return if (elapsed > 0 && (hardFailure || partialEnabled)) RecoveryEvent(elapsed) else null
    }
    fun csv(samples: List<Observation>, from: Long, until: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return buildString {
            append("from_utc,until_utc,status,duration_ms,latency_ms,clock_epoch,in_current_statistics\n")
            val epoch = samples.maxOfOrNull { it.clockEpoch } ?: 0L
            // Archived epochs retain original timestamps, including future dates after rollback.
            val rows = segments(samples, from, until) + samples.filter { it.clockEpoch != epoch }
            for (s in rows) {
                append(format.format(Date(s.at))).append(',').append(format.format(Date(s.until))).append(',')
                append(s.kind).append(',').append(s.until - s.at).append(',')
                if (s.latencyMs >= 0) append(s.latencyMs)
                append(',').append(s.clockEpoch).append(',').append(s.clockEpoch == epoch)
                append('\n')
            }
        }
    }
}
