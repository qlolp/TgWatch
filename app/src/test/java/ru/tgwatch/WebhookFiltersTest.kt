package ru.tgwatch

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebhookFiltersTest {
    private class Fixture {
        var now = 0L
        var config = WebhookConfiguration("https://example.org", true, 0, WebhookFormat.JSON)
        val requests = mutableListOf<Pair<WebhookBody, (Int) -> Unit>>()
        val coordinator = WebhookCoordinator(Any(), { config }, { now }, { _, body, done ->
            requests.add(body to done); true
        }, { _, _ -> {} })
        fun publish(status: String) = coordinator.onPublished(WebhookObservation("telegram", status,
            now + 1_000, null, NetworkKind.WIFI, 1, true, false))
        fun json() = JSONObject(requests.last().first.bytes.toString(Charsets.UTF_8))
    }
    @Test fun filteredEventStillUpdatesMeasuredBaseline() {
        val f = Fixture()
        f.config = f.config.copy(events = setOf(WebhookEventKind.RECOVERY))
        f.publish("TG_DOWN")
        assertTrue(f.requests.isEmpty())
        f.publish("OK")
        assertEquals("recovery", f.json().getString("event"))
        assertEquals("DOWN", f.json().getString("previous_status"))
    }
    @Test fun formatChangeRejectsOldCallback() {
        val f = Fixture()
        f.publish("TG_DOWN")
        f.config = f.config.copy(format = WebhookFormat.TEXT, generation = 1)
        f.publish("TG_DOWN")
        assertEquals(1, f.requests.size)
        f.requests.first().second(204)
        assertNull(f.coordinator.health().lastSuccessElapsed)
        f.publish("TG_DOWN")
        assertEquals(2, f.requests.size)
        assertEquals("text/plain; charset=utf-8", f.requests.last().first.contentType)
        f.requests.first().second(500)
        assertTrue(f.coordinator.health().inFlight)
    }
    @Test fun testDoesNotChangeBaseline() {
        val f = Fixture()
        f.publish("OK")
        f.coordinator.sendTest { assertEquals(WebhookNotifier.TestResult.SENT, it) }
        assertEquals("test", f.json().getString("event"))
        assertTrue(f.json().isNull("status"))
        f.requests.last().second(204)
        f.publish("TG_DOWN")
        assertEquals("OK", f.json().getString("previous_status"))
        f.requests.last().second(204)
        f.now = WebhookPolicy.TEST_GAP_MS
        f.coordinator.sendTest { assertEquals(WebhookNotifier.TestResult.FAILED, it) }
        f.requests.last().second(500)
        f.publish("OK")
        assertEquals("recovery", f.json().getString("event"))
        assertEquals("DOWN", f.json().getString("previous_status"))
    }
}
