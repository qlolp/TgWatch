package ru.tgwatch

/** Portable user configuration and records; device/service state is deliberately absent. */
data class BackupSnapshot(
    val createdAt: Long,
    val settings: Map<String, Any>,
    val observations: List<Observation>,
    val log: List<String>,
)
