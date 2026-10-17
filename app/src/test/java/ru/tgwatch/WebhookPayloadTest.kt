package ru.tgwatch

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebhookPayloadTest {
    private val keys = setOf("schema_version", "service_id", "event", "status", "previous_status",
        "checked_at_ms", "latency_ms", "network")
    @Test fun jsonUsesNullableLatencyAndAllowlist() {
        val event = WebhookEvent(WebhookEventKind.UNREACHABLE,
            WebhookObservation("telegram", "TG_DOWN", 1_000, null, NetworkKind.VPN, 5, true, false), "OK")
        val body = WebhookPayload.encode(event, WebhookFormat.JSON)
        assertEquals("application/json; charset=utf-8", body.contentType)
        val json = JSONObject(body.bytes.toString(Charsets.UTF_8))
        assertEquals(keys, json.keys().asSequence().toSet())
        assertEquals(1, json.getInt("schema_version"))
        assertEquals("DOWN", json.getString("status"))
        assertEquals("unreachable", json.getString("event"))
        assertEquals("VPN", json.getString("network"))
        assertTrue(json.isNull("latency_ms"))
        assertEquals(1_000L, json.getLong("checked_at_ms"))
    }
    @Test fun testIsNotAMeasurement() {
        val json = JSONObject(WebhookPayload.encode(WebhookEvent(WebhookEventKind.TEST, null, null),
            WebhookFormat.JSON).bytes.toString(Charsets.UTF_8))
        assertEquals("test", json.getString("event"))
        for (key in listOf("status", "previous_status", "checked_at_ms", "latency_ms")) assertTrue(json.isNull(key))
        assertEquals(keys, json.keys().asSequence().toSet())
    }
    @Test fun textRemainsTheDefaultLegacyPayload() {
        val tracker = WebhookEventTracker()
        val event = tracker.observe(WebhookObservation("telegram", "TG_DOWN", 10, 50,
            NetworkKind.WIFI, 1, true, false))!!
        val body = WebhookPayload.encode(event, WebhookFormat.TEXT)
        assertEquals("text/plain; charset=utf-8", body.contentType)
        assertEquals(WebhookPolicy.payload("TG_DOWN"), body.bytes.toString(Charsets.UTF_8))
    }
    @Test fun invalidLatencyIsNullAndUntrustedTargetNamesCannotEscape() {
        val event = WebhookEvent(WebhookEventKind.PARTIAL,
            WebhookObservation("https://secret.example/token", "PARTIAL", 1, -1,
                NetworkKind.UNKNOWN, 1, true, false), "TG_DOWN")
        val json = JSONObject(WebhookPayload.encode(event, WebhookFormat.JSON).bytes.toString(Charsets.UTF_8))
        assertEquals("unknown", json.getString("service_id"))
        assertEquals("DOWN", json.getString("previous_status"))
        assertTrue(json.isNull("latency_ms"))
        assertFalse(json.toString().contains("secret"))
    }
}
