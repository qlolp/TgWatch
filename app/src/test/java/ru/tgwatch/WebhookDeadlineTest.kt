package ru.tgwatch

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookDeadlineTest {
    @Test fun nonCooperativeTransportStillReturnsATotalDeadlineResult() {
        val result = CountDownLatch(1)
        val received = mutableListOf<String>()
        val start = System.nanoTime()
        val coordinator = WebhookCoordinator(Any(),
            { WebhookConfiguration("https://example.org/private-topic", true, 0) },
            { TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) },
            { _, _, _ -> true })
        coordinator.sendTest { synchronized(received) { received.add(it.name) }; result.countDown() }
        assertTrue("A noncooperative transport must publish its result within the 10s total deadline",
            result.await(11, TimeUnit.SECONDS))
        assertEquals(listOf("TIMED_OUT"), synchronized(received) { received.toList() })
        coordinator.sendTest { synchronized(received) { received.add(it.name) } }
        assertEquals(listOf("TIMED_OUT", "BUSY"), synchronized(received) { received.toList() })
    }
}
