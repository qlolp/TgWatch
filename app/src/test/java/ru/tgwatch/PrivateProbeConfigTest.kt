package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test
import ru.tgwatch.probe.HttpJsonCondition
import ru.tgwatch.probe.HttpProbeCondition
import ru.tgwatch.probe.HttpRequestHeader

class PrivateProbeConfigTest {
    private val url = "https://private.example/private-path"
    private val condition = HttpProbeCondition(204, "private-field", "private-value")
    @Test fun privateEnvelopeRoundTripsWhileOldAddressesRemainReadable() {
        val config = PrivateProbeConfig(url, condition)
        assertEquals(config, PrivateProbeConfigCodec.decode(PrivateProbeConfigCodec.encode(config)))
        assertEquals(PrivateProbeConfig(url), PrivateProbeConfigCodec.decode(url))
        assertEquals(PrivateProbeConfig(ExtraProbes.MATRIX_URL),
            PrivateProbeConfigCodec.decode(ExtraProbes.MATRIX_URL))
        val oldEnvelope = """{"version":1,"url":"$url","expected_code":204,"json_field":"private-field","expected_value":"private-value"}"""
        assertEquals(config, PrivateProbeConfigCodec.decode(oldEnvelope))
    }
    @Test fun expandedPrivateEnvelopeRoundTripsWithoutPuttingSecretsInRendering() {
        val condition = HttpProbeCondition(bodySubstring = "body-secret",
            jsonConditions = listOf(HttpJsonCondition("health.status", "json-secret")),
            headers = listOf(HttpRequestHeader("X-Private-Header", "header-secret")))
        val config = PrivateProbeConfig(url, condition)
        val encoded = PrivateProbeConfigCodec.encode(config)
        assertEquals(config, PrivateProbeConfigCodec.decode(encoded))
        for (secret in listOf(url, "body-secret", "health.status", "json-secret",
            "X-Private-Header", "header-secret")) {
            assertFalse(config.toString().contains(secret))
            assertFalse(condition.toString().contains(secret))
        }
    }
    @Test fun validationErrorsAndDebugRenderingDoNotContainCredentials() {
        val config = PrivateProbeConfig(url, condition)
        for (secret in listOf(url, "private-field", "private-value")) assertFalse(config.toString().contains(secret))
        for (raw in listOf("{private-value", "{\"version\":42,\"url\":\"$url\"}",
            "{\"version\":1,\"url\":\"$url\",\"field\":\"private-field\"}",
            "{\"version\":1,\"url\":\"$url\",\"headers\":[]}",
            "{\"version\":1,\"url\":\"$url\",\"url\":\"$url\"}",
            "http://private.example/private-value")) {
            val error = assertThrows(IllegalArgumentException::class.java) { PrivateProbeConfigCodec.decode(raw) }
            assertEquals("Invalid private probe configuration", error.message)
            assertNull(error.cause)
        }
    }
    @Test fun emptyExpectedValueIsNotConfusedWithMissingValue() {
        val config = PrivateProbeConfig(url, HttpProbeCondition(jsonField = "status", expectedValue = ""))
        assertEquals(config, PrivateProbeConfigCodec.decode(PrivateProbeConfigCodec.encode(config)))
        assertEquals(PrivateProbeConfig(url), PrivateProbeConfigCodec.decode(PrivateProbeConfigCodec.encode(
            PrivateProbeConfig(url))))
    }
}
