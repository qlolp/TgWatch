package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class HistoryStorageTest {
    @Test fun corruptLineDoesNotEraseOtherObservations() {
        val dir = Files.createTempDirectory("tg-history").toFile()
        try {
            val file = java.io.File(dir, "history-v2.csv")
            file.writeText("1000,2000,OK,15\ntruncated\n2000,3000,TG_DOWN,-1\n")
            assertEquals(2, HistoryStorage.read(file).size)
        } finally { dir.deleteRecursively() }
    }
    @Test fun mergePreservesBothBootAndExistingDataWithoutDuplicates() {
        val old = Observation(1000, 2000, "OK", 15)
        val boot = Observation(2000, 3000, "TG_DOWN")
        assertEquals(listOf(old, boot), HistoryStorage.merge(listOf(old), listOf(old, boot)))
    }
    @Test fun atomicWriteRoundTrips() {
        val dir = Files.createTempDirectory("tg-history").toFile()
        try {
            val file = java.io.File(dir, "history-v2.csv")
            val entries = listOf(Observation(1000, 2000, "PARTIAL", 15))
            HistoryStorage.write(file, entries)
            assertEquals(entries, HistoryStorage.read(file))
            HistoryStorage.write(file, entries + Observation(2000, 3000, "UNKNOWN"))
            assertEquals(2, HistoryStorage.read(file).size)
        } finally { dir.deleteRecursively() }
    }
    @Test fun csvIncludesUnknownGapsAndUtcTimestamps() {
        val csv = Timeline.csv(listOf(Observation(0, 1000, "OK", 10)), 0, 2000)
        assertTrue(csv.contains("1970-01-01T00:00:00.000Z"))
        assertTrue(csv.contains("UNKNOWN"))
        assertTrue(csv.startsWith("from_utc,until_utc,status,duration_ms,latency_ms"))
    }
}
