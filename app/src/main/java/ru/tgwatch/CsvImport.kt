package ru.tgwatch

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** Strict reader for the exported UTC timeline, including the legacy five-column format. */
object CsvImport {
    private const val LEGACY_HEADER = "from_utc,until_utc,status,duration_ms,latency_ms"
    private const val HEADER = "$LEGACY_HEADER,clock_epoch,in_current_statistics"
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_ROWS = 65_000
    private val date = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)

    fun parse(text: String): List<Observation> {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "CSV too large" }
        val lines = text.lineSequence().iterator()
        require(lines.hasNext()) { "Missing CSV header" }
        val header = lines.next()
        require(header == HEADER || header == LEGACY_HEADER) { "Unsupported CSV header" }
        val currentFormat = header == HEADER
        val rows = mutableListOf<Pair<Observation, Boolean>>()
        var count = 0
        var maxEpoch = 0L
        while (lines.hasNext()) {
            val line = lines.next()
            if (line.isEmpty() && !lines.hasNext()) break // Normal final newline only.
            require(++count <= MAX_ROWS) { "Too many CSV rows" }
            require(line.length <= 4096) { "CSV row too large" }
            val fields = line.split(',')
            require(fields.size == if (currentFormat) 7 else 5) { "Incomplete CSV row" }
            val (row, active) = record(fields, currentFormat)
            val epoch = row.clockEpoch
            maxEpoch = maxOf(maxEpoch, epoch)
            rows += row to active
        }
        require(rows.all { (row, active) -> active == (row.clockEpoch == maxEpoch) }) { "Inconsistent clock epoch flag" }
        // The old export cannot distinguish real UNKNOWN checks from generated gaps.
        // Omitting both retains unknown time coverage without inventing check counts.
        val observations = rows.map { it.first }.filter { it.kind != "UNKNOWN" || it.isEpochMetadata }
        // Without a retained current-epoch row, archived records would become current.
        // Seven-column exports can preserve the epoch even when all its rows were UNKNOWN gaps.
        if (currentFormat && rows.isNotEmpty() && observations.none { it.clockEpoch == maxEpoch }) {
            val latest = rows.map { it.first }.filter { it.clockEpoch == maxEpoch }.maxBy { it.at }
            return observations + latest.copy(until = latest.at, kind = "UNKNOWN", latencyMs = -1)
        }
        return observations
    }

    internal fun record(fields: List<String>, currentFormat: Boolean): Pair<Observation, Boolean> {
        val at = timestamp(fields[0])
        val until = timestamp(fields[1])
        val kind = fields[2]
        require(kind in Timeline.kinds) { "Unknown status" }
        val duration = number(fields[3])
        require(until >= at && duration == until - at) { "Invalid duration" }
        // UNKNOWN export rows also represent generated, potentially multi-day gaps.
        require(kind == "UNKNOWN" || duration <= 600_000L) { "Observation exceeds coverage limit" }
        val latency = if (fields[4].isEmpty()) -1L else number(fields[4])
        require(latency >= -1L) { "Invalid latency" }
        require(until > at || currentFormat && kind == "UNKNOWN" && latency == -1L) {
            "Invalid epoch metadata"
        }
        val epoch = if (currentFormat) number(fields[5]) else 0L
        require(epoch >= 0L) { "Invalid clock epoch" }
        val active = if (currentFormat) when (fields[6]) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("Invalid current statistics flag")
        } else true
        return Observation(at, until, kind, latency, epoch) to active
    }

    private fun number(value: String): Long {
        require(value.matches(Regex("-?[0-9]+"))) { "Invalid integer" }
        return value.toLongOrNull() ?: throw IllegalArgumentException("Integer overflow")
    }

    private fun timestamp(value: String): Long = try {
        require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z"))) { "Invalid UTC timestamp" }
        val result = LocalDateTime.parse(value, date).toInstant(ZoneOffset.UTC).toEpochMilli()
        require(result >= Instant.EPOCH.toEpochMilli()) { "Negative timestamp" }
        result
    } catch (e: java.time.DateTimeException) {
        throw IllegalArgumentException("Invalid UTC timestamp", e)
    }
}
