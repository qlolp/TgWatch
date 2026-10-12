package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Controlled settings reads and completions exercise the coordinator used by WebhookNotifier. */
class WebhookCoordinatorTest {
    @Test fun addressSavedAfterSnapshotCannotAdmitTheOldAddress() {
        val fixture = Fixture()
        fixture.settings.afterRead = {
            fixture.settings.save(url = B)
            fixture.coordinator.reset()
        }

        fixture.coordinator.onPublished("TG_DOWN")

        assertTrue("Captured A must not be posted after saving B", fixture.transport.requests.isEmpty())
        fixture.coordinator.onPublished("TG_DOWN")
        assertEquals(B, fixture.transport.requests.single().url)
    }

    @Test fun addressSavedBeforeResetAlreadyInvalidatesTheOldSnapshot() {
        val fixture = Fixture()
        fixture.settings.afterRead = { fixture.settings.save(url = B) }

        fixture.coordinator.onPublished("TG_DOWN")

        assertTrue("Saving settings must invalidate A before any later reset", fixture.transport.requests.isEmpty())
        fixture.coordinator.onPublished("TG_DOWN")
        assertEquals(B, fixture.transport.requests.single().url)
    }

    @Test fun changeAwayAndBackStillInvalidatesAnOldSnapshot() {
        val fixture = Fixture()
        fixture.settings.afterRead = {
            fixture.settings.save(url = B)
            fixture.settings.save(url = A)
        }

        fixture.coordinator.onPublished("TG_DOWN")

        assertTrue("Matching URL text cannot revive an older generation", fixture.transport.requests.isEmpty())
        fixture.coordinator.onPublished("TG_DOWN")
        assertEquals(A, fixture.transport.requests.single().url)
    }

    @Test fun oldSuccessfulCompletionCannotSuppressTheSameOutageAtTheNewAddress() {
        val fixture = Fixture()
        fixture.coordinator.onPublished("TG_DOWN")
        val old = fixture.transport.requests.single()
        fixture.settings.save(url = B)
        fixture.coordinator.reset()
        fixture.coordinator.onPublished("TG_DOWN")
        assertEquals("A remains the only physical request until it completes", 1, fixture.transport.requests.size)

        old.finish(204)
        fixture.coordinator.onPublished("TG_DOWN")

        assertEquals(listOf(A, B), fixture.transport.requests.map { it.url })
        assertEquals(1, fixture.transport.maxActive)
    }

    @Test fun oldFailedCompletionCannotRestoreThePreviousHealthyStatusAtTheNewAddress() {
        val fixture = Fixture()
        fixture.coordinator.onPublished("OK")
        fixture.coordinator.onPublished("TG_DOWN")
        val old = fixture.transport.requests.single()
        fixture.settings.save(url = B)
        fixture.coordinator.reset()

        old.finish(500)
        fixture.coordinator.onPublished("OK")

        assertEquals("New address starts with a silent healthy baseline", 1, fixture.transport.requests.size)
        fixture.coordinator.onPublished("TG_DOWN")
        assertEquals(B, fixture.transport.requests.last().url)
    }

    @Test fun completionObservesSavedSettingsEvenBeforeExplicitReset() {
        val fixture = Fixture()
        fixture.coordinator.onPublished("TG_DOWN")
        val old = fixture.transport.requests.single()
        fixture.settings.save(url = B)

        old.finish(204)
        fixture.coordinator.onPublished("TG_DOWN")

        assertEquals(listOf(A, B), fixture.transport.requests.map { it.url })
    }

    @Test fun duplicateOldStatusCompletionCannotReleaseANewerPhysicalRequest() {
        val fixture = Fixture()
        fixture.coordinator.onPublished("TG_DOWN")
        val old = fixture.transport.requests.single()
        fixture.settings.save(url = B)
        fixture.coordinator.reset()
        old.finish(204)
        fixture.coordinator.onPublished("TG_DOWN")
        val current = fixture.transport.requests.last()

        old.finish(500)
        fixture.coordinator.onPublished("NO_NETWORK")

        assertEquals("Duplicate callback cannot free B's slot", 2, fixture.transport.requests.size)
        assertEquals(1, fixture.transport.maxActive)
        current.finish(204)
        fixture.coordinator.onPublished("NO_NETWORK")
        assertEquals(3, fixture.transport.requests.size)
    }

    @Test fun disablingAfterSnapshotPreventsAdmission() {
        val fixture = Fixture()
        fixture.settings.afterRead = { fixture.settings.save(enabled = false) }

        fixture.coordinator.onPublished("TG_DOWN")

        assertTrue("A status captured before disabling cannot post", fixture.transport.requests.isEmpty())
    }

    @Test fun disableThenEnableStartsNewHistoryWithoutReleasingTheOldRequest() {
        val fixture = Fixture()
        fixture.coordinator.onPublished("TG_DOWN")
        val old = fixture.transport.requests.single()
        fixture.settings.save(enabled = false)
        fixture.coordinator.onPublished("NO_NETWORK")
        fixture.settings.save(enabled = true)
        fixture.coordinator.onPublished("TG_DOWN")
        assertEquals(1, fixture.transport.requests.size)

        old.finish(204)
        fixture.coordinator.onPublished("TG_DOWN")

        assertEquals(2, fixture.transport.requests.size)
        assertEquals(1, fixture.transport.maxActive)
    }

    @Test fun testSendCannotAdmitAnAddressCapturedBeforeItChanges() {
        val fixture = Fixture()
        fixture.settings.afterRead = {
            fixture.settings.save(url = B)
            fixture.coordinator.reset()
        }

        fixture.coordinator.sendTest { fixture.results.add(it) }

        assertTrue(fixture.transport.requests.isEmpty())
        assertEquals(listOf(WebhookNotifier.TestResult.BUSY), fixture.results)
        fixture.coordinator.sendTest { fixture.results.add(it) }
        assertEquals(B, fixture.transport.requests.single().url)
    }

    @Test fun testSendRemainsAvailableWhileDisabledButRespectsThePhysicalSlotAndGap() {
        val fixture = Fixture()
        fixture.coordinator.sendTest { fixture.results.add(it) }
        val old = fixture.transport.requests.single()
        fixture.settings.save(url = B, enabled = false)
        fixture.coordinator.reset()
        fixture.coordinator.sendTest { fixture.results.add(it) }
        assertEquals(listOf(WebhookNotifier.TestResult.BUSY), fixture.results)
        assertEquals(1, fixture.transport.requests.size)
        old.finish(204)
        fixture.coordinator.sendTest { fixture.results.add(it) }
        assertEquals("Settings changes cannot bypass the existing test gap", 1, fixture.transport.requests.size)

        fixture.time = WebhookPolicy.TEST_GAP_MS
        fixture.coordinator.sendTest { fixture.results.add(it) }

        assertEquals(B, fixture.transport.requests.last().url)
        assertEquals(1, fixture.transport.maxActive)
    }

    @Test fun duplicateOldTestCompletionCannotReleaseAnotherRequestOrNotifyTwice() {
        val fixture = Fixture()
        fixture.coordinator.sendTest { fixture.results.add(it) }
        val old = fixture.transport.requests.single()
        fixture.settings.save(url = B)
        fixture.coordinator.reset()
        old.finish(204)
        fixture.coordinator.onPublished("TG_DOWN")
        val current = fixture.transport.requests.last()

        old.finish(500)
        fixture.coordinator.onPublished("NO_NETWORK")

        assertEquals(listOf(WebhookNotifier.TestResult.SENT), fixture.results)
        assertEquals(2, fixture.transport.requests.size)
        current.finish(204)
        fixture.coordinator.onPublished("NO_NETWORK")
        assertEquals(3, fixture.transport.requests.size)
        assertEquals(1, fixture.transport.maxActive)
    }

    @Test fun normalRetriesUnknownSilenceAndHealthyBaselineStillUseTheProductionCoordinator() {
        val fixture = Fixture()
        fixture.coordinator.onPublished("OK")
        fixture.coordinator.onPublished("UNKNOWN")
        assertTrue(fixture.transport.requests.isEmpty())
        fixture.coordinator.onPublished("TG_DOWN")
        fixture.transport.requests.single().finish(500)
        fixture.time = WebhookPolicy.MIN_GAP_MS - 1
        fixture.coordinator.onPublished("TG_DOWN")
        assertEquals(1, fixture.transport.requests.size)
        fixture.time = WebhookPolicy.MIN_GAP_MS
        fixture.coordinator.onPublished("TG_DOWN")
        assertEquals(2, fixture.transport.requests.size)
        fixture.transport.requests.last().finish(204)
        fixture.time += WebhookPolicy.MIN_GAP_MS
        fixture.coordinator.onPublished("TG_DOWN")
        assertEquals(2, fixture.transport.requests.size)
    }

    @Test fun rejectedSubmissionReleasesItsSlotAndStillUsesTheRetryGap() {
        val fixture = Fixture()
        fixture.transport.accept = false
        fixture.coordinator.onPublished("TG_DOWN")
        fixture.transport.accept = true
        fixture.coordinator.onPublished("NO_NETWORK")
        assertEquals(1, fixture.transport.requests.size)
        fixture.transport.requests.single().finish(204)
        fixture.coordinator.onPublished("TG_DOWN")
        assertEquals(1, fixture.transport.requests.size)
        fixture.time = WebhookPolicy.MIN_GAP_MS
        fixture.coordinator.onPublished("TG_DOWN")
        assertEquals(2, fixture.transport.requests.size)
    }

    private class Fixture {
        val settings = FakeSettings()
        val transport = FakeTransport()
        var time = 0L
        val results = mutableListOf<WebhookNotifier.TestResult>()
        val coordinator = WebhookCoordinator(settings.lock, settings::read, { time }, transport::submit)
    }

    private class FakeSettings {
        val lock = Any()
        var afterRead: (() -> Unit)? = null
        private var current = WebhookConfiguration(A, true, 0)

        fun read(): WebhookConfiguration {
            val (snapshot, hook) = synchronized(lock) {
                Pair(current, afterRead.also { afterRead = null })
            }
            hook?.invoke()
            return snapshot
        }

        fun save(url: String? = current.url, enabled: Boolean = current.enabled) = synchronized(lock) {
            current = WebhookConfiguration(url, enabled, current.generation + 1)
        }
    }

    private class FakeTransport {
        val requests = mutableListOf<Request>()
        var accept = true
        private var active = 0
        var maxActive = 0
            private set

        fun submit(url: String, body: String, complete: (Int) -> Unit): Boolean {
            if (!accept) return false
            active++
            maxActive = maxOf(maxActive, active)
            requests.add(Request(url, body, complete) { active-- })
            return true
        }
    }

    private class Request(val url: String, val body: String, private val complete: (Int) -> Unit,
        private val release: () -> Unit) {
        private var completed = false

        fun finish(code: Int) {
            if (!completed) {
                completed = true
                release()
            }
            complete(code)
        }
    }

    private companion object {
        const val A = "https://example.org/a"
        const val B = "https://example.org/b"
    }
}
