package ru.tgwatch

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RegionalJournalSessionTest {
    @Test fun aMultiFileClearFinishesBeforeANewGenerationReaderCanObserveComparisons() {
        val directory = Files.createTempDirectory("regional-multiple-files").toFile()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val readDone = java.util.concurrent.CountDownLatch(1)
        val readStarted = java.util.concurrent.CountDownLatch(1)
        try {
            val journal = RegionalJournalSession(RegionalJournalStorage(File(directory, "events")))
            val comparisons = File(directory, "pairs").apply { writeText("old pairs") }
            val clearer = Thread {
                journal.clear {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    comparisons.writeText("")
                }
            }.apply { start() }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val observed = java.util.concurrent.atomic.AtomicReference<String>()
            val reader = Thread {
                val ticket = journal.generation
                readStarted.countDown()
                journal.withGeneration(ticket) { observed.set(comparisons.readText()); true }
                readDone.countDown()
            }.apply { start() }
            assertTrue(readStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(readDone.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            release.countDown()
            clearer.join(5_000)
            reader.join(5_000)
            assertFalse(clearer.isAlive)
            assertFalse(reader.isAlive)
            assertEquals("", observed.get())
        } finally { release.countDown(); directory.deleteRecursively() }
    }

    @Test fun aComparisonWriteCapturedBeforeRestoreCannotCreateAFileAfterTheFenceMoves() {
        val directory = Files.createTempDirectory("regional-other-file-fence").toFile()
        try {
            val journal = RegionalJournalSession(RegionalJournalStorage(File(directory, "events")))
            val ticket = journal.generation
            val comparison = File(directory, "comparison")
            journal.clear()
            assertFalse(journal.withGeneration(ticket) { comparison.writeText("stale"); true })
            assertFalse(comparison.exists())
            assertTrue(journal.withGeneration(journal.generation) { comparison.writeText("fresh"); true })
            assertEquals("fresh", comparison.readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun aReadErrorCanRecoverOnBackgroundRefreshWithoutDeletingHealthyRows() {
        val directory = Files.createTempDirectory("regional-read-retry").toFile()
        try {
            val file = File(directory, "journal").apply { writeText("temporarily unreadable") }
            val storage = RegionalJournalStorage(file)
            val journal = RegionalJournalSession(storage)
            journal.load(1_000)
            assertEquals(RegionalJournalError.READ, journal.snapshot.error)
            val row = RegionalJournalEntry(RegionalEventKind.NETWORK, 1_000,
                "0123456789abcdef0123456789abcdef", RegionalRoute.WIFI)
            storage.write(listOf(row))
            assertTrue(journal.prune(journal.generation, 1_001))
            assertNull(journal.snapshot.error)
            assertEquals(listOf(row), journal.snapshot.rows)
        } finally { directory.deleteRecursively() }
    }

    @Test fun openingExpiredHistoryRemovesExpiredRowsFromDiskAsWellAsTheView() {
        val directory = Files.createTempDirectory("regional-expiry").toFile()
        try {
            val storage = RegionalJournalStorage(File(directory, "journal"))
            storage.write(listOf(RegionalJournalEntry(RegionalEventKind.NETWORK, 1,
                "0123456789abcdef0123456789abcdef", RegionalRoute.WIFI)))
            val journal = RegionalJournalSession(storage)
            journal.load(RegionalJournal.RETENTION_MS + 2)
            assertTrue(journal.snapshot.rows.isEmpty())
            assertTrue(storage.read().isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun failedClearCannotMergeDeletedOldRowsIntoTheNextSuccessfulWrite() {
        val directory = Files.createTempDirectory("regional-clear-failure").toFile()
        var fail = false
        val io = object : HistoryFileOperations by SystemHistoryFileOperations {
            override fun replace(source: File, destination: File) {
                if (fail) throw java.io.IOException("fixture")
                SystemHistoryFileOperations.replace(source, destination)
            }
        }
        try {
            val storage = RegionalJournalStorage(File(directory, "journal"), io)
            val journal = RegionalJournalSession(storage)
            val old = RegionalJournalEntry(RegionalEventKind.NETWORK, 1_000,
                "0123456789abcdef0123456789abcdef", RegionalRoute.WIFI)
            val fresh = old.copy(at = 2_000, scope = "fedcba9876543210fedcba9876543210")
            assertTrue(journal.append(journal.generation, old, 1_000))
            fail = true
            assertThrows(java.io.IOException::class.java) { journal.clear() }
            fail = false
            assertTrue(journal.append(journal.generation, fresh, 2_000))
            assertEquals(listOf(fresh), storage.read())
        } finally { directory.deleteRecursively() }
    }

    @Test fun clearingFencesQueuedWritesAndReopeningCannotResurrectThem() {
        val directory = Files.createTempDirectory("regional-fence").toFile()
        try {
            val file = File(directory, "journal")
            val journal = RegionalJournalSession(RegionalJournalStorage(file))
            journal.load(1_000)
            val ticket = journal.generation
            val row = RegionalJournalEntry(RegionalEventKind.PUBLIC, 1_000,
                "0123456789abcdef0123456789abcdef", RegionalRoute.VPN,
                service = RegionalService.DOTS, status = RegionalStatus.AUTH_REQUIRED, httpCode = 401)
            assertTrue(journal.append(ticket, row, 1_000))
            journal.clear()
            assertTrue(journal.generation > ticket)
            val afterClear = journal.generation
            assertFalse(journal.clearIfCurrent(ticket))
            assertEquals(afterClear, journal.generation)
            assertFalse(journal.append(ticket, row.copy(at = 1_001), 1_001))
            assertTrue(journal.snapshot.rows.isEmpty())
            val reopened = RegionalJournalSession(RegionalJournalStorage(file))
            reopened.load(1_001)
            assertTrue(reopened.snapshot.rows.isEmpty())
            assertNull(reopened.snapshot.error)
        } finally { directory.deleteRecursively() }
    }

    @Test fun corruptedStorageHasAnExplicitErrorAndCanBeCleared() {
        val directory = Files.createTempDirectory("regional-error").toFile()
        try {
            val file = File(directory, "journal").apply { writeText("broken") }
            val journal = RegionalJournalSession(RegionalJournalStorage(file))
            journal.load(1_000)
            assertEquals(RegionalJournalError.READ, journal.snapshot.error)
            journal.clear()
            assertNull(journal.snapshot.error)
            assertEquals(emptyList<RegionalJournalEntry>(), RegionalJournalStorage(file).read())
        } finally { directory.deleteRecursively() }
    }
}
