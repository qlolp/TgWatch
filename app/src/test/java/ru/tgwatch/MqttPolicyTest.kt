package ru.tgwatch

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttPolicyTest {
    @Test fun hostPortAndTopicAreBounded() {
        assertEquals("broker.example.org", MqttPolicy.normalizeHost(" Broker.Example.org "))
        assertNull(MqttPolicy.normalizeHost("bad_host"))
        assertNull(MqttPolicy.normalizeHost("a".repeat(64) + ".org"))
        assertNull(MqttPolicy.normalizeHost("x".repeat(MqttPolicy.MAX_HOST_LENGTH + 1)))
        assertEquals(8883, MqttPolicy.normalizePort("8883"))
        assertNull(MqttPolicy.normalizePort("0"))
        assertNull(MqttPolicy.normalizePort("65536"))
        assertNull(MqttPolicy.normalizePort("1e3"))
        assertEquals("home/tgwatch", MqttPolicy.normalizeTopic("home/tgwatch"))
        listOf("", "a/#", "a/+", "\$SYS/x", "a\u0000b", "t".repeat(MqttPolicy.MAX_TOPIC_BYTES + 1))
            .forEach { assertNull(it, MqttPolicy.normalizeTopic(it)) }
        assertNull(MqttPolicy.normalizeSecret("p".repeat(MqttPolicy.MAX_PASSWORD_BYTES + 1),
            MqttPolicy.MAX_PASSWORD_BYTES))
        assertNull(MqttPolicy.normalizeSecret("a\nb", MqttPolicy.MAX_PASSWORD_BYTES))
        assertEquals("", MqttPolicy.normalizeSecret(null, MqttPolicy.MAX_PASSWORD_BYTES))
    }

    @Test fun backoffDoublesIsCappedAndResets() {
        val backoff = MqttBackoff(baseMs = 10, capMs = 35)
        assertTrue(backoff.allows(0))
        backoff.failed(100)
        assertFalse(backoff.allows(109))
        assertTrue(backoff.allows(110))
        backoff.failed(200)
        assertFalse(backoff.allows(219))
        assertTrue(backoff.allows(220))
        repeat(100) { backoff.failed(1_000) }
        assertFalse(backoff.allows(1_034))
        assertTrue("Capped wait", backoff.allows(1_035))
        backoff.failed(2_000)
        backoff.succeeded()
        assertTrue(backoff.allows(2_000))
    }

    @Test fun connectAndPublishFramesAreWellFormed() {
        val endpoint = MqttEndpoint("h", 8883, "t/x", "user", "pw")
        val connect = MqttPackets.connect("cid", endpoint)
        assertEquals(0x10, connect[0].toInt())
        assertEquals(connect.size - 2, connect[1].toInt())
        assertEquals(0xC2, connect[9].toInt() and 0xff)
        val anonymous = MqttPackets.connect("cid", endpoint.copy(username = null, password = null))
        assertEquals(0x02, anonymous[9].toInt())
        val publish = MqttPackets.publish("t/x", "{}".toByteArray())
        assertEquals(0x32, publish[0].toInt())
        assertEquals(2 + 3 + 2 + 2, publish[1].toInt())
    }

    @Test fun acknowledgementsAreStrict() {
        assertTrue(MqttPackets.readConnack(ByteArrayInputStream(byteArrayOf(0x20, 2, 0, 0))))
        assertFalse(MqttPackets.readConnack(ByteArrayInputStream(byteArrayOf(0x20, 2, 0, 5))))
        assertTrue(MqttPackets.readPuback(ByteArrayInputStream(byteArrayOf(0x40, 2, 0, 1))))
        assertFalse(MqttPackets.readPuback(ByteArrayInputStream(byteArrayOf(0x40, 2, 0, 2))))
        val oversize = ByteArrayInputStream(byteArrayOf(0x20, 0xff.toByte(), 0xff.toByte(), 0x7f))
        try {
            MqttPackets.readConnack(oversize)
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
        }
    }
}
