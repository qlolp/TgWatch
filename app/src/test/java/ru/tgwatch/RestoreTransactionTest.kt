package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RestoreTransactionTest {
    private fun withPending(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("tgwatch-restore-test").toFile()
        try { test(File(directory, "pending.json")) } finally { directory.deleteRecursively() }
    }

    @Test fun stagePersistsPayloadBeforeAnyApplication() = withPending { file ->
        RestoreTransaction(file).stage(byteArrayOf(1, 2, 3))
        assertTrue(file.exists())
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
    }

    @Test fun successfulReplayDeletesJournalAndRunsOnlyOnce() = withPending { file ->
        file.writeBytes(byteArrayOf(3, 4))
        var applications = 0
        val transaction = RestoreTransaction(file)
        assertTrue(transaction.recover { payload ->
            assertArrayEquals(byteArrayOf(3, 4), payload)
            applications++
        })
        assertFalse(file.exists())
        assertFalse(transaction.recover { applications++ })
        assertEquals(1, applications)
    }

    @Test fun failedApplicationRetainsJournalForNextProcess() = withPending { file ->
        file.writeBytes(byteArrayOf(5, 6))
        var threw = false
        try { RestoreTransaction(file).recover { throw IllegalStateException("interrupted") } }
        catch (_: IllegalStateException) { threw = true }
        assertTrue(threw)
        assertArrayEquals(byteArrayOf(5, 6), file.readBytes())
        assertTrue(RestoreTransaction(file).recover { assertEquals(2, it.size) })
        assertFalse(file.exists())
    }

    @Test fun stagingAnotherRestoreCannotLosePendingWork() = withPending { file ->
        file.writeBytes(byteArrayOf(7))
        var rejected = false
        try { RestoreTransaction(file).stage(byteArrayOf(8)) } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
        assertArrayEquals(byteArrayOf(7), file.readBytes())
    }

    @Test fun oversizedStageWritesNothing() = withPending { file ->
        var rejected = false
        try { RestoreTransaction(file, 4).stage(ByteArray(5)) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertFalse(file.exists())
    }

    @Test fun oversizedJournalDoesNotApplyOrDisappear() = withPending { file ->
        file.writeBytes(ByteArray(5))
        var called = false
        var rejected = false
        try { RestoreTransaction(file, 4).recover { called = true } } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertFalse(called)
        assertTrue(file.exists())
    }

    @Test fun absentJournalDoesNotInvokeApplication() = withPending { file ->
        assertFalse(RestoreTransaction(file).recover { fail("No pending work") })
    }

    @Test fun emptyPayloadCannotStage() = withPending { file ->
        var rejected = false
        try { RestoreTransaction(file).stage(byteArrayOf()) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertFalse(file.exists())
    }
}
