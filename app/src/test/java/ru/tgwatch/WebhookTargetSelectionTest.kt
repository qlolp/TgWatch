package ru.tgwatch

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebhookTargetSelectionTest {
    private class Fixture(services: Set<String>, format: WebhookFormat = WebhookFormat.JSON_V2) {
        var now = 0L
        var revision = 0L
        var config = WebhookConfiguration("https://example.org/hook", true, 1, format, services = services)
        var active = 0
        var maximum = 0
        var cancellations = 0
        val requests = mutableListOf<Pair<JSONObject, (Int) -> Unit>>()
        val coordinator = WebhookCoordinator(Any(), { config }, { now }, { _, body, done ->
            active++
            maximum = maxOf(maximum, active)
            var ended = false
            requests += JSONObject(body.bytes.toString(Charsets.UTF_8)) to { code ->
                if (!ended) { ended = true; active-- }
                done(code)
            }
            true
        }, schedule = { _, _ -> {} }, cancelTransport = { cancellations++ })
        fun publish(service: String, status: String = "DOWN") = coordinator.onPublished(
            WebhookObservation(service, status, 1_000 + ++revision, null, NetworkKind.WIFI,
                revision, true, false, 60))
        fun finish() = requests.last().second(204)
    }

    @Test fun excludedServicesNeverDeliverEventsOrHeartbeats() {
        for (format in listOf(WebhookFormat.JSON, WebhookFormat.JSON_V2)) {
            val fixture = Fixture(setOf("matrix"), format)
            for (service in listOf("telegram", "whatsapp", "custom")) {
                fixture.publish(service)
                fixture.publish(service, "OK")
            }
            assertTrue(format.name, fixture.requests.isEmpty())
            fixture.publish("matrix")
            assertEquals("matrix", fixture.requests.single().first.getString("service_id"))
        }
    }

    @Test fun eachBuiltInGoalCanBeTheOnlySelectedRecipient() {
        for (service in WebhookStatuses.services) {
            val fixture = Fixture(setOf(service))
            fixture.publish(service, "OK")
            assertEquals(service, fixture.requests.single().first.getString("service_id"))
            assertEquals("heartbeat", fixture.requests.single().first.getString("event"))
        }
    }

    @Test fun removingASelectedGoalFencesItsLateCallbackAndKeepsItsPhysicalLease() {
        val fixture = Fixture(setOf("matrix"))
        fixture.publish("matrix")
        fixture.config = fixture.config.copy(services = setOf("telegram"), generation = 2)
        fixture.publish("telegram")
        assertEquals(1, fixture.requests.size)
        assertEquals(1, fixture.cancellations)
        assertTrue(fixture.coordinator.health().inFlight)
        fixture.finish()
        assertNull(fixture.coordinator.health().lastSuccessElapsed)
        fixture.publish("telegram")
        assertEquals("telegram", fixture.requests.last().first.getString("service_id"))
        fixture.finish()
        fixture.now += WebhookHeartbeatPolicy.INTERVAL_MS
        fixture.publish("matrix", "OK")
        assertEquals(2, fixture.requests.size)
        assertEquals(1, fixture.maximum)
    }

    @Test fun anEmptySelectionAllowsExplicitTestMessagesButNoMeasurements() {
        val fixture = Fixture(emptySet())
        WebhookStatuses.services.forEach { fixture.publish(it) }
        assertTrue(fixture.requests.isEmpty())
        fixture.coordinator.sendTest { }
        assertEquals("test", fixture.requests.single().first.getString("event"))
        assertFalse(fixture.requests.single().first.getBoolean("monitoring"))
    }
}
