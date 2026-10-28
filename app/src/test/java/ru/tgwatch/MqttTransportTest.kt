package ru.tgwatch

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain loopback sockets stand in for TLS; the protocol, slot, deadline and backoff model are identical. */
class MqttTransportTest {
    private val endpoint = MqttEndpoint("127.0.0.1", 1, "t", null, null)
    private val io = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }

    private fun broker(ack: Boolean = true, hang: Boolean = false): Pair<ServerSocket, () -> Unit> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val accepted = io.submit {
            try {
                val client = server.accept()
                val input = client.getInputStream()
                if (hang) Thread.sleep(30_000)
                input.read(ByteArray(64))
                if (ack) {
                    client.getOutputStream().write(byteArrayOf(0x20, 2, 0, 0))
                    input.read(ByteArray(256))
                    client.getOutputStream().write(byteArrayOf(0x40, 2, 0, 1))
                }
                client.close()
            } catch (_: Exception) {
            }
        }
        return server to { accepted.cancel(true) }
    }

    private fun transport(server: ServerSocket?, clock: () -> Long, opens: AtomicInteger = AtomicInteger()) =
        MqttTransport(io, clock, MqttBackoff(baseMs = 1_000, capMs = 4_000), {
            opens.incrementAndGet()
            Socket(InetAddress.getLoopbackAddress(), server?.localPort ?: 1)
        })

    private fun submit(transport: MqttTransport, payload: ByteArray = "{}".toByteArray()): Int? {
        val result = CountDownLatch(1)
        var code: Int? = null
        if (!transport.submit(endpoint, payload) { code = it; result.countDown() }) return null
        assertTrue(result.await(10, TimeUnit.SECONDS))
        return code
    }

    @Test fun publishIsAcknowledgedAndFreesTheSlot() {
        val (server, _) = broker()
        val transport = transport(server, { 0L })
        assertEquals(MqttTransport.OK, submit(transport))
        server.close()
    }

    @Test fun failureStartsBackoffWithoutQueueingAndTimeRecovers() {
        var now = 0L
        val opens = AtomicInteger()
        val transport = transport(null, { now }, opens)
        assertEquals(MqttTransport.NO_RESPONSE, submit(transport))
        assertEquals(1, opens.get())
        assertEquals("Refused during backoff, nothing queued", null, submit(transport))
        assertEquals(1, opens.get())
        now = 1_000
        assertEquals(MqttTransport.NO_RESPONSE, submit(transport))
        now = 2_999
        assertEquals(null, submit(transport))
        now = 3_000
        assertEquals(MqttTransport.NO_RESPONSE, submit(transport))
        assertEquals(3, opens.get())
    }

    @Test fun settingsResetClearsFailureHistory() {
        val transport = transport(null, { 0L })
        assertEquals(MqttTransport.NO_RESPONSE, submit(transport))
        assertEquals(null, submit(transport))
        transport.reset()
        assertEquals(MqttTransport.NO_RESPONSE, submit(transport))
    }

    @Test fun oneSlotAndPayloadLimit() {
        val (server, stop) = broker(hang = true)
        val transport = transport(server, { 0L })
        val done = CountDownLatch(1)
        assertTrue(transport.submit(endpoint, "{}".toByteArray()) { done.countDown() })
        assertFalse("Only one physical request", transport.submit(endpoint, "{}".toByteArray()) { })
        assertFalse(transport.submit(endpoint, ByteArray(MqttPolicy.MAX_PAYLOAD_BYTES + 1)) { })
        transport.cancel()
        assertTrue("Cancellation closes the socket and releases the slot", done.await(5, TimeUnit.SECONDS))
        stop()
        server.close()
    }
}

class MqttTlsCleanupTest {
    private val endpoint = MqttEndpoint("127.0.0.1", 1, "t", null, null)

    private class Tracking : Socket() {
        @Volatile var closed = false
        override fun connect(address: java.net.SocketAddress?, timeout: Int) = Unit
        override fun close() {
            closed = true
            super.close()
        }
    }

    private fun failsWith(error: Throwable, expected: Class<out Throwable>) {
        val socket = Tracking()
        try {
            MqttTransport.openTls(endpoint, socket) { throw error }
            throw AssertionError("expected failure")
        } catch (e: Throwable) {
            assertTrue(expected.isInstance(e))
        }
        assertTrue("Socket must be closed after ${expected.simpleName}", socket.closed)
    }

    @Test fun socketIsClosedOnIoRuntimeAndSecurityFailures() {
        failsWith(java.io.IOException(), java.io.IOException::class.java)
        failsWith(IllegalStateException(), IllegalStateException::class.java)
        failsWith(SecurityException(), SecurityException::class.java)
    }
}
