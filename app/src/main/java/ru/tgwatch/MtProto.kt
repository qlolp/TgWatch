package ru.tgwatch

import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/** Unauthenticated req_pq_multi / resPQ only. Never logs in or reads/sends messages.
 * https://core.telegram.org/mtproto/samples-auth_key
 * A greeting is a reachability signal, not server authentication or message delivery.
 */
object MtProto {
    fun request(nonce: ByteArray, nowMillis: Long): ByteArray {
        require(nonce.size == 16)
        val messageId = ((nowMillis / 1000) shl 32) or (((nowMillis % 1000) shl 32) / 1000 and -4L)
        return ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(0).putLong(messageId).putInt(20).putInt(0xbe7e8ef1.toInt()).put(nonce).array()
    }
    fun validResponse(payload: ByteArray, nonce: ByteArray): Boolean {
        if (payload.size < 76 || payload.size > 4096 || nonce.size != 16) return false
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.long != 0L) return false
        val messageId = buffer.long
        // Server messages have low bits 01 (response) or 11 (notification).
        if (messageId <= 0 || messageId and 1L != 1L) return false
        val bodySize = buffer.int
        if (bodySize != payload.size - 20 || bodySize < 56 || bodySize % 4 != 0 ||
            buffer.int != 0x05162463) return false
        for (expected in nonce) if (buffer.get() != expected) return false
        // server_nonce is an opaque int128: presence, rather than its value, is validated.
        buffer.position(buffer.position() + 16)
        // pq is a big-endian unsigned 64-bit integer encoded as TL bytes. Its bounded
        // size uses the canonical one-byte length; 254/255 encodings cannot be valid.
        val pqSize = buffer.get().toInt() and 0xff
        if (pqSize !in 1..8) return false
        val padding = (4 - (pqSize + 1) % 4) % 4
        if (buffer.remaining() < pqSize + padding + 8) return false
        buffer.position(buffer.position() + pqSize)
        repeat(padding) { if (buffer.get() != 0.toByte()) return false }
        if (buffer.int != 0x1cb5c415) return false
        val fingerprints = buffer.int
        // Exact remaining length prevents overflow, truncated vectors and trailing data.
        return fingerprints > 0 && buffer.remaining() % 8 == 0 &&
            fingerprints == buffer.remaining() / 8
    }
    fun probe(host: String, socketFactory: () -> Socket, cancellation: ProbeCancellation): Boolean {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val socket = socketFactory()
        cancellation.register { socket.close() }
        socket.use {
            cancellation.check()
            socket.connect(InetSocketAddress(host, 443), 3_000)
            socket.soTimeout = 3_000
            val output = socket.getOutputStream()
            val payload = request(nonce, System.currentTimeMillis())
            output.write(0xef)
            output.write(payload.size / 4)
            output.write(payload)
            output.flush()
            val input = DataInputStream(socket.getInputStream())
            val first = input.readUnsignedByte()
            val words = if (first == 0x7f) {
                val extended = input.readUnsignedByte() or (input.readUnsignedByte() shl 8) or
                    (input.readUnsignedByte() shl 16)
                if (extended < 0x7f) return false
                extended
            } else {
                // No quick-ack flag or reserved byte is valid in this greeting response.
                if (first >= 0x7f) return false
                first
            }
            if (words !in 19..1024) return false
            val response = ByteArray(words * 4)
            input.readFully(response)
            return validResponse(response, nonce)
        }
    }
}
