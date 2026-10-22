package ru.tgwatch

import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RegionalJournalStorageTest {
    @Test fun realDiskReopenPreservesEventsAndFailedReplacementPreservesOldBytes() {
        val directory = Files.createTempDirectory("regional-journal").toFile()
        try {
            val file = File(directory, "events")
            val row = RegionalJournalEntry(RegionalEventKind.PUBLIC, 1_000,
                "0123456789abcdef0123456789abcdef", RegionalRoute.WIFI,
                service = RegionalService.YOUTUBE, status = RegionalStatus.WEB_REACHABLE, httpCode = 200)
            val storage = RegionalJournalStorage(file)
            storage.write(listOf(row))
            assertEquals(listOf(row), RegionalJournalStorage(file).read())
            val before = file.readBytes()
            val failing = object : HistoryFileOperations {
                override fun syncFile(descriptor: FileDescriptor) = descriptor.sync()
                override fun replace(source: File, destination: File) { throw IOException("fixture") }
                override fun syncDirectory(directory: File) = Unit
            }
            assertThrows(IOException::class.java) {
                RegionalJournalStorage(file, failing).write(listOf(row.copy(at = 2_000)))
            }
            assertArrayEquals(before, file.readBytes())
            assertEquals(listOf(row), storage.read())
            storage.write(emptyList())
            assertEquals(emptyList<RegionalJournalEntry>(), storage.read())
        } finally { directory.deleteRecursively() }
    }

    @Test fun corruptOrOversizedFilesAreNotSilentlyPresentedAsEmptyHistory() {
        val directory = Files.createTempDirectory("regional-journal-corrupt").toFile()
        try {
            val file = File(directory, "events")
            assertEquals(emptyList<RegionalJournalEntry>(), RegionalJournalStorage(file).read())
            file.writeText("TGRJ1\ninvalid\n")
            assertThrows(IllegalArgumentException::class.java) { RegionalJournalStorage(file).read() }
            file.writeBytes(ByteArray(RegionalJournalCodec.MAX_BYTES + 1))
            assertThrows(IllegalArgumentException::class.java) { RegionalJournalStorage(file).read() }
        } finally { directory.deleteRecursively() }
    }
}
