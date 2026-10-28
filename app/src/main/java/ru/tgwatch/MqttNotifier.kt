package ru.tgwatch

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Opt-in native MQTT delivery. It reuses [WebhookCoordinator] (event tracking, filters, ten-second deadline,
 * generation fencing, coalesced pending events) and only replaces the physical transport, so it never changes
 * measured target status and never wakes monitoring. One dedicated thread, no queue and no offline backlog.
 * No broker, topic, credential or exception text is logged.
 */
internal object MqttNotifier {
    private const val IDLE_THREAD_SECONDS = 30L
    private val io = ThreadPoolExecutor(1, 1, IDLE_THREAD_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue(1),
        { r -> Thread(r, "tg-mqtt").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    private val transport = MqttTransport(io, SystemClock::elapsedRealtime)
    private var coordinator: WebhookCoordinator? = null

    // The monitor worker must never fail because of this optional adapter.
    @Suppress("TooGenericExceptionCaught")
    fun onPublished(ctx: Context, observation: WebhookObservation) {
        try {
            coordinator(ctx).onPublished(observation)
        } catch (e: RuntimeException) {
            Log.w("TgWatch", "mqtt publish: ${e.javaClass.simpleName}")
        }
    }

    fun sendTest(ctx: Context, done: (WebhookNotifier.TestResult) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        coordinator(ctx).sendTest { result ->
            if (Looper.myLooper() == main.looper) done(result) else main.post { done(result) }
        }
    }

    fun reset() {
        transport.reset()
        val current = synchronized(this) { coordinator }
        current?.reset()
    }

    fun forget(id: TargetId) {
        val current = synchronized(this) { coordinator }
        current?.forget(id.wire)
    }

    internal fun health(ctx: Context): WebhookHealth = coordinator(ctx).health()

    private fun coordinator(ctx: Context): WebhookCoordinator = synchronized(this) {
        coordinator ?: ctx.applicationContext.let { app ->
            WebhookCoordinator(MqttSettings, { MqttSettings.configuration(app) }, SystemClock::elapsedRealtime,
                { _, body, finished ->
                    MqttSettings.endpoint(app)?.let { transport.submit(it, body.bytes, finished) } == true
                }, cancelTransport = transport::cancel).also { coordinator = it }
        }
    }
}
