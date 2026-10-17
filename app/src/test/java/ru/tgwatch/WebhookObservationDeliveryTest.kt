package ru.tgwatch

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebhookObservationDeliveryTest {
    private class Fixture {
        var now = 0L
        var config = WebhookConfiguration("https://example.org", true, 0).copy(format = WebhookFormat.JSON)
        val requests = mutableListOf<Pair<WebhookBody, (Int) -> Unit>>()
        val coordinator = WebhookCoordinator(Any(), { config }, { now }, { _, body, done ->
            requests.add(body to done); true
        }, { _, _ -> {} })
        fun publish(status: String, measured: Boolean = true, stale: Boolean = false) = coordinator.onPublished(
            WebhookObservation("telegram", status, 1_000 + now, null, NetworkKind.WIFI, 1, measured, stale))
        fun json() = JSONObject(requests.last().first.bytes.toString(Charsets.UTF_8))
    }

    @Test fun previousIsMeasuredRatherThanLastDeliveredAndBusyKeepsOnlyCurrentEvent() {
        val f = Fixture()
        f.publish("TG_DOWN")
        f.publish("PARTIAL")
        f.publish("UNKNOWN")
        f.publish("OK")
        assertEquals(1, f.requests.size)
        f.requests.first().second(500)
        f.publish("OK")
        assertEquals(2, f.requests.size)
        assertEquals("recovery", f.json().getString("event"))
        assertEquals("PARTIAL", f.json().getString("previous_status"))
        f.requests.last().second(204)
        f.now += WebhookPolicy.MIN_GAP_MS
        f.publish("OK")
        assertEquals(2, f.requests.size)
    }

    @Test fun staleAndUnmeasuredCannotReplacePendingMeasuredFailure() {
        val f = Fixture()
        f.publish("TG_DOWN")
        f.requests.last().second(500)
        f.publish("OK", measured = false)
        f.publish("OK", stale = true)
        f.now = WebhookPolicy.MIN_GAP_MS
        f.publish("TG_DOWN")
        assertEquals(2, f.requests.size)
        assertEquals("unreachable", f.json().getString("event"))
        assertTrue(f.json().isNull("previous_status"))
    }
}
