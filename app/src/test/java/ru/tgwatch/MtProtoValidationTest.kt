package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.Socket
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MtProtoValidationTest {
    private val nonce = ByteArray(16) { it.toByte() }
    private fun response() = MtProtoFixtures.response(nonce)
    private fun putInt(bytes: ByteArray, offset: Int, value: Int): ByteArray = bytes.also {
        ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
    }
    private fun assertRejected(bytes: ByteArray) = assertFalse("accepted ${bytes.size}-byte malformed response", MtProto.validResponse(bytes, nonce))

    @Test fun acceptsCompleteResPqAndBothServerMessageIdKinds() {
        assertTrue(MtProto.validResponse(response(), nonce))
        assertTrue(MtProto.validResponse(MtProtoFixtures.response(nonce, messageId = 3L), nonce))
        assertTrue(MtProto.validResponse(MtProtoFixtures.response(nonce, pq = byteArrayOf(15)), nonce))
    }

    @Test fun rejectsMatchingNonceWithMissingTlTail() {
        val fake = ByteBuffer.allocate(76).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(0).putLong(1).putInt(56).putInt(0x05162463).put(nonce).array()
        assertRejected(fake)
    }

    @Test fun rejectsInvalidServerMessageIds() {
        for (id in listOf(0L, 2L, 4L, -1L, Long.MIN_VALUE + 1)) {
            assertRejected(MtProtoFixtures.response(nonce, messageId = id))
        }
    }

    @Test fun rejectsNonzeroPqPadding() {
        assertRejected(response().also { it[65] = 1 })
        assertRejected(response().also { it[67] = 1 })
    }

    @Test fun rejectsEmptyOrOversizedPq() {
        assertRejected(response().also { it[56] = 0 })
        assertRejected(MtProtoFixtures.response(nonce, pq = ByteArray(9) { 1 }))
    }

    @Test fun rejectsReservedAndNoncanonicalLongTlLengths() {
        assertRejected(response().also { it[56] = 255.toByte() })
        val original = response()
        val longEncoded = original.copyOfRange(0, 56) + byteArrayOf(254.toByte(), 8, 0, 0) +
            original.copyOfRange(57, 65) + original.copyOfRange(68, original.size)
        assertRejected(putInt(longEncoded, 16, longEncoded.size - 20))
    }

    @Test fun rejectsWrongVectorConstructor() {
        assertRejected(putInt(response(), 68, 0x12345678))
    }

    @Test fun rejectsEmptyNegativeHugeOrInconsistentFingerprintCounts() {
        assertRejected(MtProtoFixtures.response(nonce, fingerprints = 0))
        for (count in listOf(-1, 0, 2, Int.MAX_VALUE)) assertRejected(putInt(response(), 72, count))
    }

    @Test fun rejectsTrailingBytesEvenWhenBodyLengthIncludesThem() {
        val extra = response() + ByteArray(4)
        assertRejected(putInt(extra, 16, extra.size - 20))
    }

    @Test fun rejectsEveryTruncatedResPqAndIncorrectHeaderLength() {
        val complete = response()
        for (size in 0 until complete.size) {
            val truncated = complete.copyOf(size)
            if (size >= 20) putInt(truncated, 16, size - 20)
            assertRejected(truncated)
        }
        for (size in listOf(-1, 0, 56, Int.MAX_VALUE)) assertRejected(putInt(response(), 16, size))
    }

    @Test fun rejectsAuthConstructorAndNonceMismatch() {
        assertRejected(response().also { it[0] = 1 })
        assertRejected(putInt(response(), 20, 0xbe7e8ef1.toInt()))
        assertRejected(response().also { it[24] = 99 })
        assertFalse(MtProto.validResponse(response(), ByteArray(15)))
    }

    @Test fun acceptsMaximumBoundedResponseAndRejectsOversizedPayload() {
        val max = MtProtoFixtures.response(nonce, pq = ByteArray(4) { 1 }, fingerprints = 503)
        assertEquals(4096, max.size)
        assertTrue(MtProto.validResponse(max, nonce))
        assertRejected(MtProtoFixtures.response(nonce, pq = ByteArray(4) { 1 }, fingerprints = 504))
    }

    @Test fun acceptsCanonicalShortAndExtendedAbridgedFrames() {
        assertTrue(probe { payload -> byteArrayOf((payload.size / 4).toByte()) + payload })
        assertTrue(probe(fingerprints = 54) { payload ->
            assertEquals(508, payload.size)
            byteArrayOf(0x7f, 0x7f, 0, 0) + payload
        })
        assertTrue(probe(pq = ByteArray(4) { 1 }, fingerprints = 503) { payload ->
            byteArrayOf(0x7f, 0, 4, 0) + payload
        })
    }

    @Test fun rejectsNoncanonicalExtendedFrameBeforeReadingPayload() {
        assertFalse(probe { payload -> byteArrayOf(0x7f, (payload.size / 4).toByte(), 0, 0) + payload })
    }

    @Test fun rejectsHighBitAndInvalidFrameSizesBeforePayloadRead() {
        for (header in listOf(byteArrayOf(0), byteArrayOf(18), byteArrayOf(0x7f, 0, 0, 0), byteArrayOf(0x7f, 1, 4, 0),
            byteArrayOf(0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))) {
            assertFalse("accepted header ${header.toList()}", probe { header })
        }
    }

    @Test fun rejectsAbridgedHighBitOnOtherwiseCompleteFrame() {
        assertFalse(probe(pq = ByteArray(4) { 1 }, fingerprints = 55) { payload ->
            assertEquals(512, payload.size)
            byteArrayOf(0x80.toByte()) + payload
        })
        assertFalse(probe(fingerprints = 118) { payload ->
            assertEquals(1020, payload.size)
            byteArrayOf(0xff.toByte()) + payload
        })
    }

    @Test fun rejectsTruncatedAbridgedHeaderAndBody() {
        for (header in listOf(byteArrayOf(), byteArrayOf(0x7f), byteArrayOf(0x7f, 0x7f), byteArrayOf(0x7f, 0x7f, 0))) {
            try { probe { header }; fail("truncated header accepted") } catch (_: EOFException) {}
        }
        try { probe { payload -> byteArrayOf((payload.size / 4).toByte()) + payload.dropLast(1) }; fail("truncated body accepted") }
        catch (_: EOFException) {}
    }

    @Test fun sendsOnlyUnauthenticatedReqPqMultiAndClosesSocket() {
        val socket = FixtureSocket { payload -> byteArrayOf((payload.size / 4).toByte()) + payload }
        assertTrue(MtProto.probe("127.0.0.1", { socket }, ProbeCancellation()))
        val sent = socket.sent.toByteArray()
        assertEquals(42, sent.size)
        assertEquals(0xef.toByte(), sent[0])
        assertEquals(10.toByte(), sent[1])
        val header = ByteBuffer.wrap(sent, 2, 40).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0L, header.long)
        assertEquals(0L, header.long and 3)
        assertEquals(20, header.int)
        assertEquals(0xbe7e8ef1.toInt(), header.int)
        assertTrue(socket.closed)
    }

    @Test fun sharedBatchDeadlineCancelsBlockedFrameReadAndClosesSocket() {
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        val socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {}
            override fun setSoTimeout(timeout: Int) {}
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getInputStream() = object : java.io.InputStream() {
                override fun read(): Int { started.countDown(); closed.await(2, TimeUnit.SECONDS); return -1 }
            }
            override fun close() { closed.countDown() }
        }
        val start = System.nanoTime()
        try {
            val result = ProbeBatch(pool, 150).run(listOf(ProbeEndpoint("dc", ProbeGroup.MTPROTO, "127.0.0.1"))) { ep, cancellation ->
                ProbeOutcome(ep.name, ep.group, MtProto.probe(ep.address, { socket }, cancellation), detail = "resPQ")
            }
            assertEquals(0L, started.count)
            assertFalse(result.single().reachable)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000)
            assertTrue(closed.await(1, TimeUnit.SECONDS))
        } finally { socket.close(); pool.shutdownNow() }
    }

    private fun probe(pq: ByteArray = byteArrayOf(0x17, 0xed.toByte(), 0x48, 0x94.toByte(), 0x1a, 0x08,
        0xf9.toByte(), 0x81.toByte()), fingerprints: Int = 1, frame: (ByteArray) -> ByteArray): Boolean {
        val socket = FixtureSocket(pq, fingerprints, frame)
        return MtProto.probe("127.0.0.1", { socket }, ProbeCancellation())
    }

    private class FixtureSocket(private val pq: ByteArray = byteArrayOf(0x17, 0xed.toByte(), 0x48, 0x94.toByte(),
        0x1a, 0x08, 0xf9.toByte(), 0x81.toByte()), private val fingerprints: Int = 1,
        private val frame: (ByteArray) -> ByteArray) : Socket() {
        val sent = ByteArrayOutputStream()
        var closed = false
        override fun connect(endpoint: SocketAddress, timeout: Int) {}
        override fun setSoTimeout(timeout: Int) {}
        override fun getOutputStream() = sent
        override fun getInputStream(): ByteArrayInputStream {
            val nonce = sent.toByteArray().copyOfRange(26, 42)
            return ByteArrayInputStream(frame(MtProtoFixtures.response(nonce, pq, fingerprints)))
        }
        override fun close() { closed = true }
    }
}
