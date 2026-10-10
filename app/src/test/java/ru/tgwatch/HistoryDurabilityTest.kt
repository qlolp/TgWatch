package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HistoryDurabilityTest {
    private fun replace(file: File, rows: List<Observation>, cutoff: Long): List<Observation> = HistoryStorage.replace(file, rows, cutoff)
    @Test fun replacementPrunesExpiredRowsAndRoundTripsBeforeReturning() {
        val dir = Files.createTempDirectory("history-restore").toFile()
        try {
            val file = File(dir, "history.csv")
            val recent = Observation(2000, 3000, "TG_DOWN")
            val result = replace(file, listOf(Observation(0, 1000, "OK"), recent), 1000)
            assertEquals(listOf(recent), result)
            assertEquals(result, HistoryStorage.read(file))
        } finally { dir.deleteRecursively() }
    }
    @Test fun invalidReplacementDoesNotChangeExistingFile() {
        val dir = Files.createTempDirectory("history-restore").toFile()
        try {
            val file = File(dir, "history.csv")
            val original = listOf(Observation(0, 1000, "OK"))
            HistoryStorage.write(file, original)
            val bad = listOf(Observation(-1, 1000, "OK"), Observation(0, 600001, "OK"), Observation(0, 0, "OK"),
                Observation(0, 1, "BAD"), Observation(0, 1, "OK", -2), Observation(0, 1, "OK", clockEpoch = -1))
            for (row in bad) {
                try { replace(file, original + row, Long.MIN_VALUE); fail("Invalid replacement accepted") } catch (_: IllegalArgumentException) {}
                assertEquals(original, HistoryStorage.read(file))
            }
            try { replace(file, List(65001) { original.first() }, 0); fail("Oversized replacement accepted") } catch (_: IllegalArgumentException) {}
        } finally { dir.deleteRecursively() }
    }
    @Test fun failedReplacementPropagatesInsteadOfReturningAnUnsavedSnapshot() {
        val dir = Files.createTempDirectory("history-restore").toFile()
        try {
            val blocked = File(dir, "not-a-file").apply { mkdir() }
            try { replace(blocked, listOf(Observation(0, 1000, "OK")), 0); fail("Failed write swallowed") } catch (_: java.io.IOException) {}
            assertTrue(blocked.isDirectory)
        } finally { dir.deleteRecursively() }
    }
    @Test fun appendPolicySyncsFirstAppendStatusChangesAndElapsedMinute() {
        val policy = HistoryStorage.AppendSyncPolicy()
        fun sync(kind: String, elapsed: Long) = policy.needsSync(kind, elapsed)
        fun append(kind: String, elapsed: Long) { val required = sync(kind, elapsed); policy.saved(kind, elapsed, required) }
        assertTrue(sync("OK", 1000)); append("OK", 1000)
        assertFalse(sync("OK", 60999)); assertTrue(sync("OK", 61000))
        assertTrue(sync("TG_DOWN", 2000)); append("TG_DOWN", 2000)
        assertFalse(sync("TG_DOWN", 3000)); assertTrue(sync("OK", 3000))
        assertTrue(sync("TG_DOWN", 1)) // Monotonic clock reset is conservative.
    }
}
