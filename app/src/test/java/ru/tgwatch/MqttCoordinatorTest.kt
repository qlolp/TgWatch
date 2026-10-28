package ru.tgwatch

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The MQTT adapter reuses the webhook coordinator: same deadline, no extra queue, receiver zero untouched. */
class MqttCoordinatorTest {
    private val endpoint = MqttEndpoint("127.0.0.1", 1, "t", null, null)
    private val io = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }

    @Test fun deadlineAppliesToAHungBrokerAndBlocksOverlap() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        io.execute { try { server.accept().use { Thread.sleep(30_000) } } catch (_: Exception) { } }
        var now = 0L
        val transport = MqttTransport(io, { now },
            open = { Socket(InetAddress.getLoopbackAddress(), server.localPort) })
        val results = mutableListOf<String>()
        val finished = CountDownLatch(1)
        val coordinator = WebhookCoordinator(Any(),
            { WebhookConfiguration("mqtts://configured", true, 0, WebhookFormat.JSON) },
            { now }, { _, body, done -> transport.submit(endpoint, body.bytes, done) },
            schedule = { _, action ->
                io.execute {
                    Thread.sleep(200)
                    now = WebhookPolicy.RESULT_DEADLINE_MS
                    action()
                }
                val noop: () -> Unit = {}
                noop
            },
            cancelTransport = transport::cancel)
        coordinator.sendTest { synchronized(results) { results.add(it.name) }; finished.countDown() }
        assertTrue(finished.await(10, TimeUnit.SECONDS))
        assertEquals(listOf("TIMED_OUT"), synchronized(results) { results.toList() })
        server.close()
    }

    @Test fun adapterNeverSeesBrokerDetailsThroughTheCoordinator() {
        val seen = mutableListOf<String>()
        val coordinator = WebhookCoordinator(Any(),
            { WebhookConfiguration("mqtts://configured", true, 0, WebhookFormat.JSON) }, { 0L },
            { url, body, done -> seen.add(url + String(body.bytes)); done(200); true })
        coordinator.onPublished("TG_DOWN")
        assertEquals(1, seen.size)
        assertTrue(seen.single().startsWith("mqtts://configured{"))
    }
}
