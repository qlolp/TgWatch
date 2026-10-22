package ru.tgwatch

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RegionalComparisonTest {
    private val connection = RegionalConnection("0123456789abcdef0123456789abcdef", RegionalRoute.VPN, "DE", 900)
    private val services = setOf(RegionalService.GEMINI, RegionalService.YOUTUBE)
    private fun collector(label: String = "Home") = RegionalComparisonCollector(100, connection, services, label, 1_000)
    private fun row(service: RegionalService = RegionalService.GEMINI, at: Long = 1_010) =
        RegionalJournalEntry(RegionalEventKind.PUBLIC, at, connection.token, connection.route,
            "DE", 900, service, RegionalStatus.WEB_REACHABLE, httpCode = 200)

    @Test fun oldCachedAndWrongScopeResultsCannotSatisfyAFreshComparison() {
        val capture = collector()
        assertFalse(capture.offer(row(), 99, 120, connection, services))
        assertFalse(capture.offer(row(), 100, 120, connection, services))
        assertFalse(capture.offer(row().copy(scope = "fedcba9876543210fedcba9876543210"),
            110, 120, connection, services))
        assertFalse(capture.offer(row(), 130, 120, connection, services))
        assertNull(capture.finish(120, 1_020, connection, services))
        assertFalse(capture.invalidated)
        assertTrue(capture.offer(row(), 110, 120, connection, services))
        assertTrue(capture.offer(row(RegionalService.YOUTUBE), 115, 120, connection, services))
        val result = capture.finish(120, 1_020, connection, services)!!
        assertTrue(result.complete)
        assertEquals(RegionalStatus.WEB_REACHABLE, result.readings[RegionalService.GEMINI]!!.status)
    }

    @Test fun theDeadlineReturnsMissingMeasurementsRatherThanInventingFailures() {
        val capture = collector()
        assertTrue(capture.offer(row(), 110, 120, connection, services))
        assertNull(capture.finish(75_099, 75_999, connection, services))
        val result = capture.finish(75_100, 76_000, connection, services)!!
        assertFalse(result.complete)
        assertTrue(result.readings.containsKey(RegionalService.YOUTUBE))
        assertNull(result.readings[RegionalService.YOUTUBE])
        assertEquals(RegionalStatus.WEB_REACHABLE, result.readings[RegionalService.GEMINI]!!.status)
    }

    @Test fun aConnectionOrConfigurationOrProfileChangeInvalidatesAnUnfinishedSide() {
        val changed = connection.copy(token = "fedcba9876543210fedcba9876543210")
        for ((current, enabled) in listOf(changed to services, connection to setOf(RegionalService.GEMINI))) {
            val capture = collector()
            assertFalse(capture.offer(row(), 110, 120, current, enabled))
            assertTrue(capture.invalidated)
            assertNull(capture.finish(75_100, 76_000, connection, services))
        }
    }

    @Test fun clockRollbackAndResultsAfterTheCaptureDeadlineCannotBeAccepted() {
        val elapsedRollback = collector()
        assertNull(elapsedRollback.finish(99, 1_010, connection, services))
        assertTrue(elapsedRollback.invalidated)
        val wallRollback = collector()
        assertNull(wallRollback.finish(120, 999, connection, services))
        assertTrue(wallRollback.invalidated)
        val late = collector()
        assertFalse(late.offer(row(), 75_101, 75_102, connection, services))
        assertTrue(late.finish(75_102, 76_002, connection, services)!!.readings.values.all { it == null })
    }

    @Test fun labelsAreBoundedAndTheSelectedProfileSetMustBeNonempty() {
        for (bad in listOf("", "   ", "x".repeat(41), "label\nextra line", "label\u0000")) {
            assertThrows(IllegalArgumentException::class.java) { collector(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            RegionalComparisonCollector(100, connection, emptySet(), "A", 1_000)
        }
    }

    @Test fun aDuplicateSnapshotCannotReplaceAFresherResult() {
        val capture = collector()
        assertTrue(capture.offer(row(), 111, 120, connection, services))
        assertFalse(capture.offer(row().copy(status = RegionalStatus.AUTH_REQUIRED),
            110, 120, connection, services))
        assertEquals(RegionalStatus.WEB_REACHABLE,
            capture.finish(75_100, 76_000, connection, services)!!.readings[RegionalService.GEMINI]!!.status)
    }

    @Test fun aCountryObservedDuringCaptureAnnotatesTheSideWithoutRewritingEarlierEvidence() {
        val unknown = connection.copy(country = null, countryAt = null)
        val capture = RegionalComparisonCollector(100, unknown, setOf(RegionalService.GEMINI), "A", 1_000)
        val publicResult = row().copy(country = null, countryAt = null)
        val observed = connection.copy(countryAt = 1_015)
        assertTrue(capture.offer(publicResult, 110, 120, observed, setOf(RegionalService.GEMINI)))
        val result = capture.finish(120, 1_020, observed, setOf(RegionalService.GEMINI))!!
        assertEquals("DE", result.connection.country)
        assertEquals(1_015L, result.connection.countryAt)
        assertNull(result.readings[RegionalService.GEMINI]!!.country)
    }

    @Test fun aCountryTimestampFromTheFutureIsNotPresentedAsCurrentSnapshotMetadata() {
        val capture = collector()
        assertTrue(capture.offer(row(), 110, 120, connection, services))
        assertTrue(capture.offer(row(RegionalService.YOUTUBE), 115, 120, connection, services))
        val result = capture.finish(120, 1_020, connection.copy(countryAt = 1_021), services)!!
        assertNull(result.connection.country)
        assertNull(result.connection.countryAt)
    }

    @Test fun aNewCollectorAfterProcessRecreationNeedsItsOwnFreshMeasurements() {
        val before = collector()
        assertTrue(before.offer(row(), 110, 120, connection, services))
        val after = RegionalComparisonCollector(200, connection, services, "A", 1_100)
        assertFalse(after.offer(row(), 110, 220, connection, services))
        assertTrue(after.finish(75_200, 76_100, connection, services)!!.readings.values.all { it == null })
    }

    @Test fun realStorageRoundTripsTenPairsIncludingMissingReadingsAndRejectsTruncation() {
        val directory = Files.createTempDirectory("regional-comparisons").toFile()
        try {
            val a = RegionalComparisonSnapshot("Home", 1_020, connection,
                mapOf(RegionalService.GEMINI to row(), RegionalService.YOUTUBE to null))
            val b = a.copy(label = "Mobile", at = 2_000)
            val pair = RegionalComparisonPair(a, b)
            assertTrue(pair.sameConnection)
            val other = connection.copy(token = "fedcba9876543210fedcba9876543210")
            assertFalse(RegionalComparisonPair(a, b.copy(connection = other,
                readings = b.readings.mapValues { (_, value) -> value?.copy(scope = other.token) })).sameConnection)
            val file = File(directory, "pairs")
            val storage = RegionalComparisonStorage(file)
            storage.write(List(10) { pair })
            assertEquals(List(10) { pair }, RegionalComparisonStorage(file).read())
            assertThrows(IllegalArgumentException::class.java) { storage.write(List(11) { pair }) }
            val bytes = file.readBytes()
            for (bad in listOf(bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(1),
                ByteArray(RegionalComparisonCodec.MAX_BYTES + 1))) {
                assertThrows(Exception::class.java) { RegionalComparisonCodec.decode(bad) }
            }
            val wrongService = pair.copy(a = a.copy(readings = mapOf(RegionalService.YOUTUBE to row())))
            assertThrows(IllegalArgumentException::class.java) { storage.write(listOf(wrongService)) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun aBinaryPresenceMarkerOtherThanZeroOrOneIsRejected() {
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { out ->
            out.writeUTF("TGAB1")
            out.writeInt(1)
            for ((label, at) in listOf("A" to 1_020L, "B" to 2_020L)) {
                out.writeUTF(label)
                out.writeLong(at)
                out.writeUTF("0123456789abcdef0123456789abcdef")
                out.writeUTF("VPN")
                out.writeUTF("DE")
                out.writeLong(900)
                out.writeInt(1)
                out.writeUTF("gemini")
                out.writeByte(2)
                out.writeUTF("TGRJ1\nPUBLIC\t1010\t0123456789abcdef0123456789abcdef\tVPN\tDE\t900" +
                    "\tgemini\tWEB_REACHABLE\t0\t200\t-\n")
            }
        }
        assertThrows(IllegalArgumentException::class.java) { RegionalComparisonCodec.decode(bytes.toByteArray()) }
    }
}
