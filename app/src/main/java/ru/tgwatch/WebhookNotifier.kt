package ru.tgwatch

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Opt-in POST of one status line to a user-owned HTTPS endpoint (for example an ntfy.sh topic).
 * One background thread, at most one request in flight, bounded timeouts, no redirects.
 * The address is never logged; failures log only the exception class.
 */
object WebhookNotifier {
    enum class TestResult { SENT, FAILED, BUSY, NOT_CONFIGURED }

    private const val TAG = "TgWatch"
    private const val IDLE_THREAD_SECONDS = 30L
    private const val NO_RESPONSE = -1
    @Volatile private var coordinator: WebhookCoordinator? = null
    private val io = ThreadPoolExecutor(1, 1, IDLE_THREAD_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue(1),
        { r -> Thread(r, "tg-webhook").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    @Volatile private var userAgent: String? = null

    /** Called by the monitor for every published result; returns immediately. */
    // The monitor worker must never fail because of this optional adapter.
    @Suppress("TooGenericExceptionCaught")
    fun onPublished(ctx: Context, status: MonitorService.Status) {
        try {
            coordinator(ctx).onPublished(status.name)
        } catch (e: RuntimeException) {
            Log.w(TAG, "webhook skipped: ${e.javaClass.simpleName}")
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

    private fun coordinator(ctx: Context): WebhookCoordinator = coordinator ?: synchronized(this) {
        coordinator ?: ctx.applicationContext.let { app ->
            WebhookCoordinator(WebhookSettings, { WebhookSettings.configuration(app) },
                SystemClock::elapsedRealtime, { url, body, finished -> submit(app, url, body, finished) })
        }.also { coordinator = it }
    }

    // Any failure becomes "not delivered"; only the exception class is logged, never the address.
    @Suppress("TooGenericExceptionCaught")
    private fun submit(ctx: Context, url: String, body: String, finished: (Int) -> Unit): Boolean {
        val agent = userAgent(ctx)
        return try {
            io.execute {
                var code = NO_RESPONSE
                try {
                    code = post(url, body, agent)
                } catch (e: IOException) {
                    Log.w(TAG, "webhook failed: ${e.javaClass.simpleName}")
                } catch (e: RuntimeException) {
                    Log.w(TAG, "webhook failed: ${e.javaClass.simpleName}")
                } finally {
                    finished(code)
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    private fun post(url: String, body: String, agent: String): Int {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = WebhookPolicy.TIMEOUT_MS
            connection.readTimeout = WebhookPolicy.TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doOutput = true
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            connection.setRequestProperty("User-Agent", agent)
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            return connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun userAgent(ctx: Context): String = userAgent ?: WebhookPolicy.userAgent(try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }).also { userAgent = it }
}

/** Android-independent orchestration, shared by monitor delivery and explicit test sends. */
internal class WebhookCoordinator(
    private val configurationLock: Any,
    private val configuration: () -> WebhookConfiguration,
    private val nowElapsed: () -> Long,
    private val submit: (String, String, (Int) -> Unit) -> Boolean
) {
    private val gate = WebhookGate()
    private var configured: WebhookConfiguration? = null

    fun reset() = synchronized(configurationLock) {
        configured = configuration()
        gate.reset()
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

    /** Caller holds the settings monitor through admission and queueing, never through network I/O. */
    @Suppress("TooGenericExceptionCaught")
    private fun dispatch(request: WebhookGate.Request, url: String, body: String,
        done: ((WebhookNotifier.TestResult) -> Unit)? = null) {
        val completed: (Int) -> Unit = { code ->
            val accepted = synchronized(configurationLock) {
                synchronizeConfiguration(configuration())
                gate.finished(request, WebhookPolicy.delivered(code))
            }
            if (accepted) done?.invoke(if (WebhookPolicy.delivered(code)) WebhookNotifier.TestResult.SENT
                else WebhookNotifier.TestResult.FAILED)
        }
        val queued = try {
            submit(url, body, completed)
        } catch (e: RuntimeException) {
            completed(-1)
            throw e
        }
        if (!queued) completed(-1)
    }

    /** Delivery/rate history belongs to a configuration; physical in-flight identity outlives it. */
    private fun synchronizeConfiguration(current: WebhookConfiguration) {
        if (configured != current) {
            configured = current
            gate.reset()
        }
    }
}
