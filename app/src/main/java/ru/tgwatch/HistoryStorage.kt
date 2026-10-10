package ru.tgwatch

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

/** File I/O is separate from Android so migration and crash-safe writes can be tested. */
object HistoryStorage {
    data class Migration(val samples: List<Observation>, val complete: Boolean)
    fun migrateLegacy(legacy: File, destination: File, marker: File, current: List<Observation>): Migration {
        if (marker.exists()) return Migration(current, true)
        return try {
            val merged = merge(readLegacy(legacy), current)
            write(destination, merged)
            marker.writeText("legacy minute estimates imported")
            Migration(merged, true)
        } catch (_: Exception) {
            // The healthy destination remains usable; retry migration after the next load.
            Migration(current, false)
        }
    }
    fun read(file: File): List<Observation> {
        if (!file.exists()) return emptyList()
        // I/O errors propagate: an unreadable file must never be treated as empty.
        return file.readLines().mapNotNull { line ->
            val p = line.split(',')
            if (p.size !in 4..5) return@mapNotNull null
            val at = p[0].toLongOrNull() ?: return@mapNotNull null
            val until = p[1].toLongOrNull() ?: return@mapNotNull null
            val latency = p[3].toLongOrNull() ?: return@mapNotNull null
            if (at < 0 || until <= at || until - at > 600_000L || p[2] !in Timeline.kinds) return@mapNotNull null
            val epoch = if (p.size == 5) p[4].toLongOrNull() ?: return@mapNotNull null else 0L
            if (epoch < 0) return@mapNotNull null
            Observation(at, until, p[2], latency, epoch)
        }
    }
    fun merge(older: List<Observation>, newer: List<Observation>): List<Observation> =
        (older + newer).associateBy { it.clockEpoch to it.at }.values.sortedWith(compareBy({ it.clockEpoch }, { it.at }))

    fun write(file: File, samples: List<Observation>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { stream ->
            stream.write(samples.joinToString("\n") { encode(it) }.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            // Same-filesystem rename replaces atomically on Android/Linux; never delete first.
            check(tmp.renameTo(file)) { "Cannot atomically replace history" }
        }
    }
    fun append(file: File, sample: Observation) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { stream ->
            stream.write("\n${encode(sample)}\n".toByteArray(Charsets.UTF_8))
        }
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
