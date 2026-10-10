package ru.tgwatch

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Observation(val at: Long, val until: Long, val kind: String, val latencyMs: Long = -1L,
    val clockEpoch: Long = 0L)
data class TimeStats(val okMs: Long, val downMs: Long, val partialMs: Long, val offlineMs: Long,
    val unknownMs: Long, val longestOutageMs: Long, val checks: Int, val avgLatencyMs: Long) {
    val uptimePercent: Double get() = if (okMs + downMs + partialMs == 0L) -1.0
        else okMs * 100.0 / (okMs + downMs + partialMs)
}

/** Observations expire. Neither a dead service nor two distant failures fill a gap. */
object Timeline {
    val kinds = setOf("OK", "TG_DOWN", "PARTIAL", "NO_NETWORK", "UNKNOWN")
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
        for (segment in segments(samples, from, until)) {
            val length = segment.until - segment.at
            duration[segment.kind] = (duration[segment.kind] ?: 0) + length
            streak = if (segment.kind == "TG_DOWN") streak + length else 0L
            longest = maxOf(longest, streak)
        }
        val inRange = current(samples).filter { it.at >= from && it.at < until }
        val latency = inRange.filter { it.kind == "OK" && it.latencyMs >= 0 }.map { it.latencyMs }
        return TimeStats(duration["OK"] ?: 0, duration["TG_DOWN"] ?: 0, duration["PARTIAL"] ?: 0,
            duration["NO_NETWORK"] ?: 0, duration["UNKNOWN"] ?: 0, longest, inRange.size,
            if (latency.isEmpty()) -1 else latency.sum() / latency.size)
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
