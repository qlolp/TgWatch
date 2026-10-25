package ru.tgwatch

/** Portable public data only; credential URLs and live Android/service state have no fields here. */
data class TargetBackupSnapshot(val createdAtMs: Long, val globalSettings: Map<String, Any>,
    val targets: List<TargetDefinition>, val observations: Map<TargetId, List<TargetObservation>>,
    val log: List<String>)

object TargetBackupCodec {
    /**
     * v3 omitted history_retention; v4 writes it; v5 is written only when a backup holds an optional
     * custom HTTPS goal (custom2..custom5), so older readers reject exactly the backups they cannot represent.
     */
    const val FORMAT_VERSION = 5
    const val SINGLE_CUSTOM_FORMAT = 4
    const val READABLE_FORMAT_MIN = 3
    const val MAX_PLAINTEXT_BYTES = 24 * 1024 * 1024
    const val MAX_OBSERVATIONS = 142_000
    const val TELEGRAM_ROWS = 65_000
    const val EXTRA_ROWS = 11_000
    const val LEGACY_ENDPOINT_KEY = "extra_probe_custom_url"

    private val kinds = TargetStatus.entries.map { it.name }.toSet()

    fun rowLimit(id: TargetId): Int = if (id == TargetId.TELEGRAM) TELEGRAM_ROWS else EXTRA_ROWS

    fun formatVersion(targets: Collection<TargetId>): Int =
        if (targets.any { it.isOptional }) FORMAT_VERSION else SINGLE_CUSTOM_FORMAT

    fun encode(snapshot: TargetBackupSnapshot): ByteArray = checked {
        validate(snapshot)
        TargetBackupWriter.write(snapshot)
    }

    fun decode(bytes: ByteArray): TargetBackupSnapshot = checked {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PLAINTEXT_BYTES)
        TargetBackupReader(bytes).read().also(::validate)
    }

    internal fun validate(snapshot: TargetBackupSnapshot) {
        require(snapshot.createdAtMs >= 0L && snapshot.targets.size in 1..TargetId.entries.size)
        val ids = snapshot.targets.map { it.id }.toSet()
        require(ids.size == snapshot.targets.size && snapshot.observations.keys == ids)
        require(snapshot.observations.values.sumOf { it.size.toLong() } <= MAX_OBSERVATIONS)
        snapshot.observations.forEach { (id, rows) ->
            require(rows.size <= rowLimit(id))
            rows.forEach(::validateRow)
        }
        require(LEGACY_ENDPOINT_KEY !in snapshot.globalSettings)
        // Reuse the established public settings/Unicode/log rules with NO history object tree.
        BackupCodec.encode(BackupSnapshot(0, snapshot.globalSettings, emptyList(), snapshot.log)).fill(0)
    }

    internal fun validateRow(observation: TargetObservation) {
        val row = observation.row
        require(row.kind in kinds)
        require(observation.endpointRevision >= 0L)
        require(validObservation(if (row.kind == "DOWN") row.copy(kind = "TG_DOWN") else row))
    }

    private inline fun <T> checked(block: () -> T): T = try { block() }
        catch (_: Exception) { throw IllegalArgumentException("Invalid target backup") }
}
