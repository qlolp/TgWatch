package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ExtraProbeCoordinatorTest {
    @Test fun deliveryRequiresSameServiceCheckRevisionAndSelectedNetwork() {
        val first = ExtraProbeCoordinator()
        val other = ExtraProbeCoordinator()
        try {
            val token = first.begin(10, 20)
            assertTrue(first.accepts(token, 10, 20))
            assertFalse(first.accepts(token, 11, 20))
            assertFalse(first.accepts(token, 10, 21))
            assertFalse(first.accepts(token, 10, null))
            assertFalse(other.accepts(token, 10, 20))
            first.begin(10, 20)
            assertFalse(first.accepts(token, 10, 20))
        } finally { first.close(); other.close() }
    }

    @Test fun queuedDeliveryIsRejectedAfterNewCheckEvenBeforeNewPrimarySucceeds() {
        assertQueuedDeliveryRejected { it.begin(4, 8) }
    }

    @Test fun queuedDeliveryIsRejectedAfterStopOrRestoreInvalidation() {
        assertQueuedDeliveryRejected { it.invalidate() }
    }

    @Test fun queuedDeliveryIsRejectedAfterServiceTeardown() {
        assertQueuedDeliveryRejected { it.close() }
    }

    @Test fun cancellationInterruptsWaiterAndDoesNotDeliverPartialBatch() {
        val coordinator = ExtraProbeCoordinator()
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val done = CountDownLatch(1)
        val delivered = AtomicInteger()
        try {
            val token = coordinator.begin(1, 2)
            assertTrue(coordinator.submit(token, {
                started.countDown()
                try { CountDownLatch(1).await() }
                catch (_: InterruptedException) { interrupted.countDown(); Thread.currentThread().interrupt() }
                finally { done.countDown() }
                emptyList()
            }, { delivered.incrementAndGet() }))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            coordinator.invalidate()
            assertTrue(interrupted.await(2, TimeUnit.SECONDS))
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(0, delivered.get())
        } finally { coordinator.close() }
    }

    @Test fun noncooperativeOperationRetainsOnlyWorkerAndRejectsReplacementWithoutQueue() {
        val coordinator = ExtraProbeCoordinator()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val calls = AtomicInteger()
        val deliveries = AtomicInteger()
        try {
            val first = coordinator.begin(1, 2)
            assertTrue(coordinator.submit(first, {
                calls.incrementAndGet()
                started.countDown()
                while (release.count > 0) {
                    try { release.await() } catch (_: InterruptedException) { /* Physical operation remains occupied. */ }
                }
                finished.countDown()
                emptyList()
            }, { deliveries.incrementAndGet() }))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            repeat(100) {
                val next = coordinator.begin(1, 2)
                assertFalse(coordinator.submit(next, { calls.incrementAndGet(); emptyList() }, {}))
            }
            assertEquals(1, calls.get())
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            val next = coordinator.begin(1, 2)
            val delivered = CountDownLatch(1)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var accepted = false
            while (!accepted && System.nanoTime() < deadline) {
                accepted = coordinator.submit(next, { calls.incrementAndGet(); emptyList() }, { delivered.countDown() })
                Thread.yield()
            }
            assertTrue("The same waiter becomes reusable after physical completion", accepted)
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            assertEquals(2, calls.get())
            assertEquals(0, deliveries.get())
        } finally { release.countDown(); coordinator.close() }
    }

    @Test fun sameCheckCannotQueueDuplicateDiagnostics() {
        val coordinator = ExtraProbeCoordinator()
        val delivered = CountDownLatch(1)
        try {
            val token = coordinator.begin(1, 2)
            assertTrue(coordinator.submit(token, { emptyList() }, { delivered.countDown() }))
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            assertFalse(coordinator.submit(token, { fail("Duplicate must never execute"); emptyList() }, {}))
        } finally { coordinator.close() }
    }

    private fun assertQueuedDeliveryRejected(invalidate: (ExtraProbeCoordinator) -> Unit) {
        val coordinator = ExtraProbeCoordinator()
        val queued = CountDownLatch(1)
        val callback = AtomicReference<() -> Unit>()
        val publications = AtomicInteger()
        try {
            val token = coordinator.begin(4, 8)
            assertTrue(coordinator.submit(token, { emptyList() }, {
                callback.set { if (coordinator.accepts(token, 4, 8)) publications.incrementAndGet() }
                queued.countDown()
            }))
            assertTrue(queued.await(2, TimeUnit.SECONDS))
            invalidate(coordinator)
            callback.get().invoke()
            assertEquals(0, publications.get())
        } finally { coordinator.close() }
    }
}
