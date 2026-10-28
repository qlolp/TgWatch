package ru.tgwatch

import android.content.Context
import android.content.pm.PackageManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Opt-in POST of a bounded event payload to a user-owned HTTPS endpoint (for example an ntfy.sh topic).
 * One network thread, bounded per-recipient queue, bounded timeouts, no redirects.
 * No address, body or transport exception is logged.
 */
object WebhookNotifier {
    enum class TestResult { SENT, FAILED, TIMED_OUT, BUSY, NOT_CONFIGURED }

    private const val IDLE_THREAD_SECONDS = 30L
    private val io = ThreadPoolExecutor(1, 1, IDLE_THREAD_SECONDS, TimeUnit.SECONDS,
        LinkedBlockingQueue(WebhookSettings.MAX_RECEIVERS - 1),
        { r -> Thread(r, "tg-webhook").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    private val transports = Array(WebhookSettings.MAX_RECEIVERS) { WebhookTransport(io) }
    private val coordinators = arrayOfNulls<WebhookCoordinator>(WebhookSettings.MAX_RECEIVERS)
    @Volatile private var userAgent: String? = null

    /** Called by the monitor for every published result; returns immediately. */
    // The monitor worker must never fail because of this optional adapter.
    @Suppress("TooGenericExceptionCaught")
    fun onPublished(ctx: Context, observation: WebhookObservation) {
        for (id in 0 until WebhookSettings.MAX_RECEIVERS) {
            try {
                coordinator(ctx, id).onPublished(observation)
            } catch (e: RuntimeException) {
                // Optional delivery must never interrupt the monitor; log the class, never the address.
                Log.w("TgWatch", "webhook publish: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Explicit user test from the settings screen, even while the switch is off; [done] runs on the main thread. */
    fun sendTest(ctx: Context, done: (TestResult) -> Unit) {
        sendTest(ctx, 0, done)
    }

    fun sendTest(ctx: Context, receiverId: Int, done: (TestResult) -> Unit) {
        require(receiverId in 0 until WebhookSettings.MAX_RECEIVERS)
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        coordinator(app, receiverId).sendTest { result ->
            if (Looper.myLooper() == main.looper) done(result) else main.post { done(result) }
        }
    }

    /** Address or switch changed: the next change is reported regardless of earlier posts. */
    fun reset() {
        val current = synchronized(this) { coordinators.filterNotNull().toList() }
        current.forEach(WebhookCoordinator::reset)
    }
    fun reset(receiverId: Int) {
        require(receiverId in 0 until WebhookSettings.MAX_RECEIVERS)
        val current = synchronized(this) { coordinators[receiverId] }
        current?.reset()
    }
    fun forget(id: TargetId) {
        val current = synchronized(this) { coordinators.filterNotNull().toList() }
        current.forEach { it.forget(id.wire) }
    }

    internal fun health(ctx: Context): WebhookHealth = health(ctx, 0)

    internal fun health(ctx: Context, receiverId: Int): WebhookHealth = coordinator(ctx, receiverId).health()

    private fun coordinator(ctx: Context, receiverId: Int): WebhookCoordinator = synchronized(this) {
        coordinators[receiverId] ?: ctx.applicationContext.let { app ->
            val transport = transports[receiverId]
            WebhookCoordinator(WebhookSettings, { WebhookSettings.configuration(app, receiverId) },
                SystemClock::elapsedRealtime,
                { url, body, finished -> transport.submit(url, body.resolved(app), userAgent(app), finished) },
                cancelTransport = transport::cancel).also { coordinators[receiverId] = it }
        }
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
    private val submit: (String, WebhookBody, (Int) -> Unit) -> Boolean,
    private val schedule: (Long, () -> Unit) -> (() -> Unit) = WebhookDeadlines::schedule,
    private val cancelTransport: () -> Unit = {}
) {
    private val gate = WebhookGate()
    private var tracker = WebhookEventTracker()
    private val heartbeats = WebhookHeartbeatCandidates()
    private val latest = WebhookPendingEvents()
    private val configurationFence = WebhookConfigurationFence()
    private var generation = 0L
    private var active: Attempt? = null
    private var lastHealth = WebhookHealth()

    private class Attempt(val request: WebhookGate.Request, val generation: Long, val started: Long,
        val done: ((WebhookNotifier.TestResult) -> Unit)?, val eventId: Long?) {
        var result: WebhookNotifier.TestResult? = null
        var cancelDeadline: (() -> Unit)? = null
        var cancellationRequested = false
            private set

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
        configurationFence.changed(configuration())
        invalidate()
    }

    fun forget(serviceId: String) = synchronized(configurationLock) {
        tracker.forget(serviceId)
        heartbeats.forget(serviceId)
        latest.forget(serviceId)
        gate.forget(serviceId)
        active?.takeIf { it.request.superseded }?.let {
            lastHealth = WebhookHealth(lastSuccessElapsed = lastHealth.lastSuccessElapsed)
            it.cancel(cancelTransport)
        }
    }

    fun health(): WebhookHealth = synchronized(configurationLock) {
        if (configurationFence.changed(configuration())) invalidate()
        lastHealth.copy(inFlight = active != null, transportBlocked = active?.let {
            it.cancellationRequested || it.result == WebhookNotifier.TestResult.TIMED_OUT
        } == true)
    }

    fun onPublished(observation: WebhookObservation) {
        val captured = configuration()
        synchronized(configurationLock) {
            val current = configuration()
            if (configurationFence.changed(current)) invalidate()
            if (!current.admits(captured, observation)) return
            heartbeats.published(observation, current, nowElapsed())
            if (!tracker.accepts(observation)) {
                // V2 claims current freshness; legacy event retries keep their established measured baseline.
                if (current.format == WebhookFormat.JSON_V2) latest.forget(observation.serviceId)
                return
            }
            latest.published(tracker.observe(observation), observation, nowElapsed())
            val url = current.url?.takeIf { current.enabled }
            if (url != null) {
                val due = latest.values.firstNotNullOfOrNull { pending ->
                    val measured = checkNotNull(pending.event.observation)
                    val key = "${measured.serviceId}:${WebhookStatuses.normalize(measured.status)}"
                    if (pending.event.kind !in current.events || !pending.fresh(nowElapsed())) null else
                        gate.beginEvent(key, nowElapsed())?.let { pending to it }
                }
                if (due != null) {
                    val (pending, request) = due
                    dispatch(request, url, WebhookPayload.encode(pending.event, current.format,
                        System.currentTimeMillis()), eventId = pending.id)?.let {
                        heartbeats.eventStarted(checkNotNull(pending.event.observation), current.format, it)
                    }
                } else heartbeats.next(nowElapsed())?.let { candidate ->
                    gate.beginHeartbeat(candidate.observation.serviceId)?.let { request ->
                        dispatch(request, url, WebhookPayload.encode(
                            WebhookEvent(WebhookEventKind.HEARTBEAT, candidate.observation, null), current.format,
                            System.currentTimeMillis()))?.let { heartbeats.started(candidate, it) }
                    }
                }
            }
        }
    }

    fun sendTest(done: (WebhookNotifier.TestResult) -> Unit) {
        val captured = configuration()
        synchronized(configurationLock) {
            val current = configuration()
            if (configurationFence.changed(current)) invalidate()
            val url = current.url
            val request = if (url != null && captured == current) gate.beginTest(nowElapsed()) else null
            when {
                url == null -> done(WebhookNotifier.TestResult.NOT_CONFIGURED)
                request == null -> done(WebhookNotifier.TestResult.BUSY)
                else -> dispatch(request, url, WebhookPayload.encode(
                    WebhookEvent(WebhookEventKind.TEST, null, null), current.format, System.currentTimeMillis()), done)
            }
        }
    }

    /** Caller holds settings monitor; submit/cancel only enqueue work and never perform network I/O. */
    @Suppress("TooGenericExceptionCaught")
    private fun dispatch(request: WebhookGate.Request, url: String, body: WebhookBody,
        done: ((WebhookNotifier.TestResult) -> Unit)? = null, eventId: Long? = null): Long? {
        val attempt = Attempt(request, generation, nowElapsed(), done, eventId)
        active = attempt
        var admitted = false
        lastHealth = lastHealth.copy(lastAttemptElapsed = attempt.started, outcome = WebhookOutcome.SENDING)
        try {
            attempt.cancelDeadline = schedule(WebhookPolicy.RESULT_DEADLINE_MS) { deadline(attempt) }
            if (!submit(url, body) { complete(attempt, it) }) complete(attempt, NO_RESPONSE)
            else admitted = true
        } catch (_: RuntimeException) {
            complete(attempt, NO_RESPONSE)
        }
        return attempt.started.takeIf { admitted && attempt.generation == generation && !request.superseded }
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
                if (configurationFence.changed(configuration())) invalidate()
                report(attempt, WebhookNotifier.TestResult.TIMED_OUT)
                if (active === attempt) attempt.cancel(cancelTransport)
            }
        }
    }

    private fun complete(attempt: Attempt, code: Int) = synchronized(configurationLock) {
        if (active === attempt) {
            if (configurationFence.changed(configuration())) invalidate()
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
        if (attempt.generation == generation && !attempt.request.superseded) {
            if (result == WebhookNotifier.TestResult.SENT)
                latest.delivered(attempt.eventId)
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
        tracker = WebhookEventTracker()
        heartbeats.reset()
        latest.reset()
        lastHealth = WebhookHealth()
        active?.cancel(cancelTransport)
    }


    private companion object {
        const val NO_RESPONSE = -1
    }
}

/** Transport category only; never exposes SSIDs, addresses or Android Network handles. */
internal object WebhookNetworks {
    fun kind(caps: NetworkCapabilities): NetworkKind = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkKind.VPN
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.CELLULAR
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.ETHERNET
        else -> NetworkKind.OTHER
    }
}
