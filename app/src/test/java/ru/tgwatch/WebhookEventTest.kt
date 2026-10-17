package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class WebhookEventTest {
    private fun observation(status: String, measured: Boolean = true, stale: Boolean = false) =
        WebhookObservation("telegram", status, 1_000, null, NetworkKind.WIFI, 1, measured, stale)

    @Test fun initialOkIsSilent() {
        val tracker = WebhookEventTracker()
        assertNull(tracker.observe(observation("OK")))
        val down = tracker.observe(observation("TG_DOWN"))!!
        assertEquals(WebhookEventKind.UNREACHABLE, down.kind)
        assertEquals("OK", down.previousStatus)
    }
    @Test fun recoverySurvivesUnknown() {
        val tracker = WebhookEventTracker()
        tracker.observe(observation("TG_DOWN"))
        assertNull(tracker.observe(observation("UNKNOWN")))
        val event = tracker.observe(observation("OK"))!!
        assertEquals(WebhookEventKind.RECOVERY, event.kind)
        assertEquals("DOWN", event.previousStatus)
    }
    @Test fun networkRestoredIsNotOutageRecovery() {
        val tracker = WebhookEventTracker()
        tracker.observe(observation("NO_NETWORK"))
        assertEquals(WebhookEventKind.NETWORK_RESTORED, tracker.observe(observation("OK"))!!.kind)
    }
    @Test fun noPublishForUnmeasuredOrStale() {
        val tracker = WebhookEventTracker()
        tracker.observe(observation("TG_DOWN"))
        assertNull(tracker.observe(observation("OK", measured = false)))
        assertNull(tracker.observe(observation("OK", stale = true)))
        assertEquals(WebhookEventKind.RECOVERY, tracker.observe(observation("OK"))!!.kind)
    }
    @Test fun duplicateStatusDoesNotBecomeAHeartbeat() {
        val tracker = WebhookEventTracker()
        assertNotNull(tracker.observe(observation("TG_DOWN")))
        repeat(100) { assertNull(tracker.observe(observation("TG_DOWN"))) }
    }
    @Test fun targetsHaveIndependentBaselines() {
        val tracker = WebhookEventTracker()
        tracker.observe(observation("TG_DOWN"))
        assertNull(tracker.observe(observation("OK").copy(serviceId = "matrix")))
        assertEquals(WebhookEventKind.RECOVERY, tracker.observe(observation("OK"))!!.kind)
    }
}
