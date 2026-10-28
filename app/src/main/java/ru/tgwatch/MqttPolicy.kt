package ru.tgwatch

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

/** Validated, bounded broker settings. Never logged, displayed in full, backed up or placed in diagnostics. */
internal data class MqttEndpoint(val host: String, val port: Int, val topic: String,
    val username: String?, val password: String?)

/** Android-independent MQTT limits. Everything user-supplied is bounded before it reaches a socket. */
internal object MqttPolicy {
    const val MAX_HOST_LENGTH = 253
    const val DEFAULT_PORT = 8883
    const val MAX_PORT = 65_535
    const val MAX_TOPIC_BYTES = 128
    const val MAX_USERNAME_BYTES = 64
    const val MAX_PASSWORD_BYTES = 128
    const val MAX_PAYLOAD_BYTES = 1_024
    const val KEEP_ALIVE_SEC = 30
    /** Minimum wait after the first failed connection; doubles per consecutive failure. */
    const val BACKOFF_BASE_MS = 30_000L
    const val BACKOFF_CAP_MS = 900_000L
    private const val MAX_LABEL_LENGTH = 63
    private const val MAX_PORT_DIGITS = 5
    private const val FIRST_PRINTABLE = ' '
    private const val LAST_PRINTABLE = '~'

    fun normalizeHost(raw: String?): String? {
        val host = raw?.trim()?.lowercase().orEmpty()
        val labels = host.split('.')
        return host.takeIf {
            it.length in 1..MAX_HOST_LENGTH && labels.all { label ->
                label.length in 1..MAX_LABEL_LENGTH && !label.startsWith('-') && !label.endsWith('-') &&
                    label.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' }
            }
        }
    }

    fun normalizePort(raw: String?): Int? = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_PORT_DIGITS &&
        it.all(Char::isDigit) }?.toInt()?.takeIf { it in 1..MAX_PORT }

    /** A concrete publish topic: no wildcards, no system-topic prefix, no control characters. */
    fun normalizeTopic(raw: String?): String? = raw?.trim()?.takeIf {
        it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= MAX_TOPIC_BYTES && !it.startsWith("$") &&
            it.none { c -> c == '#' || c == '+' || c.isISOControl() }
    }

    /** Empty means "none". Credentials are bounded printable text; null marks invalid input. */
    fun normalizeSecret(raw: String?, maxBytes: Int): String? {
        val text = raw.orEmpty()
        return text.takeIf { it.toByteArray(Charsets.UTF_8).size <= maxBytes &&
            it.all { c -> c in FIRST_PRINTABLE..LAST_PRINTABLE } }
    }
}

/**
 * Bounded reconnect model: one connection attempt per delivery, no persistent socket and no offline queue.
 * After consecutive failures new attempts are refused until a capped exponential interval passed.
 */
internal class MqttBackoff(private val baseMs: Long = MqttPolicy.BACKOFF_BASE_MS,
    private val capMs: Long = MqttPolicy.BACKOFF_CAP_MS) {
    private var failures = 0
    private var blockedUntil: Long? = null

    @Synchronized fun allows(now: Long): Boolean =
        blockedUntil.let { it == null || now < it - capMs || now >= it }

    @Synchronized fun failed(now: Long) {
        failures = minOf(failures + 1, MAX_DOUBLINGS)
        blockedUntil = now + minOf(baseMs shl (failures - 1), capMs)
    }

    @Synchronized fun succeeded() = reset()

    @Synchronized fun reset() {
        failures = 0
        blockedUntil = null
    }

    private companion object { const val MAX_DOUBLINGS = 20 }
}

/** Minimal MQTT 3.1.1 packets: CONNECT, one QoS 1 PUBLISH, DISCONNECT. */
internal object MqttPackets {
    private const val CONNECT = 0x10
    private const val PUBLISH_QOS1 = 0x32
    private const val CONNACK = 0x20
    private const val PUBACK = 0x40
    private const val CLEAN_SESSION = 0x02
    private const val USERNAME_FLAG = 0x80
    private const val PASSWORD_FLAG = 0x40
    private const val PROTOCOL_LEVEL = 4
    private const val PACKET_ID = 1
    private const val MAX_REMAINING = 4_096
    private const val CONTINUATION = 0x80
    private const val SEVEN_BITS = 0x7f
    private const val MAX_LENGTH_BYTES = 4
    private const val BYTE = 0xff
    private const val MAX_UTF_BYTES = 0xffff
    private const val SHIFT_BYTE = 8
    private const val SHIFT_SEVEN = 7
    private const val DISCONNECT = 0xE0
    private const val TYPE_MASK = 0xf0
    val disconnect = byteArrayOf(DISCONNECT.toByte(), 0)

    fun connect(clientId: String, endpoint: MqttEndpoint): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(utf("MQTT"))
        body.write(PROTOCOL_LEVEL)
        body.write(CLEAN_SESSION or (if (endpoint.username != null) USERNAME_FLAG else 0) or
            (if (endpoint.username != null && endpoint.password != null) PASSWORD_FLAG else 0))
        body.write(MqttPolicy.KEEP_ALIVE_SEC shr SHIFT_BYTE)
        body.write(MqttPolicy.KEEP_ALIVE_SEC and BYTE)
        body.write(utf(clientId))
        endpoint.username?.let {
            body.write(utf(it))
            endpoint.password?.let { password -> body.write(utf(password)) }
        }
        return frame(CONNECT, body.toByteArray())
    }

    fun publish(topic: String, payload: ByteArray): ByteArray {
        require(payload.size <= MqttPolicy.MAX_PAYLOAD_BYTES)
        val body = ByteArrayOutputStream()
        body.write(utf(topic))
        body.write(PACKET_ID shr SHIFT_BYTE)
        body.write(PACKET_ID and BYTE)
        body.write(payload)
        return frame(PUBLISH_QOS1, body.toByteArray())
    }

    /** True only for CONNACK with return code 0. */
    fun readConnack(input: InputStream): Boolean {
        val packet = read(input)
        return packet.first == CONNACK && packet.second.size == 2 && packet.second[1].toInt() == 0
    }

    fun readPuback(input: InputStream): Boolean {
        val packet = read(input)
        return packet.first == PUBACK && packet.second.size == 2 &&
            ((packet.second[0].toInt() and BYTE) shl SHIFT_BYTE or (packet.second[1].toInt() and BYTE)) == PACKET_ID
    }

    private fun utf(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_UTF_BYTES)
        return byteArrayOf((bytes.size shr SHIFT_BYTE).toByte(), bytes.size.toByte()) + bytes
    }

    private fun frame(type: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(type)
        var remaining = body.size
        do {
            var digit = remaining and SEVEN_BITS
            remaining = remaining shr SHIFT_SEVEN
            if (remaining > 0) digit = digit or CONTINUATION
            out.write(digit)
        } while (remaining > 0)
        out.write(body)
        return out.toByteArray()
    }

    /** Reads one bounded packet; oversize or malformed input is an IOException. */
    private fun read(input: InputStream): Pair<Int, ByteArray> {
        val data = DataInputStream(input)
        val type = data.readUnsignedByte() and TYPE_MASK
        var length = 0
        var shift = 0
        for (index in 0 until MAX_LENGTH_BYTES) {
            val digit = data.readUnsignedByte()
            length += (digit and SEVEN_BITS) shl shift
            shift += SHIFT_SEVEN
            if (digit and CONTINUATION == 0) break
            if (index == MAX_LENGTH_BYTES - 1) throw IOException("Malformed length")
        }
        if (length > MAX_REMAINING) throw IOException("Packet too large")
        return type to ByteArray(length).also { data.readFully(it) }
    }
}
