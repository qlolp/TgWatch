package ru.tgwatch

import java.io.File
import java.io.IOException

internal object HistoryFileReader {
    private const val LEGACY_FIELDS = 4
    private const val EPOCH_FIELDS = 5
    private const val LATENCY_INDEX = 3
    private const val EPOCH_INDEX = 4
    data class ReadResult(val rows: List<Observation>, val physicalRows: Int)
    fun readCounted(file: File): ReadResult {
        if (!file.exists()) return ReadResult(emptyList(), 0)
        if (file.length() > HistoryCompaction.MAX_DISK_BYTES) throw IOException("History byte limit exceeded")
        val count = file.useLines { lines ->
            lines.filter { it.isNotBlank() }.take(HistoryCompaction.MAX_DISK_ROWS + 1).count()
        }
        if (count > HistoryCompaction.MAX_DISK_ROWS) throw IOException("History row limit exceeded")
        // I/O errors propagate: an unreadable file must never be treated as empty.
        val rows = file.readLines().mapNotNull { line ->
            val p = line.split(',')
            if (p.size !in LEGACY_FIELDS..EPOCH_FIELDS) return@mapNotNull null
            val at = p[0].toLongOrNull() ?: return@mapNotNull null
            val until = p[1].toLongOrNull() ?: return@mapNotNull null
            val latency = p[LATENCY_INDEX].toLongOrNull() ?: return@mapNotNull null
            val epoch = if (p.size == EPOCH_FIELDS) p[EPOCH_INDEX].toLongOrNull() ?: return@mapNotNull null else 0L
            val row = Observation(at, until, p[2], latency, epoch)
            if (!validObservation(row)) return@mapNotNull null
            row
        }
        return ReadResult(rows, count)
    }
}
