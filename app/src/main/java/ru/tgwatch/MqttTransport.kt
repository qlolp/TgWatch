package ru.tgwatch

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * One physical MQTT worker slot. Each admitted delivery opens a fresh TLS connection, publishes one QoS 1
 * message and closes it, so there is no persistent socket and no offline queue. Failures start a capped backoff
 * during which admission is refused; the coordinator then reports a failed delivery and retries later from the
 * latest measured state only.
 */
internal class MqttTransport(
    private val io: Executor,
    private val nowElapsed: () -> Long,
    private val backoff: MqttBackoff = MqttBackoff(),
    private val open: (MqttEndpoint) -> Socket = ::openTls
) {
    private class Call {
        @Volatile var cancelled = false
        @Volatile var superseded = false
        @Volatile var socket: Socket? = null
    }
    private var active: Call? = null

    @Synchronized fun submit(endpoint: MqttEndpoint, payload: ByteArray, finished: (Int) -> Unit): Boolean {
        if (active != null || payload.size > MqttPolicy.MAX_PAYLOAD_BYTES || !backoff.allows(nowElapsed()))
            return false
        val call = Call()
        active = call
        val captured = payload.copyOf()
        return try {
            io.execute { run(call, endpoint, captured, finished) }
            true
        } catch (_: RejectedExecutionException) {
            active = null
            false
        }
    }

    /** Closing the socket unblocks connect/read; stuck DNS keeps the slot until it returns. */
    @Synchronized fun cancel() {
        val call = active ?: return
        call.cancelled = true
        call.socket?.let(::close)
    }

    /** Settings changed: drop the failure history of the previous broker. */
    @Synchronized fun reset() {
        active?.superseded = true
        backoff.reset()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun run(call: Call, endpoint: MqttEndpoint, payload: ByteArray, finished: (Int) -> Unit) {
        var delivered = false
        try {
            if (!call.cancelled) {
                val socket = open(endpoint)
                call.socket = socket
                if (!call.cancelled) delivered = publish(socket, endpoint, payload)
            }
        } catch (_: IOException) {
            // Sanitized failure only; no broker, topic, credentials or exception text escape.
        } catch (_: RuntimeException) {
            // Platform failures have the same private result.
        } finally {
            call.socket?.let(::close)
            synchronized(this) { if (active === call) active = null }
            if (delivered) backoff.succeeded() else if (!call.superseded) backoff.failed(nowElapsed())
            finished(if (delivered) OK else NO_RESPONSE)
        }
    }

    private fun publish(socket: Socket, endpoint: MqttEndpoint, payload: ByteArray): Boolean {
        socket.soTimeout = WebhookPolicy.TIMEOUT_MS
        val out = socket.getOutputStream()
        val input = socket.getInputStream()
        out.write(MqttPackets.connect("tgwatch-" + UUID.randomUUID().toString().take(CLIENT_SUFFIX), endpoint))
        out.flush()
        if (!MqttPackets.readConnack(input)) return false
        out.write(MqttPackets.publish(endpoint.topic, payload))
        out.flush()
        val acknowledged = MqttPackets.readPuback(input)
        if (acknowledged) {
            out.write(MqttPackets.disconnect)
            out.flush()
        }
        return acknowledged
    }

    internal companion object {
        const val OK = 200
        const val NO_RESPONSE = -1
        private const val CLIENT_SUFFIX = 12

        private fun close(socket: Socket) {
            try {
                socket.close()
            } catch (_: IOException) {
                // Closing is best effort and cannot change slot accounting.
            }
        }

        /** TLS only, certificate chain and hostname verified against the system trust store. */
        fun openTls(endpoint: MqttEndpoint, plain: Socket = Socket(),
            factory: () -> SSLSocketFactory = { SSLSocketFactory.getDefault() as SSLSocketFactory }): Socket {
            var secured = false
            try {
                plain.connect(InetSocketAddress(endpoint.host, endpoint.port), WebhookPolicy.TIMEOUT_MS)
                plain.soTimeout = WebhookPolicy.TIMEOUT_MS
                val tls = factory()
                    .createSocket(plain, endpoint.host, endpoint.port, true) as SSLSocket
                tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                tls.startHandshake()
                secured = true
                return tls
            } finally {
                // Any failure, including SecurityException or other runtime errors, must not leak the socket.
                if (!secured) close(plain)
            }
        }
    }
}
