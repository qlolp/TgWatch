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
        buffer.long
        val bodySize = buffer.int
        if (bodySize != payload.size - 20 || bodySize < 56 || buffer.int != 0x05162463) return false
        return payload.copyOfRange(24, 40).contentEquals(nonce)
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
            val words = if (first == 0x7f) input.readUnsignedByte() or (input.readUnsignedByte() shl 8) or
                (input.readUnsignedByte() shl 16) else first
            if (words !in 19..1024) return false
            val response = ByteArray(words * 4)
            input.readFully(response)
            return validResponse(response, nonce)
        }
    }
}
