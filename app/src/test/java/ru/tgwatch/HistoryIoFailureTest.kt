package ru.tgwatch

import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class HistoryIoFailureTest {
    @Test fun repairDebtSurvivesFsyncFailure() = failedReplacement("file")
    @Test fun repairDebtSurvivesRenameFailure() = failedReplacement("rename")
    @Test fun repairDebtSurvivesDirectoryFsyncFailure() = failedReplacement("directory")

    private fun failedReplacement(stage: String) {
        val dir = Files.createTempDirectory("history-io-failure").toFile()
        try {
            val file = File(dir, "history.csv")
            val old = listOf(Observation(0, 1, "OK"))
            val next = old + Observation(1, 2, "TG_DOWN")
            HistoryStorage.write(file, old)
            val policy = HistoryCompaction().apply { savedReplacement(0) }
            val io = object : HistoryFileOperations by SystemHistoryFileOperations {
                override fun syncFile(descriptor: FileDescriptor) {
                    if (stage == "file") throw IOException("Injected file fsync failure")
                    SystemHistoryFileOperations.syncFile(descriptor)
                }
                override fun replace(source: File, destination: File) {
                    if (stage == "rename") throw IOException("Injected atomic replacement failure")
                    SystemHistoryFileOperations.replace(source, destination)
                }
                override fun syncDirectory(directory: File) {
                    if (stage == "directory") throw IOException("Injected directory fsync failure")
                    SystemHistoryFileOperations.syncDirectory(directory)
                }
            }
            try {
                HistoryStorage.write(file, next, io)
                fail("The failed durable step must propagate")
            } catch (_: IOException) { policy.failedWrite() }
            assertTrue(policy.needsReplacement(1, 2, file.length()))
            assertEquals(if (stage == "directory") next else old, HistoryStorage.read(file))
            HistoryStorage.write(file, next)
            policy.savedReplacement(2)
            assertFalse(policy.needsReplacement(3, 2, file.length()))
            assertEquals(next, HistoryStorage.read(file))
        } finally { dir.deleteRecursively() }
    }

    @Test fun failedAppendFsyncRequiresDurableRepair() {
        val dir = Files.createTempDirectory("history-append-failure").toFile()
        try {
            val file = File(dir, "history.csv")
            val rows = listOf(Observation(0, 1, "OK"), Observation(1, 2, "TG_DOWN"))
            HistoryStorage.write(file, rows.take(1))
            val io = object : HistoryFileOperations by SystemHistoryFileOperations {
                override fun syncFile(descriptor: FileDescriptor) { throw IOException("Injected append fsync") }
            }
            assertTrue(runCatching { HistoryStorage.append(file, rows.last(), true, io) }.isFailure)
            HistoryStorage.write(file, rows)
            assertEquals(rows, HistoryStorage.read(file))
        } finally { dir.deleteRecursively() }
    }
}
