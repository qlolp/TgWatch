package ru.tgwatch

import java.util.concurrent.Executor
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

/** Dedicated mqttTlsIntegrationTest fork only; production TLS defaults against a real Mosquitto broker. */
class MqttTlsIntegrationTest {
    private fun endpoint(port: Int = 18883, host: String = "localhost", password: String = "fixture-password") =
        MqttEndpoint(host, port, "tgwatch/ci/canary", "canary", password)

    private fun deliver(endpoint: MqttEndpoint): Int? {
        var result: Int? = null
        val transport = MqttTransport(Executor { it.run() }, { System.nanoTime() / 1_000_000 })
        assertTrue(transport.submit(endpoint, "tgwatch-tls-canary-v1".toByteArray()) { result = it })
        return result
    }

    @Test fun validPublishReceivesPubackFromActualBroker() {
        assertEquals(MqttTransport.OK, deliver(endpoint()))
    }

    @Test fun untrustedCertificateAuthorityIsRejected() {
        assertThrows(SSLHandshakeException::class.java) { MqttTransport.openTls(endpoint(port = 18884)).close() }
    }

    @Test fun trustedCertificateWithWrongHostnameIsRejected() {
        assertThrows(SSLHandshakeException::class.java) { MqttTransport.openTls(endpoint(host = "127.0.0.1")).close() }
    }

    @Test fun wrongPasswordGetsNegativeConnackAndNoDelivery() {
        val rejected = endpoint(password = "incorrect-fixture-password")
        MqttTransport.openTls(rejected).use { socket ->
            socket.getOutputStream().write(MqttPackets.connect("negative-auth-canary", rejected))
            socket.getOutputStream().flush()
            val response = socket.getInputStream().readNBytes(4)
            assertArrayEquals(byteArrayOf(0x20, 2, 0, 5), response)
        }
        assertEquals(MqttTransport.NO_RESPONSE, deliver(rejected))
    }
}
