package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

/** The result deadline never proves that the physical transport has actually stopped. */
class WebhookBlockedHealthTest {
    @Test fun unfinishedTimedOutRequestShowsBlockedDeliveryUntilPhysicalCompletion() {
        val fixture = Fixture()
        fixture.coordinator.sendTest { fixture.results.add(it) }
        fixture.now = WebhookPolicy.RESULT_DEADLINE_MS
        fixture.deadline!!.invoke()

        assertEquals(listOf(WebhookNotifier.TestResult.TIMED_OUT), fixture.results)
        assertTrue(fixture.coordinator.health().inFlight)
        assertBlocked(fixture.coordinator.health())
        repeat(100) { fixture.coordinator.onPublished("TG_DOWN") }
        fixture.coordinator.sendTest { fixture.results.add(it) }
        assertEquals(1, fixture.requests.size)
        assertEquals(WebhookNotifier.TestResult.BUSY, fixture.results.last())

        fixture.requests.single()(204)
        assertFalse(fixture.coordinator.health().inFlight)
        assertFalse(text(fixture.coordinator.health()).allKeys().contains(TextKey.COPY_WEBHOOK_HEALTH_TEXT_DELIVERY_BLOCKED_THE_PREVIOUS))
        assertNull(fixture.coordinator.health().lastSuccessElapsed)
        fixture.coordinator.onPublished("TG_DOWN")
        assertEquals(2, fixture.requests.size)
    }

    @Test fun replacingSettingsCannotEraseTheOldPhysicalTransportBlock() {
        val fixture = Fixture()
        fixture.coordinator.sendTest { fixture.results.add(it) }
        fixture.now = WebhookPolicy.RESULT_DEADLINE_MS
        fixture.deadline!!.invoke()
        repeat(100) {
            fixture.configuration = fixture.configuration.copy(generation = it.toLong() + 1)
            fixture.coordinator.reset()
            fixture.coordinator.onPublished("TG_DOWN")
            assertBlocked(fixture.coordinator.health())
        }
        assertEquals(1, fixture.requests.size)

        fixture.requests.single()(204)
        assertEquals(WebhookHealth(), fixture.coordinator.health())
        fixture.coordinator.onPublished("TG_DOWN")
        assertEquals(2, fixture.requests.size)
    }

    @Test fun settingsCancellationShowsBlockedEvenBeforeTheResultDeadline() {
        val fixture = Fixture()
        fixture.coordinator.onPublished("TG_DOWN")
        fixture.configuration = fixture.configuration.copy(enabled = false, generation = 1)
        fixture.coordinator.reset()

        assertBlocked(fixture.coordinator.health())
        fixture.requests.single()(204)
        assertEquals(WebhookHealth(), fixture.coordinator.health())
    }

    @Test fun normallyRunningRequestDoesNotShowBlockedDelivery() {
        val fixture = Fixture()
        fixture.coordinator.sendTest {}
        assertTrue(fixture.coordinator.health().inFlight)
        assertFalse(text(fixture.coordinator.health()).allKeys().contains(TextKey.COPY_WEBHOOK_HEALTH_TEXT_DELIVERY_BLOCKED_THE_PREVIOUS))
        fixture.requests.single()(204)
    }

    private fun assertBlocked(health: WebhookHealth) {
        assertTrue(health.inFlight)
        assertTrue(text(health).allKeys().contains(TextKey.COPY_WEBHOOK_HEALTH_TEXT_DELIVERY_BLOCKED_THE_PREVIOUS))
    }
    private fun text(health: WebhookHealth) = WebhookHealthText.format(health, 20_000)

    private class Fixture {
        var now = 0L
        var configuration = WebhookConfiguration("https://example.org/test-only", true, 0)
        var deadline: (() -> Unit)? = null
        val requests = mutableListOf<(Int) -> Unit>()
        val results = mutableListOf<WebhookNotifier.TestResult>()
        val coordinator = WebhookCoordinator(Any(), { configuration }, { now },
            { _, _, finished -> requests.add(finished); true },
            { _, action -> deadline = action; {} })
    }
}
