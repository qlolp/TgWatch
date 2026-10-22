package ru.tgwatch

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RegionalJournalSessionTest {
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
