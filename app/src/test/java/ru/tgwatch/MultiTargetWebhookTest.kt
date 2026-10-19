package ru.tgwatch

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MultiTargetWebhookTest {
    private class Fixture {
        var now = 0L
        var active = 0
        var maximum = 0
        var cancellations = 0
        var config = WebhookConfiguration("https://example.org/webhook", true, 0, WebhookFormat.JSON)
        val requests = mutableListOf<Pair<WebhookBody, (Int) -> Unit>>()
        val coordinator = WebhookCoordinator(Any(), { config }, { now }, { _, body, done ->
            active++
            maximum = maxOf(maximum, active)
            var ended = false
            requests += body to { code ->
                if (!ended) { ended = true; active-- }
                done(code)
            }
            true
        }, { _, _ -> {} }, { cancellations++ })
        fun publish(id: String, status: String) = coordinator.onPublished(WebhookObservation(id, status,
            now + 1000, null, NetworkKind.WIFI, 1, true, false,
            expectedIntervalSec = if (id == "telegram") 10 else 60))
        fun service() = JSONObject(requests.last().first.bytes.toString(Charsets.UTF_8)).getString("service_id")
        fun finish() = requests.last().second(204)
    }

    @Test fun otherTargetsNeverOverwritePendingEventsAndShareOnePhysicalDelivery() {
        val f = Fixture()
        f.publish("telegram", "DOWN")
        f.publish("matrix", "DOWN")
        f.publish("whatsapp", "DOWN")
        assertEquals(1, f.requests.size)
        f.finish()
        f.publish("telegram", "DOWN")
        assertEquals("matrix", f.service())
        f.finish()
        f.publish("telegram", "DOWN")
        assertEquals("whatsapp", f.service())
        assertEquals(3, f.requests.size)
        assertEquals(1, f.maximum)
    }

    @Test fun aNewOutageAfterFilteredRecoveryIsDeliveredAfterItsOwnCooldown() {
        val f = Fixture()
        f.config = f.config.copy(events = setOf(WebhookEventKind.UNREACHABLE))
        f.publish("telegram", "DOWN")
        f.finish()
        f.publish("telegram", "OK")
        f.now = WebhookPolicy.MIN_GAP_MS
        f.publish("telegram", "DOWN")
        assertEquals(2, f.requests.size)
        assertEquals("telegram", f.service())
    }

    @Test fun disablingOneTargetForgetsOnlyItsPendingEventAndMeasuredBaseline() {
        val f = Fixture()
        f.publish("telegram", "DOWN")
        f.publish("matrix", "DOWN")
        f.coordinator.forget("matrix")
        f.finish()
        f.publish("matrix", "OK")
        assertEquals(1, f.requests.size)
        f.publish("telegram", "OK")
        assertEquals(2, f.requests.size)
        assertEquals("telegram", f.service())
    }

    @Test fun forgettingAnInFlightTargetKeepsThePhysicalLeaseUntilActualCompletion() {
        val f = Fixture()
        f.publish("matrix", "DOWN")
        f.coordinator.forget("matrix")
        assertEquals(1, f.cancellations)
        assertTrue(f.coordinator.health().inFlight)
        f.publish("telegram", "DOWN")
        assertEquals(1, f.requests.size)
        f.finish()
        assertNull(f.coordinator.health().lastSuccessElapsed)
        assertNotEquals(WebhookOutcome.SENDING, f.coordinator.health().outcome)
        f.publish("telegram", "DOWN")
        assertEquals("telegram", f.service())
        assertEquals(1, f.maximum)
    }

    @Test fun anotherTargetCannotFlushAnExpiredEventWithoutANewMeasurement() {
        val f = Fixture()
        f.publish("matrix", "DOWN")
        f.publish("telegram", "DOWN")
        f.finish()
        f.now = 31_000
        f.publish("whatsapp", "OK")
        assertEquals(1, f.requests.size)
        f.publish("telegram", "DOWN")
        assertEquals(2, f.requests.size)
        assertEquals("telegram", f.service())
    }

    @Test fun textPayloadNamesTheMeasuredTarget() {
        val event = WebhookEvent(WebhookEventKind.UNREACHABLE,
            WebhookObservation("matrix", "DOWN", 1000, null, NetworkKind.WIFI, 1, true, false), "OK")
        val body = checkNotNull(WebhookPayload.encode(event, WebhookFormat.TEXT).textValue)
        assertTrue(body.allKeys().contains(TextKey.TARGET_MATRIX))
        assertFalse(body.allKeys().contains(TextKey.TARGET_TELEGRAM))
    }
}
