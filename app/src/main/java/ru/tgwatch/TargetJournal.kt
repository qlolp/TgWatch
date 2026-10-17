package ru.tgwatch

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Fixed six-column private-to-the-app journal; public backups use the independently validated JSON schema. */
internal object TargetJournal {
    private const val FIELD_COUNT = 6
    private const val EPOCH_INDEX = 4
    private const val REVISION_INDEX = 5
    private const val LATENCY_INDEX = 3

    fun read(file: File, id: TargetId): List<TargetObservation> {
        if (!file.isFile || file.length() > HistoryCompaction.MAX_DISK_BYTES)
            throw IOException("Missing target journal")
        return file.bufferedReader().use { reader ->
            val rows = ArrayList<TargetObservation>()
            reader.forEachLine { line ->
                if (line.isNotBlank()) {
                    if (rows.size >= TargetBackupCodec.rowLimit(id) + HistoryCompaction.MAX_APPENDS)
                        throw IOException("Target journal row limit")
                    rows += decode(line)
                }
            }
            rows
        }
    }

    fun write(file: File, rows: List<TargetObservation>, io: HistoryFileOperations) {
        FileOutputStream(file).use { stream ->
            val writer = stream.bufferedWriter()
            rows.forEach { writer.write(encode(it)); writer.newLine() }
            writer.flush()
            io.syncFile(stream.fd)
        }
    }

    fun writeBytes(file: File, bytes: ByteArray, io: HistoryFileOperations) = FileOutputStream(file).use {
        it.write(bytes)
        io.syncFile(it.fd)
    }

    fun append(file: File, row: TargetObservation, sync: Boolean, io: HistoryFileOperations) {
        FileOutputStream(file, true).use {
            it.write(("\n" + encode(row) + "\n").toByteArray(Charsets.UTF_8))
            if (sync) io.syncFile(it.fd)
        }
    }

    fun retained(rows: List<TargetObservation>, cutoff: Long, limit: Int): List<TargetObservation> =
        rows.groupBy { it.endpointRevision }.flatMap { (revision, cohort) ->
            Timeline.retained(cohort.map { it.row }, cutoff, limit).map { TargetObservation(it, revision) }
        }.sortedWith(compareBy<TargetObservation> { it.endpointRevision }
            .thenBy { it.row.clockEpoch }.thenBy { it.row.at }).takeLast(limit)

    private fun encode(value: TargetObservation): String = value.row.let {
        "${it.at},${it.until},${it.kind},${it.latencyMs},${it.clockEpoch},${value.endpointRevision}"
    }

    private fun decode(line: String): TargetObservation = try {
        val fields = line.split(',')
        require(fields.size == FIELD_COUNT)
        TargetObservation(Observation(fields[0].toLong(), fields[1].toLong(), fields[2],
            fields[LATENCY_INDEX].toLong(), fields[EPOCH_INDEX].toLong()), fields[REVISION_INDEX].toLong())
            .also(TargetBackupCodec::validateRow)
    } catch (_: IllegalArgumentException) { throw IOException("Invalid target journal") }
}
