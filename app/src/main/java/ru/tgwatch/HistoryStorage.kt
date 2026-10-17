package ru.tgwatch

import java.io.File
import java.io.FileOutputStream

/** File I/O is separate from Android context so migration and crash-safe writes can be tested. */
object HistoryStorage {
    /** Tracks successful writes only; a failed append must not defer the next fsync. */
    class AppendSyncPolicy {
        private var lastSyncElapsed: Long? = null
        private var lastKind: String? = null
        fun needsSync(kind: String, elapsed: Long): Boolean {
            val syncedAt = lastSyncElapsed ?: return true
            return kind != lastKind || elapsed < syncedAt || elapsed - syncedAt >= 60_000L
        }
        fun saved(kind: String?, elapsed: Long, synced: Boolean) {
            lastKind = kind
            if (synced) lastSyncElapsed = elapsed
        }
    }

    fun validateSnapshot(samples: List<Observation>) {
        require(samples.size <= 65_000) { "Too many observations" }
        require(samples.all { validObservation(it) }) { "Invalid observation" }
    }

    /** Validation and durable replacement complete before the caller publishes this snapshot. */
    fun replace(file: File, samples: List<Observation>, cutoff: Long): List<Observation> {
        val snapshot = samples.toList()
        validateSnapshot(snapshot)
        val retained = Timeline.retained(snapshot, cutoff)
        write(file, retained)
        return retained
    }
    data class Migration(val samples: List<Observation>, val complete: Boolean)
    fun migrateLegacy(legacy: File, destination: File, marker: File, current: List<Observation>): Migration {
        if (marker.exists()) return Migration(current, true)
        return try {
            val merged = Timeline.retained(merge(readLegacy(legacy), current), Long.MIN_VALUE)
            write(destination, merged)
            marker.writeText("legacy minute estimates imported")
            Migration(merged, true)
        } catch (_: Exception) {
            // The healthy destination remains usable; retry migration after the next load.
            Migration(current, false)
        }
    }
    fun read(file: File): List<Observation> = HistoryFileReader.readCounted(file).rows
    fun merge(older: List<Observation>, newer: List<Observation>): List<Observation> =
        Timeline.unique(older + newer).sortedWith(compareBy({ it.clockEpoch }, { it.at }))

    fun write(file: File, samples: List<Observation>, io: HistoryFileOperations = SystemHistoryFileOperations) {
        validateSnapshot(samples)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { stream ->
            stream.write(samples.joinToString("\n") { encode(it) }.toByteArray(Charsets.UTF_8))
            io.syncFile(stream.fd)
        }
        io.replace(tmp, file)
        io.syncDirectory(file.absoluteFile.parentFile!!)
    }
    /** Persist rename/creation metadata too; unsupported or failed sync must reach the caller. */
    internal fun syncDirectory(directory: File) = SystemHistoryFileOperations.syncDirectory(directory)
    fun append(file: File, sample: Observation, sync: Boolean = false,
        io: HistoryFileOperations = SystemHistoryFileOperations) {
        file.parentFile?.mkdirs()
        val created = !file.exists()
        FileOutputStream(file, true).use { stream ->
            stream.write("\n${encode(sample)}\n".toByteArray(Charsets.UTF_8))
            if (sync) io.syncFile(stream.fd)
        }
        if (created && sync) io.syncDirectory(file.absoluteFile.parentFile!!)
    }
    private fun encode(s: Observation) = "${s.at},${s.until},${s.kind},${s.latencyMs},${s.clockEpoch}"
    fun readLegacy(file: File): List<Observation> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            val p = line.split(',').map { it.toLongOrNull() }
            if (p.size != 5 || p.any { it == null }) return@mapNotNull null
            val minute = p[0]!!
            val ok = p[1]!!; val fail = p[2]!!; val offline = p[3]!!
            if (minute < 0 || ok + fail + offline <= 0) return@mapNotNull null
            val kind = when { fail > 0 && fail >= ok && fail >= offline -> "TG_DOWN"
                offline > 0 && offline >= ok -> "NO_NETWORK"; else -> "OK" }
            Observation(minute * 60_000L, (minute + 1) * 60_000L, kind, if (ok > 0) p[4]!! / ok else -1)
        }
    }
}

internal fun validObservation(row: Observation): Boolean {
    val validIdentity = row.at >= 0 && row.clockEpoch >= 0
    val validResult = row.kind in Timeline.kinds && row.latencyMs >= -1
    val validCoverage = row.isEpochMetadata ||
        row.until > row.at && row.until - row.at <= MAX_OBSERVATION_COVERAGE_MS
    return validIdentity && validResult && validCoverage
}

private const val MAX_OBSERVATION_COVERAGE_MS = 600_000L

/** Length-prefixed strings preserve restored log entries; old newline storage remains readable. */
object EventLogStorage {
    private const val PREFIX = "TGLOG2\n"
    fun encode(rows: List<String>): String = buildString {
        require(rows.size <= 150 && rows.all { it.length <= 4096 }) { "Invalid event log" }
        append(PREFIX)
        rows.forEach { append(it.length).append(':').append(it) }
    }
    fun decode(text: String): List<String> {
        if (!text.startsWith(PREFIX)) return text.split('\n').filter { it.isNotBlank() }
        require(text.length <= PREFIX.length + 150 * (4096 + 5)) { "Event log too large" }
        val rows = mutableListOf<String>()
        var cursor = PREFIX.length
        while (cursor < text.length) {
            val colon = text.indexOf(':', cursor)
            require(colon in cursor + 1..cursor + 4) { "Invalid event log length" }
            val length = text.substring(cursor, colon).toIntOrNull()
                ?: throw IllegalArgumentException("Invalid event log length")
            require(length in 0..4096 && length <= text.length - colon - 1 && rows.size < 150) { "Invalid event log entry" }
            cursor = colon + 1
            rows += text.substring(cursor, cursor + length)
            cursor += length
        }
        return rows
    }
}
