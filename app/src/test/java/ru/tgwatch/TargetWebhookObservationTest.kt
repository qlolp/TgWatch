package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class TargetWebhookObservationTest {
    @Test fun delayedDeliveryKeepsActualMeasurementTimeAndCannotSendStaleEvent() {
        val repository = TargetRepository(wallClock = { 900_000L }, elapsedClock = { 900_000L })
        val cfg = repository.configure(TargetDefinition.default(TargetId.MATRIX).copy(enabled = true))
        val report = TargetReport(TargetStatus.DOWN, emptyList(), true, null,
            NetworkKind.VPN, checkedAtMs = 1_000, checkedElapsedMs = 1_000)
        val attempt = TargetAttempt(TargetId.MATRIX, cfg.generation, 0, 0, 500)
        assertTrue(repository.publish(attempt, report))
        val state = repository.snapshot(TargetId.MATRIX)!!
        assertEquals(1_000L, state.checkedAtMs)
        val observation = TargetWebhookObservation.from(state, report, 0, 900_000, 900_000)
        assertEquals("matrix", observation.serviceId)
        assertEquals(NetworkKind.VPN, observation.network)
        assertEquals(60, observation.expectedIntervalSec)
        assertTrue(observation.stale)
        assertNull(WebhookEventTracker().observe(observation))
    }
    @Test fun secondaryStatusUsesGenericDownAndUnmeasuredDataCannotAlert() {
        val state = TargetSnapshot(TargetId.WHATSAPP, TargetStatus.DOWN, 2_000, 2_000)
        val report = TargetReport(TargetStatus.DOWN, emptyList(), false, null)
        val observation = TargetWebhookObservation.from(state, report, 2, 2_500, 2_500)
        assertEquals("DOWN", observation.status)
        assertFalse(observation.measured)
        assertNull(WebhookEventTracker().observe(observation))
        assertEquals(WebhookEventKind.UNREACHABLE,
            WebhookEventTracker().observe(observation.copy(measured = true))!!.kind)
    }
}
