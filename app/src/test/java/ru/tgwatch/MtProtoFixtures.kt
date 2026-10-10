package ru.tgwatch

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Complete wire-format resPQ, including TL bytes padding and fingerprint vector. */
internal object MtProtoFixtures {
    fun response(nonce: ByteArray, pq: ByteArray = byteArrayOf(0x17, 0xed.toByte(), 0x48, 0x94.toByte(),
        0x1a, 0x08, 0xf9.toByte(), 0x81.toByte()), fingerprints: Int = 1, messageId: Long = 1L): ByteArray {
        require(nonce.size == 16 && pq.size in 1..253 && fingerprints >= 0)
        val encodedPqSize = (pq.size + 1 + 3) and -4
        val bodySize = 4 + 16 + 16 + encodedPqSize + 4 + 4 + fingerprints * 8
        val buffer = ByteBuffer.allocate(20 + bodySize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putLong(0).putLong(messageId).putInt(bodySize).putInt(0x05162463).put(nonce)
            .put(ByteArray(16) { (it + 40).toByte() }).put(pq.size.toByte()).put(pq)
        repeat(encodedPqSize - pq.size - 1) { buffer.put(0) }
        buffer.putInt(0x1cb5c415).putInt(fingerprints)
        repeat(fingerprints) { buffer.putLong(0xc3b42b026ce86b21UL.toLong() + it) }
        return buffer.array()
    }
}
