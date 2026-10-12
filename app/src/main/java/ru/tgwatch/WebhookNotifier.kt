package ru.tgwatch

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Opt-in POST of one status line to a user-owned HTTPS endpoint (for example an ntfy.sh topic).
 * One background thread, at most one request in flight, bounded timeouts, no redirects.
 * No address, body or transport exception is logged.
 */
object WebhookNotifier {
    enum class TestResult { SENT, FAILED, TIMED_OUT, BUSY, NOT_CONFIGURED }

    private const val IDLE_THREAD_SECONDS = 30L
    @Volatile private var coordinator: WebhookCoordinator? = null
    private val io = ThreadPoolExecutor(1, 1, IDLE_THREAD_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue(1),
        { r -> Thread(r, "tg-webhook").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    private val transport = WebhookTransport(io)
    @Volatile private var userAgent: String? = null

    /** Called by the monitor for every published result; returns immediately. */
    // The monitor worker must never fail because of this optional adapter.
    @Suppress("TooGenericExceptionCaught")
    fun onPublished(ctx: Context, status: MonitorService.Status) {
        try {
            coordinator(ctx).onPublished(status.name)
        } catch (_: RuntimeException) {
            // Optional delivery must never interrupt the monitor.
        }
    }

    /** Explicit user test from the settings screen, even while the switch is off; [done] runs on the main thread. */
    fun sendTest(ctx: Context, done: (TestResult) -> Unit) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        coordinator(app).sendTest { result ->
            if (Looper.myLooper() == main.looper) done(result) else main.post { done(result) }
        }
    }

    /** Address or switch changed: the next change is reported regardless of earlier posts. */
    fun reset() { coordinator?.reset() }

    internal fun health(ctx: Context): WebhookHealth = coordinator(ctx).health()

    private fun coordinator(ctx: Context): WebhookCoordinator = coordinator ?: synchronized(this) {
        coordinator ?: ctx.applicationContext.let { app ->
            WebhookCoordinator(WebhookSettings, { WebhookSettings.configuration(app) },
                SystemClock::elapsedRealtime,
                { url, body, finished -> transport.submit(url, body, userAgent(app), finished) },
                cancelTransport = transport::cancel)
        }.also { coordinator = it }
    }

    private fun userAgent(ctx: Context): String = userAgent ?: WebhookPolicy.userAgent(try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }).also { userAgent = it }
}

/** Android-independent orchestration; the settings monitor protects admission, result and physical identity. */
internal class WebhookCoordinator(
    private val configurationLock: Any,
    private val configuration: () -> WebhookConfiguration,
    private val nowElapsed: () -> Long,
    private val submit: (String, String, (Int) -> Unit) -> Boolean,
    private val schedule: (Long, () -> Unit) -> (() -> Unit) = WebhookDeadlines::schedule,
    private val cancelTransport: () -> Unit = {}
) {
    private val gate = WebhookGate()
    private var configured: WebhookConfiguration? = null
    private var generation = 0L
    private var active: Attempt? = null
    private var lastHealth = WebhookHealth()

    private class Attempt(val request: WebhookGate.Request, val generation: Long, val started: Long,
        val done: ((WebhookNotifier.TestResult) -> Unit)?) {
        var result: WebhookNotifier.TestResult? = null
        var cancelDeadline: (() -> Unit)? = null
        private var cancellationRequested = false

        @Suppress("TooGenericExceptionCaught")
        fun cancel(transport: () -> Unit) {
            if (!cancellationRequested) {
                cancellationRequested = true
                try {
                    transport()
                } catch (_: RuntimeException) {
                    // Best-effort cancellation cannot prevent settings changes or strand the result.
                }
            }
        }
    }

    fun reset() = synchronized(configurationLock) {
        configured = configuration()
        invalidate()
    }

    fun health(): WebhookHealth = synchronized(configurationLock) {
        synchronizeConfiguration(configuration())
        lastHealth.copy(inFlight = active != null)
    }

    fun onPublished(status: String) {
        val captured = configuration()
        synchronized(configurationLock) {
            val current = configuration()
            synchronizeConfiguration(current)
            val url = current.url?.takeIf { current.enabled }
            val body = WebhookPolicy.payload(status)
            if (captured == current && url != null && body != null) {
                gate.begin(status, nowElapsed())?.let { dispatch(it, url, body) }
            }
        }
    }

    fun sendTest(done: (WebhookNotifier.TestResult) -> Unit) {
        val captured = configuration()
        synchronized(configurationLock) {
            val current = configuration()
            synchronizeConfiguration(current)
            val url = current.url
            val request = if (url != null && captured == current) gate.beginTest(nowElapsed()) else null
            when {
                url == null -> done(WebhookNotifier.TestResult.NOT_CONFIGURED)
                request == null -> done(WebhookNotifier.TestResult.BUSY)
                else -> dispatch(request, url, WebhookPolicy.testPayload(), done)
            }
        }
    }

    /** Caller holds settings monitor; submit/cancel only enqueue work and never perform network I/O. */
    @Suppress("TooGenericExceptionCaught")
    private fun dispatch(request: WebhookGate.Request, url: String, body: String,
        done: ((WebhookNotifier.TestResult) -> Unit)? = null) {
        val attempt = Attempt(request, generation, nowElapsed(), done)
        active = attempt
        lastHealth = lastHealth.copy(lastAttemptElapsed = attempt.started, outcome = WebhookOutcome.SENDING)
        try {
            attempt.cancelDeadline = schedule(WebhookPolicy.RESULT_DEADLINE_MS) { deadline(attempt) }
            if (!submit(url, body) { complete(attempt, it) }) complete(attempt, NO_RESPONSE)
        } catch (_: RuntimeException) {
            complete(attempt, NO_RESPONSE)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun deadline(attempt: Attempt): Unit = synchronized(configurationLock) {
        if (active === attempt && attempt.result == null) {
            val remaining = WebhookPolicy.RESULT_DEADLINE_MS - (nowElapsed() - attempt.started)
            if (remaining > 0) {
                try {
                    attempt.cancelDeadline = schedule(remaining) { deadline(attempt) }
                } catch (_: RuntimeException) {
                    report(attempt, WebhookNotifier.TestResult.FAILED)
                    if (active === attempt) attempt.cancel(cancelTransport)
                }
            } else {
                synchronizeConfiguration(configuration())
                report(attempt, WebhookNotifier.TestResult.TIMED_OUT)
                if (active === attempt) attempt.cancel(cancelTransport)
            }
        }
    }

    private fun complete(attempt: Attempt, code: Int) = synchronized(configurationLock) {
        if (active === attempt) {
            synchronizeConfiguration(configuration())
            val result = when {
                nowElapsed() - attempt.started >= WebhookPolicy.RESULT_DEADLINE_MS ->
                    WebhookNotifier.TestResult.TIMED_OUT
                WebhookPolicy.delivered(code) -> WebhookNotifier.TestResult.SENT
                else -> WebhookNotifier.TestResult.FAILED
            }
            // Publish only once; a late success must never rewrite timeout or suppress a retry.
            if (attempt.result == null) report(attempt, result)
            if (active === attempt) {
                gate.finished(attempt.request, attempt.result == WebhookNotifier.TestResult.SENT)
                active = null
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun report(attempt: Attempt, result: WebhookNotifier.TestResult) {
        attempt.result = result
        attempt.cancelDeadline?.invoke()
        attempt.cancelDeadline = null
        if (attempt.generation == generation) {
            lastHealth = lastHealth.copy(outcome = when (result) {
                WebhookNotifier.TestResult.SENT -> WebhookOutcome.SENT
                WebhookNotifier.TestResult.TIMED_OUT -> WebhookOutcome.TIMED_OUT
                else -> WebhookOutcome.FAILED
            }, lastSuccessElapsed = if (result == WebhookNotifier.TestResult.SENT) nowElapsed()
                else lastHealth.lastSuccessElapsed)
        }
        try {
            attempt.done?.invoke(result)
        } catch (_: RuntimeException) {
            // A UI consumer cannot strand the physical slot or prevent cancellation.
        }
    }

    private fun invalidate() {
        generation++
        gate.reset()
        lastHealth = WebhookHealth()
        active?.cancel(cancelTransport)
    }

    private fun synchronizeConfiguration(current: WebhookConfiguration) {
        if (configured != current) {
            configured = current
            invalidate()
        }
    }

    private companion object { const val NO_RESPONSE = -1 }
}
