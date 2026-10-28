package ru.tgwatch

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiWebhookReceiverTest {
    @Test fun recipientsRouteTheirOwnTargetsAndKeepIndependentRetryState() {
        val lock = Any()
        var now = 0L
        val configs = arrayOf(
            WebhookConfiguration(URL_A, true, 0, WebhookFormat.JSON,
                services = setOf("telegram"), receiverId = 0),
            WebhookConfiguration(URL_B, true, 0, WebhookFormat.JSON,
                services = setOf("matrix"), receiverId = 1)
        )
        val results = Array(2) { mutableListOf<Pair<String, String>>() }
        val completions = Array(2) { mutableListOf<(Int) -> Unit>() }
        val coordinators = configs.indices.map { id ->
            WebhookCoordinator(lock, { synchronized(lock) { configs[id] } }, { now },
                { url, body, done ->
                    results[id] += url to body.bytes.toString(Charsets.UTF_8)
                    completions[id] += done
                    true
                }, schedule = { _, _ -> {} })
        }
        fun publish(service: String) {
            val observation = WebhookObservation(service, "DOWN", now + 1_000, null,
                NetworkKind.WIFI, 1, true, false)
            coordinators.forEach { it.onPublished(observation) }
        }

        publish("telegram")
        publish("matrix")

        assertEquals(1, results[0].size)
        assertEquals(1, results[1].size)
        assertEquals(URL_A, results[0].single().first)
        assertEquals(URL_B, results[1].single().first)
        assertEquals("telegram", JSONObject(results[0].single().second).getString("service_id"))
        assertEquals("matrix", JSONObject(results[1].single().second).getString("service_id"))

        completions[0].single()(500)
        completions[1].single()(204)
        now = WebhookPolicy.MIN_GAP_MS
        publish("telegram")

        assertEquals("Failed delivery remains retryable for only its own recipient", 2, results[0].size)
        assertEquals("A successful sibling does not inherit the retry", 1, results[1].size)
        assertNull(coordinators[0].health().lastSuccessElapsed)
        assertTrue(coordinators[1].health().lastSuccessElapsed != null)
    }

    @Test fun resettingOneReceiverDoesNotInvalidateSiblingDeliveryState() {
        val lock = Any()
        val configs = arrayOf(
            WebhookConfiguration(URL_A, true, 0, receiverId = 0),
            WebhookConfiguration(URL_B, true, 0, receiverId = 1)
        )
        val completions = Array(2) { mutableListOf<(Int) -> Unit>() }
        val coordinators = configs.indices.map { id ->
            WebhookCoordinator(lock, { synchronized(lock) { configs[id] } }, { 10L },
                { _, _, done -> completions[id] += done; true }, schedule = { _, _ -> {} })
        }
        val observation = WebhookObservation("telegram", "DOWN", 1_000, null,
            NetworkKind.WIFI, 1, true, false)
        coordinators.forEach { it.onPublished(observation) }

        coordinators[1].reset()
        completions[0].single()(204)
        completions[1].single()(204)

        assertEquals(WebhookOutcome.SENT, coordinators[0].health().outcome)
        assertEquals(WebhookHealth(), coordinators[1].health())
    }

    private companion object {
        const val URL_A = "https://example.org/a"
        const val URL_B = "https://example.org/b"
    }
}
