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
                StackTraceElement("ru.tgwatch.MonitorService", "check", "MonitorService.kt", it + 1)
            }
        }

    @Test fun storesTypeThreadAndFramesButNeverTheMessage() {
        val dir = directory()
        CrashJournal.record(dir, Thread.currentThread(), error(), 1_000)
        val summary = CrashJournal.summary(dir)
        assertEquals(1, summary.retained)
        val last = requireNotNull(summary.last)
        assertEquals(1_000L, last.atMs)
        assertEquals("java.lang.IllegalStateException", last.type)
        assertEquals(Thread.currentThread().name, last.thread)
        assertEquals(3, last.frames.size)
        assertTrue(last.frames.first().startsWith("ru.tgwatch.MonitorService.check"))
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
}
