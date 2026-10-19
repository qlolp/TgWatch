package ru.tgwatch

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebhookHeartbeatPolicyTest {
    private fun observation(id: String = "telegram") = WebhookObservation(id, "OK", 1234,
        20, NetworkKind.WIFI, 1, true, false, expectedIntervalSec = 600)
    private val legacyKeys = setOf("schema_version", "service_id", "event", "status", "previous_status",
        "checked_at_ms", "latency_ms", "network")
    @Test fun firstFreshOkAndExactFiveMinuteBoundaryAreEligible() {
        val policy = WebhookHeartbeatPolicy()
        assertTrue(policy.due(observation(), 0))
        assertTrue(policy.due(observation(), 0))
        policy.started("telegram", 0)
        assertFalse(policy.due(observation(), 299999))
        assertTrue(policy.due(observation(), 300000))
    }
    @Test fun unknownStaleUnmeasuredMissingTimeAndUnsupportedIdsNeverBecomeHealthy() {
        val policy = WebhookHeartbeatPolicy()
        val good = observation()
        for (invalid in listOf(good.copy(status = "UNKNOWN"), good.copy(stale = true),
            good.copy(measured = false), good.copy(checkedAtMs = 0), good.copy(serviceId = "unknown"))) {
            assertFalse(policy.due(invalid, 500000))
        }
        assertFalse(policy.due(good, -1))
        for (id in listOf("one", "two", "three", "four", "five")) {
            policy.started(id, 100)
            assertFalse(policy.due(good.copy(serviceId = id), 400000))
        }
        assertTrue(policy.due(good, 0))
    }
    @Test fun fourGoalsAreIndependentAndForgetResetAndElapsedRollbackAreSafe() {
        val policy = WebhookHeartbeatPolicy()
        for (id in listOf("telegram", "matrix", "whatsapp", "custom")) {
            assertTrue(policy.due(observation(id), 100))
            policy.started(id, 100)
            assertFalse(policy.due(observation(id), 101))
        }
        assertTrue(policy.due(observation(), 99))
        policy.started("telegram", 99)
        policy.forget("matrix")
        assertTrue(policy.due(observation("matrix"), 101))
        assertFalse(policy.due(observation("custom"), 101))
        policy.reset()
        assertTrue(policy.due(observation("custom"), 101))
    }
    @Test fun heartbeatPayloadUsesSourceMeasurementAndExactlyElevenAllowlistedKeys() {
        val event = WebhookEvent(WebhookEventKind.HEARTBEAT, observation(), "DOWN")
        val body = WebhookPayload.encode(event, WebhookFormat.JSON_V2, 9999)
        val json = JSONObject(body.bytes.toString(Charsets.UTF_8))
        assertEquals("application/json; charset=utf-8", body.contentType)
        assertEquals(legacyKeys + setOf("sent_at_ms", "monitoring", "is_stale"), json.keys().asSequence().toSet())
        assertEquals(2, json.getInt("schema_version"))
        assertEquals("heartbeat", json.getString("event"))
        assertTrue(json.isNull("previous_status"))
        assertEquals(1234, json.getLong("checked_at_ms"))
        assertEquals(9999, json.getLong("sent_at_ms"))
        assertTrue(json.getBoolean("monitoring"))
        assertFalse(json.getBoolean("is_stale"))
        assertFalse(WebhookEventKind.DELIVERY_EVENTS.contains(WebhookEventKind.HEARTBEAT))
        assertFalse(WebhookEventKind.DELIVERY_EVENTS.contains(WebhookEventKind.TEST))
    }
    @Test fun legacyPayloadKeepsExactlyEightKeysAndTestV2CannotClaimAMeasurement() {
        val event = WebhookEvent(WebhookEventKind.RECOVERY, observation(), "DOWN")
        val legacy = JSONObject(WebhookPayload.encode(event, WebhookFormat.JSON, 9999).bytes.toString(Charsets.UTF_8))
        assertEquals(legacyKeys, legacy.keys().asSequence().toSet())
        assertEquals(1, legacy.getInt("schema_version"))
        val test = JSONObject(WebhookPayload.encode(WebhookEvent(WebhookEventKind.TEST, null, null),
            WebhookFormat.JSON_V2, 9999).bytes.toString(Charsets.UTF_8))
        assertFalse(test.getBoolean("monitoring"))
        assertTrue(test.getBoolean("is_stale"))
        for (key in listOf("status", "previous_status", "checked_at_ms", "latency_ms")) assertTrue(test.isNull(key))
        assertThrows(IllegalArgumentException::class.java) { WebhookPayload.encode(event, WebhookFormat.JSON_V2) }
        assertThrows(IllegalArgumentException::class.java) { WebhookPayload.encode(event, WebhookFormat.JSON_V2, 0) }
    }
    @Test fun trackerBaselineIsUnchangedByIndependentHeartbeatPolicy() {
        val tracker = WebhookEventTracker()
        val policy = WebhookHeartbeatPolicy()
        assertEquals(WebhookEventKind.UNREACHABLE, tracker.observe(observation().copy(status = "DOWN"))!!.kind)
        assertTrue(policy.due(observation(), 100))
        policy.started("telegram", 100)
        val recovery = tracker.observe(observation())!!
        assertEquals(WebhookEventKind.RECOVERY, recovery.kind)
        assertEquals("DOWN", recovery.previousStatus)
    }
}
