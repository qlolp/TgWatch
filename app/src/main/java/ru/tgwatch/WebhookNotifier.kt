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
    private val gate = WebhookGate()
    private val io = ThreadPoolExecutor(1, 1, IDLE_THREAD_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue(1),
        { r -> Thread(r, "tg-webhook").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    @Volatile private var userAgent: String? = null

    /** Called by the monitor for every published result; returns immediately. */
    // The monitor worker must never fail because of this optional adapter.
    @Suppress("TooGenericExceptionCaught")
    fun onPublished(ctx: Context, status: MonitorService.Status) {
        try {
            val url = WebhookSettings.activeUrl(ctx) ?: return
            val name = status.name
            if (!gate.begin(name, SystemClock.elapsedRealtime())) return
            val body = WebhookPolicy.payload(name)
            val queued = body != null &&
                submit(ctx, url, body) { code -> gate.finished(name, WebhookPolicy.delivered(code)) }
            if (!queued) gate.finished(name, false)
        } catch (e: RuntimeException) {
            Log.w(TAG, "webhook skipped: ${e.javaClass.simpleName}")
        }
    }

    /** Explicit user test from the settings screen, even while the switch is off; [done] runs on the main thread. */
    fun sendTest(ctx: Context, done: (TestResult) -> Unit) {
        val app = ctx.applicationContext
        val url = WebhookSettings.url(app)
        if (url == null) {
            done(TestResult.NOT_CONFIGURED)
            return
        }
        if (!gate.beginTest(SystemClock.elapsedRealtime())) {
            done(TestResult.BUSY)
            return
        }
        val main = Handler(Looper.getMainLooper())
        val queued = submit(app, url, WebhookPolicy.testPayload()) { code ->
            gate.finishedTest()
            main.post { done(if (WebhookPolicy.delivered(code)) TestResult.SENT else TestResult.FAILED) }
        }
        if (!queued) {
            gate.finishedTest()
            done(TestResult.FAILED)
        }
    }

    /** Address or switch changed: the next change is reported regardless of earlier posts. */
    fun reset() = gate.reset()

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
