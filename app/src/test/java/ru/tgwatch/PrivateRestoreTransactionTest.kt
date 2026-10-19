package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.file.Files

class PrivateRestoreTransactionTest {
    @Test fun privateMetadataBindsReplayToItsGenerationAndPreservesUnimportedReviewFlags() {
        val root = Files.createTempDirectory("private-restore-metadata").toFile()
        try {
            val marker = File(root, "dp/id")
            val credentials = File(root, "cp")
            val metadata = PrivateRestoreMetadata(setOf(TargetId.CUSTOM), "a".repeat(32))
            PrivateRestoreTransaction(marker, credentials).stage(byteArrayOf(1), null, metadata)
            assertFalse(marker.readText().contains("custom"))
            assertTrue(PrivateRestoreTransaction(marker, credentials).recoverWithMetadata { _, _, restored ->
                assertEquals(metadata, restored)
            })
        } finally { root.deleteRecursively() }
    }

    @Test fun oldPrivateJournalDefaultsToReviewingAllTargets() {
        val root = Files.createTempDirectory("private-restore-old-metadata").toFile()
        try {
            val marker = File(root, "dp/id")
            val credentials = File(root, "cp")
            val transaction = PrivateRestoreTransaction(marker, credentials)
            transaction.stage(byteArrayOf(1), null)
            File(credentials, marker.readText() + "/metadata").delete()
            assertTrue(transaction.recoverWithMetadata { _, _, metadata ->
                assertEquals(TargetId.entries.toSet(), metadata.reviewTargets)
                assertNull(metadata.baseGenerationId)
            })
        } finally { root.deleteRecursively() }
    }
    private class Crash : Error()
    private class Cuts(private val before: Int = -1, private val after: Int = -1) : HistoryFileOperations {
        var count = 0
        private fun boundary(action: () -> Unit) {
            count++
            if (count == before) throw IOException("injected private journal failure")
            action()
            if (count == after) throw Crash()
        }
        override fun syncFile(descriptor: FileDescriptor) = boundary { descriptor.sync() }
        override fun replace(source: File, destination: File) = boundary {
            SystemHistoryFileOperations.replace(source, destination)
        }
        override fun syncDirectory(directory: File) = boundary {
            SystemHistoryFileOperations.syncDirectory(directory)
        }
    }

    @Test fun everyPrivateStagingDurableCutEitherKeepsNoCommitOrAReplayableIdOnlyCommit() {
        val root = Files.createTempDirectory("private-restore-cuts").toFile()
        val payload = "validated public payload".toByteArray()
        val secret = "https://example.org/credential-path"
        try {
            val count = Cuts()
            PrivateRestoreTransaction(File(root, "count/dp/id"), File(root, "count/cp"), count).stage(payload, secret)
            for (step in 1..count.count) for (crash in listOf(false, true)) {
                val directory = File(root, "cut-$step-$crash")
                val marker = File(directory, "dp/id")
                val credentials = File(directory, "cp")
                val faults = if (crash) Cuts(after = step) else Cuts(before = step)
                try { PrivateRestoreTransaction(marker, credentials, faults).stage(payload, secret); fail() }
                catch (_: IOException) { assertFalse(crash) }
                catch (_: Crash) { assertTrue(crash) }
                val committed = marker.exists()
                if (committed) assertTrue(marker.readText().matches(Regex("[a-f0-9]{32}")))
                var applied = false
                assertEquals(committed, PrivateRestoreTransaction(marker, credentials).recover { bytes, endpoint ->
                    assertArrayEquals(payload, bytes)
                    assertEquals(secret, endpoint)
                    applied = true
                })
                assertEquals(committed, applied)
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun privateConditionEnvelopeReplaysAcrossEveryDurableStagingCut() {
        val root = Files.createTempDirectory("private-condition-cuts").toFile()
        val secret = PrivateProbeConfigCodec.encode(PrivateProbeConfig("https://private.example/path",
            ru.tgwatch.probe.HttpProbeCondition(204, "private-field", "private-value")))
        try {
            val count = Cuts()
            PrivateRestoreTransaction(File(root, "count/dp/id"), File(root, "count/cp"), count)
                .stage(byteArrayOf(1), secret)
            for (step in 1..count.count) for (crash in listOf(false, true)) {
                val directory = File(root, "cut-$step-$crash")
                val marker = File(directory, "dp/id")
                val credentials = File(directory, "cp")
                val faults = if (crash) Cuts(after = step) else Cuts(before = step)
                try { PrivateRestoreTransaction(marker, credentials, faults).stage(byteArrayOf(1), secret); fail() }
                catch (_: IOException) { assertFalse(crash) }
                catch (_: Crash) { assertTrue(crash) }
                val committed = marker.exists()
                if (committed) assertTrue(marker.readText().matches(Regex("[a-f0-9]{32}")))
                assertEquals(committed, PrivateRestoreTransaction(marker, credentials).recover { _, value ->
                    assertEquals(secret, value)
                })
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun deviceProtectedMarkerContainsOnlyIdAndPrivatePayloadReplaysAfterReopen() {
        val root = Files.createTempDirectory("private-restore").toFile()
        try {
            val marker = File(root, "dp/pending")
            val credentials = File(root, "cp")
            val secret = "https://private.example/secret-path"
            val transaction = PrivateRestoreTransaction(marker, credentials)
            transaction.stage("public snapshot".toByteArray(), secret)
            assertTrue(marker.readText().matches(Regex("[a-f0-9]{32}")))
            assertFalse(marker.readText().contains(secret))
            var applications = 0
            assertTrue(PrivateRestoreTransaction(marker, credentials).recover { bytes, endpoint ->
                assertEquals("public snapshot", bytes.toString(Charsets.UTF_8))
                assertEquals(secret, endpoint)
                applications++
            })
            assertEquals(1, applications)
            assertFalse(marker.exists())
            assertTrue(credentials.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun failedReplayKeepsValidatedPrivateDataAndOrphansAreNeverApplied() {
        val root = Files.createTempDirectory("private-restore-retry").toFile()
        try {
            val marker = File(root, "dp/pending")
            val credentials = File(root, "cp")
            val transaction = PrivateRestoreTransaction(marker, credentials)
            transaction.stage(byteArrayOf(1, 2, 3), null)
            try { transaction.recover { _, _ -> error("blocked apply") }; fail() }
            catch (_: IllegalStateException) { }
            assertTrue(marker.exists())
            assertTrue(transaction.recover { bytes, endpoint ->
                assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
                assertNull(endpoint)
            })
            File(credentials, "a".repeat(32)).mkdirs()
            assertFalse(transaction.recover { _, _ -> fail("An uncommitted orphan must not replay") })
        } finally { root.deleteRecursively() }
    }

    @Test fun invalidMarkerCannotReadOutsideCredentialDirectory() {
        val root = Files.createTempDirectory("private-restore-path").toFile()
        try {
            val marker = File(root, "pending").apply { writeText("../../elsewhere") }
            try { PrivateRestoreTransaction(marker, File(root, "cp")).recover { _, _ -> fail() }; fail() }
            catch (_: IllegalArgumentException) { }
            assertTrue(marker.exists())
        } finally { root.deleteRecursively() }
    }
}
