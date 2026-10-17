package ru.tgwatch

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigInteger

/** Rebuildable local metrics. Strict bounded parsing and the same durable replacement as history. */
object DcLatencyStorage {
    private const val HEADER = "TGWDC1"
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val FIELDS = 12
    private const val MAX_FIELD = 256
    fun read(file: File): List<DcLatencyBucket> {
        if (!file.exists()) return emptyList()
        try {
            require(file.length() <= MAX_BYTES)
            val lines = file.readLines(Charsets.US_ASCII)
            require(lines.firstOrNull() == HEADER && lines.size <= DcLatencyBuckets.MAX_ROWS + 1)
            return lines.drop(1).map(::decode).also(::validate)
        } catch (e: IllegalArgumentException) { throw IOException("Invalid local DC metrics", e) }
    }
    fun write(file: File, rows: List<DcLatencyBucket>, io: HistoryFileOperations = SystemHistoryFileOperations) {
        validate(rows)
        val bytes = (HEADER + rows.joinToString("") { "\n" + encode(it) }).toByteArray(Charsets.US_ASCII)
        require(bytes.size <= MAX_BYTES)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(temporary).use { it.write(bytes); io.syncFile(it.fd) }
            io.replace(temporary, file)
            io.syncDirectory(file.absoluteFile.parentFile!!)
        } finally { temporary.delete() }
    }
    private fun encode(row: DcLatencyBucket) = listOf(row.endpointId, row.endpointRevision, row.clockEpoch,
        row.bucketStartMs, row.successCount, row.exactSum, row.minMs ?: -1, row.maxMs ?: -1,
        row.errorCount, row.notMeasuredCount, row.available, row.histogram.joinToString(":")).joinToString(",")
    private fun decode(line: String): DcLatencyBucket {
        val p = line.split(',')
        require(p.size == FIELDS && p.all { it.length <= MAX_FIELD })
        val values = p.iterator()
        val id = values.next()
        val revision = values.next().toLong()
        val epoch = values.next().toLong()
        val at = values.next().toLong()
        val count = values.next().toLong()
        val sum = values.next().toBigInteger()
        val min = values.next().toLong().takeIf { it >= 0 }
        val max = values.next().toLong().takeIf { it >= 0 }
        val errors = values.next().toLong()
        val missing = values.next().toLong()
        val available = values.next().toBooleanStrict()
        return DcLatencyBucket(id, revision, epoch, at, count, sum, min, max, errors, missing,
            values.next().split(':').map(String::toLong), available)
    }
    private fun validate(rows: List<DcLatencyBucket>) {
        require(rows.size <= DcLatencyBuckets.MAX_ROWS && rows.groupBy { it.endpointId }
            .values.all { it.size <= DcLatencyBuckets.PER_ENDPOINT })
        val identities = rows.map { listOf(it.endpointId, it.endpointRevision, it.clockEpoch, it.bucketStartMs) }
        require(identities.distinct().size == rows.size)
        rows.forEach(::validateRow)
    }
    private fun validateRow(row: DcLatencyBucket) {
            require(DcLatencyBuckets.validId(row.endpointId) && row.endpointRevision >= 0 && row.clockEpoch >= 0 &&
                row.bucketStartMs >= 0 && row.bucketStartMs % DcLatencyBuckets.BUCKET_MS == 0L)
            require(row.successCount >= 0 && row.errorCount >= 0 && row.notMeasuredCount >= 0 &&
                row.histogram.size == DcLatencyBucket.UPPER.size + 1 && row.histogram.all { it >= 0L })
            val count = BigInteger.valueOf(row.successCount)
            require(row.histogram.fold(BigInteger.ZERO) { sum, n -> sum + BigInteger.valueOf(n) } == count)
            require(row.exactSum >= BigInteger.ZERO && row.exactSum <= count * BigInteger.valueOf(Long.MAX_VALUE))
            validateExtrema(row)
    }
    private fun validateExtrema(row: DcLatencyBucket) {
        require(if (row.successCount == 0L) row.minMs == null && row.maxMs == null
            else row.minMs != null && row.maxMs != null && row.minMs >= 0 && row.maxMs >= row.minMs)
    }
}
