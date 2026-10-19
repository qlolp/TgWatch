package ru.tgwatch

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebhookHeartbeatDeliveryTest {
    private class Fixture {
        var now = 0L
        var configuration = WebhookConfiguration("https://private.example/hook", true, 1, WebhookFormat.JSON_V2)
        var accept = true
        var submissions = 0
        var cancellations = 0
        var maxActive = 0
        data class Request(val json: JSONObject, val done: (Int) -> Unit, var completed: Boolean = false)
        val requests = mutableListOf<Request>()
        val coordinator = WebhookCoordinator(Any(), { configuration }, { now }, { _, body, done ->
            submissions++
            if (!accept) false else {
                requests.add(Request(JSONObject(body.bytes.toString(Charsets.UTF_8)), done))
                maxActive = maxOf(maxActive, requests.count { !it.completed })
                true
            }
        }, schedule = { _, _ -> {} }, cancelTransport = { cancellations++ })
        private var revision = 0L
        fun publish(id: String = "telegram", status: String = "OK", measured: Boolean = true,
            stale: Boolean = false) = coordinator.onPublished(
            WebhookObservation(id, status, 1000 + ++revision, null, NetworkKind.WIFI, revision, measured, stale, 60))
        fun finish(index: Int = requests.lastIndex, code: Int = 204) {
            val request = requests[index]
            request.completed = true
            request.done(code)
        }
    }
    @Test fun newerInvalidObservationEvictsWaitingHeartbeatWithoutChangingMeasuredBaseline() {
        for (invalid in listOf("unknown", "unmeasured", "stale")) {
            val fixture = Fixture()
            fixture.publish("matrix", "DOWN")
            fixture.publish()
            fixture.publish(status = if (invalid == "unknown") "UNKNOWN" else "OK",
                measured = invalid != "unmeasured", stale = invalid == "stale")
            fixture.finish()
            fixture.publish("custom")
            assertEquals(invalid, "custom", fixture.requests.last().json.getString("service_id"))
            fixture.finish()
            fixture.publish(status = "DOWN")
            assertEquals(invalid, "OK", fixture.requests.last().json.getString("previous_status"))
            assertEquals(1, fixture.maxActive)
        }
    }
    @Test fun newerUnknownObservationEvictsWaitingEventWithoutResettingRecoveryBaseline() {
        val fixture = Fixture()
        fixture.publish("matrix", "DOWN")
        fixture.publish(status = "DOWN")
        fixture.publish(status = "UNKNOWN", measured = false)
        fixture.finish()
        fixture.publish("custom")
        assertEquals("custom", fixture.requests.last().json.getString("service_id"))
        fixture.finish()
        fixture.publish()
        assertEquals("recovery", fixture.requests.last().json.getString("event"))
        assertEquals("DOWN", fixture.requests.last().json.getString("previous_status"))
    }
    @Test fun eventFiltersAlsoRestrictHeartbeatStatusWithoutChangingMeasuredBaseline() {
        val fixture = Fixture()
        fixture.configuration = fixture.configuration.copy(events = setOf(WebhookEventKind.UNREACHABLE))
        fixture.publish()
        assertTrue(fixture.requests.isEmpty())
        fixture.publish(status = "DOWN")
        assertEquals("OK", fixture.requests.single().json.getString("previous_status"))
        fixture.finish()
        fixture.now = 300000
        fixture.publish(status = "DOWN")
        assertEquals("heartbeat", fixture.requests.last().json.getString("event"))
        fixture.finish()
        fixture.publish(status = "OK")
        assertEquals(2, fixture.requests.size)
        fixture.publish(status = "DOWN")
        assertEquals("unreachable", fixture.requests.last().json.getString("event"))
        assertEquals("OK", fixture.requests.last().json.getString("previous_status"))
        fixture.configuration = fixture.configuration.copy(generation = 2, events = emptySet())
        fixture.finish()
        fixture.now += 300000
        fixture.publish("matrix")
        assertEquals(3, fixture.requests.size)
    }

    @Test fun pendingLegacyDownRefreshesForEveryFreshMeasurementWhileTheLaneIsBlocked() {
        val fixture = Fixture()
        fixture.configuration = fixture.configuration.copy(format = WebhookFormat.JSON)
        fixture.publish("matrix", "DOWN")
        fixture.publish(status = "TG_DOWN")
        for (time in listOf(60000L, 120000L, 180000L, 240001L)) {
            fixture.now = time
            fixture.publish(status = "TG_DOWN")
        }
        fixture.finish(0)
        fixture.publish("custom")
        assertEquals(2, fixture.requests.size)
        assertEquals("telegram", fixture.requests.last().json.getString("service_id"))
        assertEquals("unreachable", fixture.requests.last().json.getString("event"))
        assertEquals(1006, fixture.requests.last().json.getLong("checked_at_ms"))
    }

    @Test fun initialOkSendsOnlyInTheExplicitNewFormat() {
        val fixture = Fixture()
        fixture.publish()
        assertEquals("heartbeat", fixture.requests.single().json.getString("event"))
        assertTrue(fixture.requests.single().json.isNull("previous_status"))
        assertEquals(1001, fixture.requests.single().json.getLong("checked_at_ms"))
        fixture.finish()
        fixture.publish()
        assertEquals(1, fixture.requests.size)
    }
    @Test fun eventsHavePriorityOverWaitingHeartbeatsAcrossFourGoals() {
        val fixture = Fixture()
        fixture.publish("telegram")
        fixture.publish("matrix")
        fixture.publish("whatsapp", "DOWN")
        fixture.publish("custom")
        assertEquals(1, fixture.requests.size)
        fixture.finish()
        fixture.publish("custom")
        assertEquals("unreachable", fixture.requests.last().json.getString("event"))
        assertEquals("whatsapp", fixture.requests.last().json.getString("service_id"))
        assertEquals(1, fixture.maxActive)
    }
    @Test fun rejectedSubmitDoesNotConsumeButAdmittedFailureConsumesFiveMinutes() {
        val fixture = Fixture()
        fixture.accept = false
        fixture.publish(); fixture.publish()
        assertEquals(2, fixture.submissions)
        fixture.accept = true
        fixture.publish()
        assertEquals(1, fixture.requests.size)
        fixture.finish(code = 500)
        fixture.now = 299999
        fixture.publish()
        assertEquals(1, fixture.requests.size)
        fixture.now = 300000
        fixture.publish()
        assertEquals(2, fixture.requests.size)
    }
    @Test fun expiredWaitingMeasurementAndAlreadyAdmittedMeasurementAreNeverResent() {
        val fixture = Fixture()
        fixture.publish("telegram")
        fixture.publish("matrix")
        fixture.now = 180001
        fixture.finish()
        fixture.publish("whatsapp")
        assertEquals("whatsapp", fixture.requests.last().json.getString("service_id"))
        fixture.finish()
        fixture.now = 300000
        fixture.publish("custom")
        assertEquals("custom", fixture.requests.last().json.getString("service_id"))
        assertEquals(1, fixture.requests.count { it.json.getString("service_id") == "telegram" })
        assertEquals(0, fixture.requests.count { it.json.getString("service_id") == "matrix" })
    }
    @Test fun heartbeatCompletionCannotRemoveRecoveryOrChangeItsPreviousStatus() {
        val fixture = Fixture()
        fixture.publish(status = "DOWN")
        fixture.finish()
        fixture.now = 300000
        fixture.publish(status = "DOWN")
        assertEquals("heartbeat", fixture.requests.last().json.getString("event"))
        fixture.publish(status = "OK")
        fixture.finish()
        fixture.publish(status = "OK")
        assertEquals("recovery", fixture.requests.last().json.getString("event"))
        assertEquals("DOWN", fixture.requests.last().json.getString("previous_status"))
    }
    @Test fun formatChangeAndOldCompletionCannotReleaseOrUpdateTheNewGeneration() {
        val fixture = Fixture()
        fixture.publish()
        fixture.configuration = fixture.configuration.copy(generation = 2, format = WebhookFormat.JSON)
        fixture.publish(status = "DOWN")
        assertEquals(1, fixture.requests.size)
        assertEquals(1, fixture.cancellations)
        fixture.finish()
        assertNull(fixture.coordinator.health().lastSuccessElapsed)
        fixture.publish(status = "DOWN")
        assertEquals(1, fixture.requests.last().json.getInt("schema_version"))
        assertEquals(1, fixture.maxActive)
    }
    @Test fun forgettingHeartbeatGoalCancelsItsLogicalRequestWithoutReleasingPhysicalSlot() {
        val fixture = Fixture()
        fixture.publish()
        fixture.coordinator.forget("telegram")
        assertEquals(1, fixture.cancellations)
        fixture.publish("matrix")
        assertEquals(1, fixture.requests.size)
        fixture.finish()
        fixture.publish("matrix")
        assertEquals("matrix", fixture.requests.last().json.getString("service_id"))
        assertEquals(1, fixture.maxActive)
    }
}
