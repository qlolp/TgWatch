package ru.tgwatch

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Process-local, current-configuration health. Contains neither credentials nor transport detail. */
internal data class WebhookHealth(
    val lastAttemptElapsed: Long? = null,
    val lastSuccessElapsed: Long? = null,
    val outcome: WebhookOutcome = WebhookOutcome.IDLE,
    val inFlight: Boolean = false,
    /** Physical cancellation is pending; configuration replacement cannot clear this condition. */
    val transportBlocked: Boolean = false
)

internal enum class WebhookOutcome { IDLE, SENDING, SENT, FAILED, TIMED_OUT }

/** One active physical request means at most one timer; canceled tasks are removed immediately. */
internal object WebhookDeadlines {
    private val timer = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "tg-webhook-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
        val future = timer.schedule(action, delayMs, TimeUnit.MILLISECONDS)
        return { future.cancel(false) }
    }
}

/** Cancellation is best effort: DNS and platform I/O can ignore interruption/disconnect. */
internal class WebhookTransport(
    private val io: Executor,
    private val open: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    private val cancellationIo: Executor = cancellationExecutor
) {
    private class Call {
        @Volatile var cancelled = false
        @Volatile var connection: HttpURLConnection? = null
        @Volatile var worker: Thread? = null
    }
    private var active: Call? = null

    @Synchronized fun submit(url: String, body: String, agent: String, finished: (Int) -> Unit): Boolean {
        if (active != null) return false
        val call = Call()
        active = call
        return try {
            io.execute { run(call, url, body, agent, finished) }
            true
        } catch (_: RejectedExecutionException) {
            active = null
            false
        }
    }

    @Synchronized fun cancel() {
        val call = active ?: return
        if (call.cancelled) return
        call.cancelled = true
        call.worker?.interrupt()
        call.connection?.let { connection ->
            try {
                cancellationIo.execute { disconnect(connection) }
            } catch (_: RejectedExecutionException) {
                // The worker will also disconnect in finally; cancellation never creates extra workers.
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun run(call: Call, url: String, body: String, agent: String, finished: (Int) -> Unit) {
        var code = NO_RESPONSE
        call.worker = Thread.currentThread()
        try {
            if (!call.cancelled) {
                val connection = open(url)
                call.connection = connection
                if (!call.cancelled) code = post(connection, body, agent)
            }
        } catch (_: IOException) {
            // Sanitized FAILED result only; no URLs, response bodies or exception text escape.
        } catch (_: RuntimeException) {
            // Platform transport failures have the same private result.
        } finally {
            call.connection?.let { disconnect(it) }
            synchronized(this) { if (active === call) active = null }
            // A reused worker must not carry cancellation into a subsequent request.
            Thread.interrupted()
            finished(code)
        }
    }

    private fun post(connection: HttpURLConnection, body: String, agent: String): Int {
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
    }

    private companion object {
        const val NO_RESPONSE = -1
        const val IDLE_SECONDS = 30L
        val cancellationExecutor = ThreadPoolExecutor(1, 1, IDLE_SECONDS, TimeUnit.SECONDS,
            LinkedBlockingQueue(1), { task -> Thread(task, "tg-webhook-cancel").apply { isDaemon = true } })
            .apply { allowCoreThreadTimeOut(true) }

        @Suppress("TooGenericExceptionCaught")
        fun disconnect(connection: HttpURLConnection) {
            try {
                connection.disconnect()
            } catch (_: RuntimeException) {
                // Disconnect cannot prevent result publication or physical-slot accounting.
            }
        }
    }
}
