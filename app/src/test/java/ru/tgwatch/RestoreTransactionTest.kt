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

    @Test fun validLegacyMigrationStagesConvertedJournalBeforeRemovingLegacy() = withPending { pending ->
        val legacy = File(pending.parentFile, "legacy.pending").apply { writeBytes(byteArrayOf(1, 2)) }
        val converted = byteArrayOf(7, 8, 9)

        assertTrue(RestoreTransaction(pending).migrateLegacy(legacy, { bytes ->
            assertArrayEquals(byteArrayOf(1, 2), bytes)
            converted
        }, { bytes ->
            assertArrayEquals(converted, bytes)
            assertTrue(legacy.exists())
            assertFalse(pending.exists())
        }))

        assertFalse(legacy.exists())
        assertArrayEquals(converted, pending.readBytes())
        assertFalse(File(pending.parentFile, pending.name + ".tmp").exists())
        assertTrue(RestoreTransaction(pending).recover { assertArrayEquals(converted, it) })
    }

    @Test fun interruptedConversionRetainsLegacyWithoutStaging() = withPending { pending ->
        val legacy = File(pending.parentFile, "legacy.pending").apply { writeBytes(byteArrayOf(1, 2)) }
        assertThrows(IllegalStateException::class.java) {
            RestoreTransaction(pending).migrateLegacy(legacy,
                { throw IllegalStateException("interrupted conversion") }, { fail("No converted payload") })
        }
        assertArrayEquals(byteArrayOf(1, 2), legacy.readBytes())
        assertFalse(pending.exists())
    }

    @Test fun existingValidatedJournalIsAuthoritativeOverLegacy() = withPending { pending ->
        val legacy = File(pending.parentFile, "legacy.pending").apply { writeBytes(byteArrayOf(1, 2)) }
        pending.writeBytes(byteArrayOf(9))
        var validated = false

        assertTrue(RestoreTransaction(pending).migrateLegacy(legacy,
            { fail("Existing journal must not be replaced"); byteArrayOf() }, { bytes ->
                assertArrayEquals(byteArrayOf(9), bytes)
                validated = true
            }))

        assertTrue(validated)
        assertFalse(legacy.exists())
        assertArrayEquals(byteArrayOf(9), pending.readBytes())
    }

    @Test fun invalidExistingJournalRetainsLegacyAndPendingPayload() = withPending { pending ->
        val legacy = File(pending.parentFile, "legacy.pending").apply { writeBytes(byteArrayOf(1, 2)) }
        pending.writeBytes(byteArrayOf(9))

        assertThrows(IllegalArgumentException::class.java) {
            RestoreTransaction(pending).migrateLegacy(legacy,
                { fail("Existing journal must remain authoritative"); byteArrayOf() },
                { throw IllegalArgumentException("invalid pending payload") })
        }

        assertArrayEquals(byteArrayOf(1, 2), legacy.readBytes())
        assertArrayEquals(byteArrayOf(9), pending.readBytes())
    }

    @Test fun absentLegacyDoesNotConvertOrValidate() = withPending { pending ->
        val legacy = File(pending.parentFile, "absent.pending")
        assertFalse(RestoreTransaction(pending).migrateLegacy(legacy,
            { fail("No legacy payload"); byteArrayOf() }, { fail("No migration to validate") }))
        assertFalse(pending.exists())
    }

    @Test fun invalidConvertedPayloadRetainsLegacyWithoutStaging() = withPending { pending ->
        val legacy = File(pending.parentFile, "legacy.pending").apply { writeBytes(byteArrayOf(1, 2)) }
        assertThrows(IllegalArgumentException::class.java) {
            RestoreTransaction(pending).migrateLegacy(legacy, { byteArrayOf(7) },
                { throw IllegalArgumentException("invalid converted payload") })
        }
        assertArrayEquals(byteArrayOf(1, 2), legacy.readBytes())
        assertFalse(pending.exists())
    }

    @Test fun failedStagingRetainsLegacy() = withPending { file ->
        val legacy = File(file.parentFile, "legacy.pending").apply { writeBytes(byteArrayOf(1, 2)) }
        val blocker = File(file.parentFile, "not-a-directory").apply { writeBytes(byteArrayOf(3)) }
        val pending = File(blocker, "pending.json")
        assertThrows(IllegalStateException::class.java) {
            RestoreTransaction(pending).migrateLegacy(legacy, { byteArrayOf(7) }, {})
        }
        assertArrayEquals(byteArrayOf(1, 2), legacy.readBytes())
        assertFalse(pending.exists())
    }

    @Test fun oversizedLegacyIsRejectedBeforeConversion() = withPending { pending ->
        val legacy = File(pending.parentFile, "legacy.pending").apply { writeBytes(ByteArray(5)) }
        assertThrows(IllegalArgumentException::class.java) {
            RestoreTransaction(pending, 4).migrateLegacy(legacy,
                { fail("Oversized legacy must not be converted"); byteArrayOf() }, {})
        }
        assertEquals(5L, legacy.length())
        assertFalse(pending.exists())
    }

    @Test fun oversizedConvertedPayloadIsRejectedBeforeValidationOrStaging() = withPending { pending ->
        val legacy = File(pending.parentFile, "legacy.pending").apply { writeBytes(byteArrayOf(1, 2)) }
        assertThrows(IllegalArgumentException::class.java) {
            RestoreTransaction(pending, 4).migrateLegacy(legacy, { ByteArray(5) },
                { fail("Oversized conversion must not be decoded") })
        }
        assertArrayEquals(byteArrayOf(1, 2), legacy.readBytes())
        assertFalse(pending.exists())
    }

    @Test fun oversizedExistingJournalRetainsLegacyWithoutValidation() = withPending { pending ->
        val legacy = File(pending.parentFile, "legacy.pending").apply { writeBytes(byteArrayOf(1, 2)) }
        pending.writeBytes(ByteArray(5))
        assertThrows(IllegalArgumentException::class.java) {
            RestoreTransaction(pending, 4).migrateLegacy(legacy,
                { fail("Existing journal must not be converted"); byteArrayOf() },
                { fail("Oversized pending must not be decoded") })
        }
        assertArrayEquals(byteArrayOf(1, 2), legacy.readBytes())
        assertEquals(5L, pending.length())
    }
}
