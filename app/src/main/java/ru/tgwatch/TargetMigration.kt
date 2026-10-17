package ru.tgwatch

import java.io.File
import java.io.IOException

enum class MigrationResult { READY, RETRY, FAILED }

/** Never destroys the source; the committed manifest is the sole idempotence marker. */
object TargetMigration {
    fun migrate(legacy: File, destination: File,
        io: HistoryFileOperations = SystemHistoryFileOperations): MigrationResult = try {
        val storage = TargetStorage.open(destination, io)
        if (!storage.isInitialized) {
            val rows = HistoryFileReader.readCounted(legacy).rows.map {
                TargetObservation(if (it.kind == "TG_DOWN") it.copy(kind = "DOWN") else it)
            }
            val definitions = TargetId.entries.map(TargetDefinition::default)
            storage.replaceGeneration(TargetBackupSnapshot(0L, emptyMap(), definitions,
                TargetId.entries.associateWith { if (it == TargetId.TELEGRAM) rows else emptyList() }, emptyList()),
                supersedeLegacy = false)
        }
        MigrationResult.READY
    } catch (_: IOException) { MigrationResult.RETRY }
        catch (_: IllegalArgumentException) { MigrationResult.FAILED }
}
