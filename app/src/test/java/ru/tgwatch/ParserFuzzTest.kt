package ru.tgwatch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/**
 * Parsers here take untrusted bytes and text: randomised input must fail closed, never
 * throw anything but its own IllegalArgumentException and never hang. The seed is fixed,
 * so a failure is reproducible from the reported mutation.
 */
class ParserFuzzTest {
    private val random = Random(20261012L)

    private fun validGreeting(nonce: ByteArray, pqSize: Int, fingerprints: Int): ByteArray {
        val padding = (4 - (pqSize + 1) % 4) % 4
        val bodySize = 4 + 16 + 16 + 1 + pqSize + padding + 4 + 4 + 8 * fingerprints
        check(bodySize % 4 == 0 && bodySize >= 56)
        val body = ByteBuffer.allocate(bodySize).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x05162463).put(nonce).put(ByteArray(16))
            .put(pqSize.toByte()).put(ByteArray(pqSize)).put(ByteArray(padding))
            .putInt(0x1cb5c415).putInt(fingerprints).put(ByteArray(8 * fingerprints))
        return ByteBuffer.allocate(20 + bodySize).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(0).putLong(1).putInt(bodySize).put(body.array()).array()
    }

    @Test fun validGreetingsOfEveryShapeAreAccepted() {
        for (pqSize in 1..8) for (fingerprints in 1..3) {
            val nonce = ByteArray(16).also { random.nextBytes(it) }
            val payload = validGreeting(nonce, pqSize, fingerprints)
            assertTrue("pq=$pqSize fingerprints=$fingerprints", MtProto.validResponse(payload, nonce))
        }
    }

    @Test fun mutatedGreetingsNeverCrashTheParser() {
        for (iteration in 0 until 4_000) {
            val nonce = ByteArray(16).also { random.nextBytes(it) }
            val payload = validGreeting(nonce, 1 + random.nextInt(8), 1 + random.nextInt(3))
            repeat(1 + random.nextInt(4)) {
                val index = random.nextInt(payload.size)
                payload[index] = (payload[index].toInt() xor (1 shl random.nextInt(8))).toByte()
            }
            val subject = if (random.nextBoolean()) payload.copyOf(random.nextInt(payload.size + 1)) else payload
            try {
                MtProto.validResponse(subject, nonce)
            } catch (failure: IllegalArgumentException) {
                throw AssertionError("Iteration $iteration rejected a payload by exception", failure)
            }
        }
    }

    @Test fun mutatedEventLogsFailClosed() {
        val source = EventLogStorage.encode(listOf("10.10 12:00:00  OK", "10.10 12:01:00  TG_DOWN"))
        for (iteration in 0 until 2_000) {
            val text = buildString {
                append(source)
                repeat(random.nextInt(3)) {
                    val at = random.nextInt(length + 1)
                    insert(at, ('a' + random.nextInt(26)))
                }
                if (random.nextBoolean()) setLength(random.nextInt(length + 1))
            }
            try {
                EventLogStorage.decode(text).forEach { assertTrue(it.length <= 4096) }
            } catch (expected: IllegalArgumentException) {
                // Fail closed is the contract.
            }
        }
    }

    @Test fun mutatedCsvImportsFailClosed() {
        val source = listOf(
            CsvImportTestSupport.header(),
            "1970-01-01T00:00:00.000Z,1970-01-01T00:00:10.000Z,OK,10000,120,0,true",
            "1970-01-01T00:01:00.000Z,1970-01-01T00:01:10.000Z,TG_DOWN,10000,-1,0,true",
        ).joinToString("\n")
        for (iteration in 0 until 2_000) {
            val text = buildString {
                append(source)
                repeat(random.nextInt(3)) {
                    val at = random.nextInt(length + 1)
                    insert(at, (' ' + random.nextInt(90)))
                }
                if (random.nextBoolean()) setLength(random.nextInt(length + 1))
            }
            try {
                val rows = CsvImport.parse(text)
                assertTrue(rows.all { it.until > it.at })
            } catch (expected: IllegalArgumentException) {
                // Fail closed is the contract.
            }
        }
    }

    @Test fun wrongLengthAndWrongNonceGreetingsAreRejected() {
        val nonce = ByteArray(16).also { random.nextBytes(it) }
        val payload = validGreeting(nonce, 4, 1)
        assertFalse(MtProto.validResponse(payload.copyOf(payload.size - 4), nonce))
        assertFalse(MtProto.validResponse(payload, ByteArray(16)))
    }
}

/** Header of the current CSV export; kept here so the fixture cannot drift unnoticed. */
object CsvImportTestSupport {
    fun header(): String = "from_utc,until_utc,status,duration_ms,latency_ms,clock_epoch,in_current_statistics"
}
