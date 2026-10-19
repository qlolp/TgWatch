package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class SurfaceTextValueTest {
    @Test fun notificationContentRetainsSemanticValuesAndMeasuredChronology() {
        val state = MonitorService.State(MonitorService.Status.OK, 1000, since = 500, latencyMs = 42)
        val view = OngoingNotification.view(state, TimeStats(60000, 0, 0, 0, 0, 0, 1, 42), 1100)
        assertEquals(1000L, view.whenMs)
        assertFalse(view.chronometer)
        val title = view.notif.title
        assertTrue(title.args.contains(TextArgument.Number(42)))
        assertNotNull(view.notif.text.key)
    }
    @Test fun textWebhookDefersLanguageToTheAndroidBoundaryAndKeepsOnlyFixedGoalData() {
        val event = WebhookEvent(WebhookEventKind.UNREACHABLE,
            WebhookObservation("custom", "DOWN", 1000, null, NetworkKind.WIFI, 1, true, false), "OK")
        val body = WebhookPayload.encode(event, WebhookFormat.TEXT)
        assertNotNull(body.textValue)
        assertTrue(body.bytes.isEmpty())
        assertTrue(body.textValue!!.args.any { it == TextArgument.Value(TargetNames.text(TargetId.CUSTOM)) })
        assertNull(WebhookPayload.encode(event, WebhookFormat.JSON).textValue)
    }
    @Test fun webhookDeliveryHealthKeepsBlockedStateAndElapsedAgesTyped() {
        val text = WebhookHealthText.format(WebhookHealth(outcome = WebhookOutcome.TIMED_OUT,
            lastAttemptElapsed = 1000, inFlight = true, transportBlocked = true), 6000)
        assertNotNull(text.key)
        fun values(value: TextValue): List<TextArgument> = value.args +
            value.args.filterIsInstance<TextArgument.Value>().flatMap { values(it.value) }
        assertTrue(values(text).contains(TextArgument.Number(5)))
    }
}
