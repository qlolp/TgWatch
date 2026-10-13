package ru.tgwatch

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class WebhookDeliveryBehaviorTest {
    @Test fun deadlineRequestsCancellationAndRetainsPhysicalSlotAcrossResetAndLateSuccess() {
        val f = Fixture()
        f.coordinator.sendTest { f.results.add(it) }
        f.advanceToDeadline()
        assertEquals(listOf(WebhookNotifier.TestResult.TIMED_OUT), f.results)
        assertEquals(1, f.cancelCount)
        assertTrue(f.coordinator.health().inFlight)
        assertEquals(WebhookOutcome.TIMED_OUT, f.coordinator.health().outcome)
        assertNull(f.coordinator.health().lastSuccessElapsed)
        repeat(100) { f.changeConfiguration(); f.coordinator.onPublished("TG_DOWN") }
        assertEquals(1, f.requests.size)
        assertEquals(1, f.cancelCount)
        f.requests.first()(204)
        assertEquals(listOf(WebhookNotifier.TestResult.TIMED_OUT), f.results)
        assertEquals(WebhookHealth(), f.coordinator.health())
        f.coordinator.onPublished("TG_DOWN")
        assertEquals(2, f.requests.size)
    }

    @Test fun completionAtBoundaryTimesOutEvenWhenTimerIsDelayedAndDuplicateCannotRewriteHealth() {
        val f = Fixture()
        f.coordinator.sendTest { f.results.add(it) }
        f.now = WebhookPolicy.RESULT_DEADLINE_MS
        f.requests.single()(204)
        f.requests.single()(500)
        assertEquals(listOf(WebhookNotifier.TestResult.TIMED_OUT), f.results)
        assertEquals(WebhookOutcome.TIMED_OUT, f.coordinator.health().outcome)
        assertNull(f.coordinator.health().lastSuccessElapsed)
        assertFalse(f.coordinator.health().inFlight)
        assertEquals(0, f.timer.pending.size)
    }

    @Test fun successBeforeDeadlineCancelsTimerAndRetainsLastSuccessAfterLaterFailure() {
        val f = Fixture()
        f.coordinator.onPublished("TG_DOWN")
        f.now = 5
        f.requests.single()(204)
        assertEquals(5L, f.coordinator.health().lastSuccessElapsed)
        assertEquals(0, f.timer.pending.size)
        f.now = 15
        f.coordinator.onPublished("NO_NETWORK")
        f.requests.last()(503)
        assertEquals(WebhookHealth(15, 5, WebhookOutcome.FAILED), f.coordinator.health())
    }

    @Test fun oldGenerationCompletionAndTimerCannotSetCurrentHealth() {
        val f = Fixture()
        f.coordinator.sendTest { f.results.add(it) }
        f.changeConfiguration()
        assertEquals(WebhookHealth(inFlight = true, transportBlocked = true), f.coordinator.health())
        f.advanceToDeadline()
        assertEquals(WebhookHealth(inFlight = true, transportBlocked = true), f.coordinator.health())
        f.requests.single()(204)
        assertEquals(WebhookHealth(), f.coordinator.health())
        assertEquals(listOf(WebhookNotifier.TestResult.TIMED_OUT), f.results)
    }

    @Test fun submissionAndTimerRejectionsReleaseOnlyWorkThatNeverStarted() {
        val f = Fixture()
        f.accept = false
        f.coordinator.sendTest { f.results.add(it) }
        assertEquals(listOf(WebhookNotifier.TestResult.FAILED), f.results)
        assertFalse(f.coordinator.health().inFlight)
        assertEquals(0, f.timer.pending.size)
        f.now = WebhookPolicy.TEST_GAP_MS
        f.timer.reject = true
        f.coordinator.sendTest { f.results.add(it) }
        assertEquals(listOf(WebhookNotifier.TestResult.FAILED, WebhookNotifier.TestResult.FAILED), f.results)
        assertFalse(f.coordinator.health().inFlight)
        assertTrue(f.requests.isEmpty())
    }

    @Test fun earlyTimerRejectionReportsFailureButRetainsRunningPhysicalRequest() {
        val f = Fixture()
        f.coordinator.sendTest { f.results.add(it) }
        f.timer.reject = true
        f.timer.fire()
        assertEquals(listOf(WebhookNotifier.TestResult.FAILED), f.results)
        assertTrue(f.coordinator.health().inFlight)
        assertEquals(1, f.cancelCount)
        f.requests.single()(204)
        assertEquals(WebhookOutcome.FAILED, f.coordinator.health().outcome)
        assertFalse(f.coordinator.health().inFlight)
    }

    @Test fun timerCapacityStaysOneAndReturnsToZeroForRapidCompletedRequests() {
        val f = Fixture()
        repeat(2_000) {
            f.changeConfiguration()
            f.coordinator.onPublished("TG_DOWN")
            assertEquals(1, f.timer.pending.size)
            f.requests.last()(204)
            assertEquals(0, f.timer.pending.size)
        }
        assertEquals(2_000, f.requests.size)
    }

    @Test fun throwingConsumerCannotStrandSlotOrPreventTimeoutCancellation() {
        val f = Fixture()
        f.coordinator.sendTest { error("consumer failure") }
        f.advanceToDeadline()
        assertEquals(1, f.cancelCount)
        f.requests.single()(204)
        assertFalse(f.coordinator.health().inFlight)
        f.coordinator.onPublished("TG_DOWN")
        assertEquals(2, f.requests.size)
    }

    @Test fun completedOldCallbackCannotFreeANewerPhysicalRequestAfterTimeout() {
        val f = Fixture()
        f.coordinator.sendTest { f.results.add(it) }
        f.advanceToDeadline()
        val old = f.requests.single()
        old(204)
        f.coordinator.onPublished("TG_DOWN")
        old(500)
        f.coordinator.onPublished("NO_NETWORK")
        assertEquals(2, f.requests.size)
        assertTrue(f.coordinator.health().inFlight)
        assertEquals(WebhookOutcome.SENDING, f.coordinator.health().outcome)
    }

    @Test fun cancelledQueuedTransportNeverOpensAnAddressAndReportsActualEnd() {
        val queue = mutableListOf<Runnable>()
        val codes = mutableListOf<Int>()
        var opened = 0
        val transport = WebhookTransport(Executor { queue.add(it) }, { opened++; FakeConnection() }, Executor { it.run() })
        assertTrue(transport.submit("https://example.org/secret", "private", "test", codes::add))
        transport.cancel()
        assertFalse(transport.submit("https://example.org/other", "private", "test", codes::add))
        queue.single().run()
        assertEquals(0, opened)
        assertEquals(listOf(-1), codes)
        assertTrue(transport.submit("https://example.org/other", "private", "test", codes::add))
    }

    @Test fun rejectedTransportQueueCanBeRetriedWithoutOpeningAConnection() {
        val transport = WebhookTransport(Executor { throw RejectedExecutionException() }, { error("must not open") })
        repeat(2) { assertFalse(transport.submit("https://example.org", "private", "test") { fail() }) }
    }

    @Test fun slowDnsIgnoringInterruptKeepsSlotUntilOpenActuallyReturns() = blockedTransport(dns = true)

    @Test fun slowResponseIgnoringDisconnectKeepsSlotAndReportsOnlyTimeout() = blockedTransport(dns = false)

    @Test fun concurrentDeadlineCompletionAndSettingsReplacementPublishExactlyOnce() {
        val executor = Executors.newFixedThreadPool(3)
        try {
            repeat(200) {
                val lock = Any()
                var config = WebhookConfiguration("https://example.org/old-secret", true, 0)
                var now = 0L
                var deadline: (() -> Unit)? = null
                var finish: ((Int) -> Unit)? = null
                val results = mutableListOf<WebhookNotifier.TestResult>()
                val coordinator = WebhookCoordinator(lock, { synchronized(lock) { config } }, { now },
                    { _, _, done -> finish = done; true }, { _, action -> deadline = action; {} })
                coordinator.sendTest { results.add(it) }
                now = WebhookPolicy.RESULT_DEADLINE_MS
                val start = CountDownLatch(1)
                val jobs = listOf<() -> Unit>(
                    { start.await(); deadline!!.invoke() },
                    { start.await(); finish!!.invoke(204); finish!!.invoke(500) },
                    { start.await(); synchronized(lock) {
                        config = config.copy(url = "https://example.org/new-secret", generation = 1)
                        coordinator.reset()
                    } }
                ).map { task -> executor.submit { task() } }
                start.countDown()
                jobs.forEach { it.get(2, TimeUnit.SECONDS) }
                assertEquals(listOf(WebhookNotifier.TestResult.TIMED_OUT), results)
                assertEquals(WebhookHealth(), coordinator.health())
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun productionTimerRemovesCancelledJobsImmediately() {
        val field = WebhookDeadlines::class.java.getDeclaredField("timer").apply { isAccessible = true }
        val timer = field.get(WebhookDeadlines) as java.util.concurrent.ScheduledThreadPoolExecutor
        val before = timer.queue.size
        repeat(2_000) {
            val cancel = WebhookDeadlines.schedule(60_000) { fail("Cancelled deadline ran") }
            cancel()
        }
        assertTrue("Rapid completion must not retain one scheduled job per request", timer.queue.size <= before)
    }

    @Test fun cancellationFailureCannotBreakSettingsResetOrFreeThePhysicalSlot() {
        val f = Fixture()
        f.cancel = { error("private transport failure") }
        f.coordinator.sendTest { f.results.add(it) }
        f.changeConfiguration()
        f.advanceToDeadline()
        assertEquals(listOf(WebhookNotifier.TestResult.TIMED_OUT), f.results)
        assertEquals(1, f.cancelCount)
        assertTrue(f.coordinator.health().inFlight)
        f.requests.single()(204)
        assertEquals(WebhookHealth(), f.coordinator.health())
    }

    @Test fun timeoutConsumerEndingOldRequestAndStartingNewOneCannotCancelTheNewTransport() {
        val f = Fixture()
        f.coordinator.sendTest {
            f.results.add(it)
            f.requests.first()(204)
            f.coordinator.onPublished("TG_DOWN")
        }
        f.advanceToDeadline()
        assertEquals(listOf(WebhookNotifier.TestResult.TIMED_OUT), f.results)
        assertEquals(2, f.requests.size)
        assertEquals("The old physical request ended inside its result callback", 0, f.cancelCount)
        assertEquals(WebhookOutcome.SENDING, f.coordinator.health().outcome)
        assertTrue(f.coordinator.health().inFlight)
    }

    @Test fun reentrantSuccessfulCompletionCannotLoseReplacementCompletion() =
        reentrantCompletion(timeoutReplacement = false)

    @Test fun reentrantSuccessfulCompletionCannotLoseReplacementDeadline() =
        reentrantCompletion(timeoutReplacement = true)

    private fun reentrantCompletion(timeoutReplacement: Boolean) {
        val f = Fixture()
        f.coordinator.sendTest {
            f.results.add(it)
            f.requests.first()(500)
            f.now = WebhookPolicy.TEST_GAP_MS
            f.coordinator.sendTest { replacement -> f.results.add(replacement) }
        }
        f.requests.first()(204)
        assertEquals(listOf(WebhookNotifier.TestResult.SENT), f.results)
        assertEquals(2, f.requests.size)
        assertTrue("The outer old completion must retain the replacement's physical identity",
            f.coordinator.health().inFlight)
        assertEquals(WebhookOutcome.SENDING, f.coordinator.health().outcome)
        if (timeoutReplacement) {
            f.advanceToDeadline()
            assertEquals(listOf(WebhookNotifier.TestResult.SENT, WebhookNotifier.TestResult.TIMED_OUT), f.results)
            assertTrue(f.coordinator.health().inFlight)
        }
        f.requests.last()(204)
        assertEquals(2, f.results.size)
        assertEquals(if (timeoutReplacement) WebhookNotifier.TestResult.TIMED_OUT else WebhookNotifier.TestResult.SENT,
            f.results.last())
        assertFalse(f.coordinator.health().inFlight)
        f.coordinator.onPublished("TG_DOWN")
        assertEquals("The replacement completion must release its gate token", 3, f.requests.size)
    }

    private fun blockedTransport(dns: Boolean) {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val connection = FakeConnection { if (!dns) awaitIgnoringInterrupt(entered, release) }
        val transport = WebhookTransport(executor, {
            if (dns) awaitIgnoringInterrupt(entered, release)
            connection
        }, Executor { it.run() })
        val f = Fixture { url, body, done -> transport.submit(url, body, "test") { done(it); finished.countDown() } }
        f.cancel = transport::cancel
        try {
            f.coordinator.sendTest { f.results.add(it) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            f.advanceToDeadline()
            assertEquals(listOf(WebhookNotifier.TestResult.TIMED_OUT), f.results)
            assertTrue(f.coordinator.health().inFlight)
            repeat(100) { f.changeConfiguration(); f.coordinator.onPublished("TG_DOWN") }
            assertFalse(transport.submit("https://example.org/new", "body", "test") { fail() })
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(WebhookNotifier.TestResult.TIMED_OUT), f.results)
            assertFalse(f.coordinator.health().inFlight)
            assertTrue(connection.disconnected)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    private fun awaitIgnoringInterrupt(entered: CountDownLatch, release: CountDownLatch) {
        entered.countDown()
        while (release.count > 0) {
            try { release.await() } catch (_: InterruptedException) { /* Simulate platform DNS/I/O. */ }
        }
    }

    private class FakeConnection(private val response: () -> Unit = {}) : HttpURLConnection(URL("https://example.org")) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getResponseCode(): Int { response(); return 204 }
    }

    private class Fixture(private val submit: ((String, String, (Int) -> Unit) -> Boolean)? = null) {
        var now = 0L
        var config = WebhookConfiguration("https://example.org/private-topic", true, 0)
        var cancelCount = 0
        var cancel: () -> Unit = {}
        var accept = true
        val timer = FakeTimer()
        val results = mutableListOf<WebhookNotifier.TestResult>()
        val requests = mutableListOf<(Int) -> Unit>()
        val coordinator = WebhookCoordinator(Any(), { config }, { now }, { url, body, done ->
            submit?.invoke(url, body, done) ?: if (accept) { requests.add(done); true } else false
        }, timer::schedule, { cancelCount++; cancel() })

        fun changeConfiguration() { config = config.copy(generation = config.generation + 1); coordinator.reset() }
        fun advanceToDeadline() { now += WebhookPolicy.RESULT_DEADLINE_MS; timer.fire() }
    }

    private class FakeTimer {
        val pending = mutableListOf<() -> Unit>()
        var reject = false
        fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
            assertTrue(delayMs > 0)
            if (reject) throw RejectedExecutionException()
            pending.add(action)
            return { pending.remove(action); Unit }
        }
        fun fire() { val tasks = pending.toList(); pending.clear(); tasks.forEach { it() } }
    }
}
