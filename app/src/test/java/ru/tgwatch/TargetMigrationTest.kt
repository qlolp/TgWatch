package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.file.Files

class TargetMigrationTest {
    private class Crash : Error()
    private class Faults(private val before: Int = -1, private val after: Int = -1) : HistoryFileOperations {
        var count = 0
        private fun step(action: () -> Unit) {
            count++
            if (count == before) throw IOException("injected durable failure")
            action()
            if (count == after) throw Crash()
        }
        override fun syncFile(descriptor: FileDescriptor) = step { descriptor.sync() }
        override fun replace(source: File, destination: File) = step {
            SystemHistoryFileOperations.replace(source, destination)
        }
        override fun syncDirectory(directory: File) = step {
            SystemHistoryFileOperations.syncDirectory(directory)
        }
    }
    private fun data(marker: Long) = TargetBackupSnapshot(marker, mapOf("interval_sec" to 30),
        TargetId.entries.map(TargetDefinition::default), TargetId.entries.associateWith { id ->
            listOf(TargetObservation(Observation(marker, marker + 1, if (id == TargetId.TELEGRAM) "OK" else "DOWN",
                marker, marker), marker))
        }, listOf("generation $marker"))

    @Test fun crashAtEveryMigrationAndRestoreDurableStep() {
        val root = Files.createTempDirectory("targets-crash").toFile()
        try {
            val counter = Faults()
            TargetStorage.open(File(root, "count"), counter).replaceGeneration(data(2))
            for (step in 1..counter.count) {
                val failureDir = File(root, "failure-$step")
                TargetStorage.open(failureDir).replaceGeneration(data(1))
                try { TargetStorage.open(failureDir, Faults(before = step)).replaceGeneration(data(2)); fail() }
                catch (_: IOException) { }
                assertEquals("reported failure at $step must keep old data", data(1),
                    TargetStorage.open(failureDir).snapshot())

                val crashDir = File(root, "crash-$step")
                TargetStorage.open(crashDir).replaceGeneration(data(1))
                try { TargetStorage.open(crashDir, Faults(after = step)).replaceGeneration(data(2)); fail() }
                catch (_: Crash) { }
                val reopened = TargetStorage.open(crashDir).snapshot()
                assertTrue("crash at $step must select a whole generation", reopened == data(1) || reopened == data(2))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun migrationKeepsLegacySourceAndNeverReimportsAfterCommittedGeneration() {
        val root = Files.createTempDirectory("targets-migrate").toFile()
        try {
            val legacy = File(root, "history-v2.csv")
            HistoryStorage.write(legacy, listOf(Observation(10, 20, "TG_DOWN", 5, 3)))
            val destination = File(root, "targets")
            assertEquals(MigrationResult.READY, TargetMigration.migrate(legacy, destination))
            assertTrue(legacy.exists())
            val store = TargetStorage.open(destination)
            val migrated = store.snapshot()
            assertEquals(listOf(TargetObservation(Observation(10, 20, "DOWN", 5, 3))),
                migrated.observations[TargetId.TELEGRAM])
            assertTrue(migrated.targets.filter { it.id != TargetId.TELEGRAM }.none { it.enabled })
            store.replaceGeneration(data(2))
            assertEquals(MigrationResult.READY, TargetMigration.migrate(legacy, destination))
            assertEquals(data(2), TargetStorage.open(destination).snapshot())
        } finally { root.deleteRecursively() }
    }

    @Test fun failedMigrationDoesNotPublishEmptyDataAndCanRetry() {
        val root = Files.createTempDirectory("targets-migrate-fail").toFile()
        try {
            val legacy = File(root, "legacy")
            HistoryStorage.write(legacy, listOf(Observation(10, 20, "OK")))
            val destination = File(root, "targets")
            assertEquals(MigrationResult.RETRY, TargetMigration.migrate(legacy, destination, Faults(before = 3)))
            assertTrue(legacy.exists())
            assertFalse(TargetStorage.open(destination).isInitialized)
            assertEquals(MigrationResult.READY, TargetMigration.migrate(legacy, destination))
            assertEquals(1, TargetStorage.open(destination).read(TargetId.TELEGRAM).size)
        } finally { root.deleteRecursively() }
    }

    @Test fun journalAppendRetainsEndpointCohortsAndIndependentClockEpochs() {
        val root = Files.createTempDirectory("targets-journal").toFile()
        try {
            val store = TargetStorage.open(root)
            store.replaceGeneration(data(100))
            store.record(TargetId.CUSTOM, Observation(200, 300, "OK", 7), 101)
            store.record(TargetId.CUSTOM, Observation(150, 250, "DOWN", -1), 100)
            store.record(TargetId.CUSTOM, Observation(190, 290, "OK", 8), 101)
            val rows = TargetStorage.open(root).read(TargetId.CUSTOM)
            assertEquals(100, rows.last { it.endpointRevision == 100L }.row.clockEpoch)
            assertEquals(1, rows.last { it.endpointRevision == 101L }.row.clockEpoch)
            assertEquals(100, store.read(TargetId.TELEGRAM).single().row.at)
            assertEquals(1, Timeline.stats(rows.filter { it.endpointRevision == 101L }.map { it.row }, 0, 1000).checks)
        } finally { root.deleteRecursively() }
    }

    @Test fun invalidManifestAndOversizedJournalsAreUnavailableInsteadOfEmpty() {
        val root = Files.createTempDirectory("targets-invalid").toFile()
        try {
            val store = TargetStorage.open(root)
            store.replaceGeneration(data(1))
            store.journalFile(TargetId.MATRIX).writeText("broken")
            try { store.read(TargetId.MATRIX); fail() } catch (_: IOException) { }
            File(root, "active-generation").writeText("TGWTARGET1\n../../outside\n")
            try { TargetStorage.open(root); fail() } catch (_: IOException) { }
        } finally { root.deleteRecursively() }
    }
}
