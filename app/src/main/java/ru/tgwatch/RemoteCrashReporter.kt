package ru.tgwatch

import android.content.Context
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** A single bounded, best-effort attempt on an uncaught crash; failures are discarded. */
internal object RemoteCrashReporter {
    private const val CONNECT_TIMEOUT_MS = 1_500
    private const val READ_TIMEOUT_MS = 1_500
    private const val DEADLINE_MS = 2_500L
    private val attempted = AtomicBoolean(false)

    @Suppress("TooGenericExceptionCaught")
    fun report(ctx: Context, record: CrashJournal.Record) {
        val endpoint = runCatching { CrashReportingSettings.endpoint(ctx) }.getOrNull() ?: return
        if (!runCatching { CrashReportingSettings.enabled(ctx) }.getOrDefault(false) ||
            !attempted.compareAndSet(false, true)) return

        val payload = try {
            CrashReportingPolicy.payload(BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT, record.type, record.frames)
        } catch (_: Exception) {
            return
        }
        val connection = AtomicReference<HttpURLConnection?>()
        try {
            val request = FutureTask { send(endpoint, payload, connection) }
            Thread(request, "crash-report").apply {
                isDaemon = true
                start()
            }
            request.get(DEADLINE_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            connection.get()?.disconnect()
            request.cancel(true)
        } catch (_: Exception) {
            // Do not log the endpoint, response, or exception text; there is no retry or queue.
        }
    }

    private fun send(endpoint: String, payload: ByteArray, connectionRef: AtomicReference<HttpURLConnection?>) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(endpoint).openConnection() as? HttpURLConnection ?: return
            connectionRef.set(connection)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            connection.responseCode
        } catch (_: Exception) {
            // Best effort only.
        } finally {
            connection?.disconnect()
            connectionRef.set(null)
        }
    }
}
