package ru.tgwatch

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashJournalTest {
    private fun directory(): File = Files.createTempDirectory("crash-journal").toFile()

    private fun error(frames: Int = 3, message: String = "SECRET_TOKEN user@example.org"): Throwable =
        IllegalStateException(message).apply {
            stackTrace = Array(frames) {
                StackTraceElement("ru.tgwatch.MonitorService", "onCreate", "MonitorService.kt", it + 1)
            }
        }

    @Test fun storesTypeThreadAndFramesButNeverTheMessage() {
        val dir = directory()
        CrashJournal.record(dir, Thread({}, "main"), error(), 1_000)
        val summary = CrashJournal.summary(dir)
        assertEquals(1, summary.retained)
        val last = requireNotNull(summary.last)
        assertEquals(1_000L, last.atMs)
        assertEquals("java.lang.IllegalStateException", last.type)
        assertEquals("main", last.thread)
        assertEquals(3, last.frames.size)
        assertTrue(last.frames.first().startsWith("ru.tgwatch.MonitorService.onCreate"))
        val raw = File(dir, "crashes-v1.txt").readText()
        assertFalse(raw.contains("SECRET_TOKEN"))
        assertFalse(raw.contains("example.org"))
    }

    @Test fun keepsOnlyTheNewestRecords() {
        val dir = directory()
        repeat(8) { CrashJournal.record(dir, Thread.currentThread(), error(frames = 1), 1_000L + it) }
        val records = CrashJournal.read(dir)
        assertEquals(5, records.size)
        assertEquals(1_003L, records.first().atMs)
        assertEquals(1_007L, records.last().atMs)
        assertEquals(5, CrashJournal.summary(dir).retained)
    }

    @Test fun controlCharactersAndSeparatorsCannotBreakTheFormat() {
        val dir = directory()
        val thread = Thread({}, "bad\nname|#")
        CrashJournal.record(dir, thread, error(), 5)
        val records = CrashJournal.read(dir)
        assertEquals(1, records.size)
        assertTrue(records.single().thread.none { it == '\n' || it == '|' })
        assertFalse(File(dir, "crashes-v1.txt").readText().contains("\nname"))
    }

    @Test fun corruptOrOversizedEntriesReadAsNoRecords() {
        val dir = directory()
        val file = File(dir, "crashes-v1.txt")
        file.writeText("#not-a-number|main|java.lang.Error\n  at x")
        assertEquals(0, CrashJournal.summary(dir).retained)
        file.writeBytes(ByteArray(64 * 1024))
        assertEquals(0, CrashJournal.summary(dir).retained)
        assertTrue(CrashJournal.summary(File(dir, "missing")).last == null)
    }

    @Test fun journalStaysWithinItsByteBudget() {
        val dir = directory()
        val frame = "x".repeat(7_000)
        repeat(5) { index ->
            CrashJournal.record(dir, Thread.currentThread(), error().apply {
                stackTrace = arrayOf(StackTraceElement(frame, "check", "MonitorService.kt", index))
            }, 1_000L + index)
        }
        val size = File(dir, "crashes-v1.txt").length()
        assertTrue("journal grew to $size bytes", size <= 32 * 1024)
        assertTrue(CrashJournal.read(dir).isNotEmpty())
    }

    @Test fun anUnusableDirectoryIsDroppedWithoutThrowing() {
        val blocked = File(directory(), "blocked")
        blocked.writeText("not a directory")
        CrashJournal.record(blocked, Thread.currentThread(), error(), 1_000)
        assertFalse(File(blocked, "crashes-v1.txt").exists())
    }

    @Test fun privateThreadNamesAndSourcePathsNeverReachDisk() {
        val dir = directory()
        val secret = "SECRET_THREAD_TOKEN"
        val failure = error().apply {
            stackTrace = arrayOf(StackTraceElement("ru.tgwatch.MonitorService", "onCreate",
                "/private/$secret/document.kt", 42))
        }
        CrashJournal.record(dir, Thread({}, "https://private.example/$secret"), failure, 1_000)
        val raw = File(dir, "crashes-v1.txt").readText()
        assertFalse(raw, raw.contains(secret))
        assertFalse(raw, raw.contains("private.example"))
        assertFalse(raw, raw.contains("/private/"))
        assertEquals("other", CrashJournal.read(dir).single().thread)
        assertTrue(CrashJournal.read(dir).single().frames.single().startsWith("ru.tgwatch.MonitorService.onCreate"))
    }

    @Test fun legacyJournalMetadataIsRedactedWhenRead() {
        val dir = directory()
        File(dir, "crashes-v1.txt").writeText("#1000|https://private.example/SECRET_LEGACY|java.lang.Error\n" +
            "  at ru.tgwatch.MonitorService.check(/private/SECRET_LEGACY/document.kt:42)")
        val record = CrashJournal.read(dir).single()
        assertEquals("other", record.thread)
        assertFalse(record.frames.toString(), record.frames.toString().contains("SECRET_LEGACY"))
    }

    @Test fun fabricatedIdentifiersCannotSmuggleSecretsThroughAllowedNamespaces() {
        val dir = directory()
        val failure = error().apply {
            stackTrace = arrayOf(
                StackTraceElement("ru.tgwatch.SECRET_FRAME_TYPE", "check", "Source.kt", 1),
                StackTraceElement("ru.tgwatch.MonitorService", "SECRET_FRAME_METHOD", "MonitorService.kt", 2))
        }
        CrashJournal.record(dir, Thread({}, "main"), failure, 1_000)
        val raw = File(dir, "crashes-v1.txt").readText()
        assertFalse(raw, raw.contains("SECRET_FRAME_TYPE"))
        assertFalse(raw, raw.contains("SECRET_FRAME_METHOD"))
    }
}
