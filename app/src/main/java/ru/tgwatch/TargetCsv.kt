package ru.tgwatch

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** CSV v3 contains fixed goal IDs and public endpoint revisions, never private endpoint addresses. */
object TargetCsv {
    private const val HEADER = "from_utc,until_utc,status,duration_ms,latency_ms,clock_epoch," +
        "in_current_statistics,target_id,endpoint_revision"
    private const val LEGACY_FIELDS = 7
    private const val TARGET_COLUMN = 7
    private const val ENDPOINT_COLUMN = 8
    private const val FIELD_COUNT = 9
    private const val MAX_LINE = 4096
    fun encode(values: Map<TargetId, List<TargetObservation>>, from: Long, until: Long): String {
        require(from >= 0L && until >= from)
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        val output = buildString {
            append(HEADER).append('\n')
            for ((id, source) in values) {
                require(source.size <= TargetBackupCodec.rowLimit(id))
                val rows = window(source, from, until)
                val revision = rows.maxOf { it.endpointRevision }
                val epochs = rows.groupBy { it.endpointRevision }
                    .mapValues { (_, cohort) -> cohort.maxOf { it.row.clockEpoch } }
                rows.forEach { value ->
                    TargetBackupCodec.validateRow(value)
                    val row = value.row
                    append(format.format(Date(row.at))).append(',').append(format.format(Date(row.until))).append(',')
                    append(TargetStatus.fromWire(row.kind)!!.name).append(',').append(row.until - row.at).append(',')
                    if (row.latencyMs >= 0L) append(row.latencyMs)
                    append(',').append(row.clockEpoch).append(',')
                    append(value.endpointRevision == revision && row.clockEpoch == epochs[value.endpointRevision])
                    append(',').append(id.wire).append(',').append(value.endpointRevision).append('\n')
                }
            }
        }
        require(output.toByteArray(Charsets.UTF_8).size <= TargetBackupCodec.MAX_PLAINTEXT_BYTES)
        return output
    }

    fun isLegacy(text: String): Boolean = text.lineSequence().firstOrNull() != HEADER

    fun parse(text: String): Map<TargetId, List<TargetObservation>> {
        require(text.length <= TargetBackupCodec.MAX_PLAINTEXT_BYTES &&
            text.toByteArray(Charsets.UTF_8).size <= TargetBackupCodec.MAX_PLAINTEXT_BYTES)
        val lines = text.lineSequence().iterator()
        require(lines.hasNext())
        if (lines.next() != HEADER) return mapOf(TargetId.TELEGRAM to CsvImport.parse(text).map {
            TargetObservation(if (it.kind == "TG_DOWN") it.copy(kind = "DOWN") else it)
        })
        val parsed = linkedMapOf<TargetId, MutableList<Pair<TargetObservation, Boolean>>>()
        var count = 0
        while (lines.hasNext()) {
            val line = lines.next()
            if (line.isEmpty() && !lines.hasNext()) break
            require(line.length <= MAX_LINE && ++count <= TargetBackupCodec.MAX_OBSERVATIONS)
            val fields = line.split(',')
            require(fields.size == FIELD_COUNT)
            val id = requireNotNull(TargetId.fromWire(fields[TARGET_COLUMN]))
            require(fields[ENDPOINT_COLUMN].matches(Regex("[0-9]+")))
            val revision = requireNotNull(fields[ENDPOINT_COLUMN].toLongOrNull())
            val (row, active) = CsvImport.record(fields.take(LEGACY_FIELDS).mapIndexed { index, field ->
                if (index == 2) Timeline.canonicalKind(field) else field }, true)
            val own = parsed.getOrPut(id) { mutableListOf() }
            require(own.size < TargetBackupCodec.rowLimit(id))
            own += TargetObservation(if (row.kind == "TG_DOWN") row.copy(kind = "DOWN") else row, revision) to active
        }
        require(parsed.isNotEmpty())
        return parsed.mapValues { (_, values) -> validate(values) }
    }

    private fun validate(values: List<Pair<TargetObservation, Boolean>>): List<TargetObservation> {
        val revision = values.maxOf { it.first.endpointRevision }
        return values.groupBy { it.first.endpointRevision }.flatMap { (id, cohort) ->
            val epoch = cohort.maxOf { it.first.row.clockEpoch }
            require(cohort.all { (value, active) -> active == (id == revision && value.row.clockEpoch == epoch) })
            val rows = cohort.map { it.first }.filter { it.row.kind != "UNKNOWN" || it.row.isEpochMetadata }
            if (rows.none { it.row.clockEpoch == epoch }) {
                val last = cohort.maxBy { it.first.row.at }.first
                rows + last.copy(row = last.row.copy(until = last.row.at, kind = "UNKNOWN", latencyMs = -1))
            } else rows
        }.also { rows -> rows.forEach(TargetBackupCodec::validateRow) }
    }

    private fun window(source: List<TargetObservation>, from: Long, until: Long): List<TargetObservation> {
        if (source.isEmpty()) return listOf(TargetObservation(Observation(until, until, "UNKNOWN")))
        return source.groupBy { it.endpointRevision }.flatMap { (revision, cohort) ->
            val epoch = cohort.maxOf { it.row.clockEpoch }
            val kept = cohort.mapNotNull { value ->
                val row = value.row
                when {
                    row.clockEpoch != epoch || row.isEpochMetadata -> value
                    row.at >= until || row.until <= from -> null
                    else -> value.copy(row = row.copy(at = maxOf(row.at, from), until = minOf(row.until, until)))
                }
            }
            if (kept.any { it.row.clockEpoch == epoch }) kept else
                kept + TargetObservation(Observation(until, until, "UNKNOWN", clockEpoch = epoch), revision)
        }
    }
}
