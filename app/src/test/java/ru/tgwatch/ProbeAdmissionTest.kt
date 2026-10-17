package ru.tgwatch

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ProbeAdmissionTest {
    @Test fun fivePoolRestartsRespectPhysicalBudget() {
        val pools = (0 until 5).map { NetworkProbe.executor() }
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        try {
            pools.forEachIndexed { index, pool ->
                val endpoints = (0 until 13).map { ProbeEndpoint("pool$index-endpoint$it", ProbeGroup.WEB, "https://test.invalid/$index/$it") }
                ProbeBatch(pool, 100).run(endpoints) { ep, _ ->
                    val count = active.incrementAndGet()
                    maximum.updateAndGet { maxOf(it, count) }
                    try {
                        while (release.count > 0) {
                            try { release.await() } catch (_: InterruptedException) { }
                        }
                        ProbeOutcome(ep.name, ep.group, true, 1, "released")
                    } finally { active.decrementAndGet() }
                }
                pool.shutdownNow()
            }
            assertTrue("Physical primary work across all pools was ${maximum.get()}", maximum.get() <= 16)
        } finally {
            release.countDown()
            pools.forEach { it.shutdownNow() }
            pools.forEach { assertTrue(it.awaitTermination(3, TimeUnit.SECONDS)) }
            assertEquals(0, active.get())
        }
    }

    @Test fun sameEndpointIsNotDuplicated() {
        val pools = (0 until 2).map { NetworkProbe.executor() }
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val endpoint = ProbeEndpoint("shared", ProbeGroup.WEB, "https://test.invalid/shared")
        try {
            pools.forEach { pool ->
                ProbeBatch(pool, 100).run(listOf(endpoint)) { ep, _ ->
                    calls.incrementAndGet()
                    while (release.count > 0) {
                        try { release.await() } catch (_: InterruptedException) { }
                    }
                    ProbeOutcome(ep.name, ep.group, true, 1, "released")
                }
            }
            assertEquals("The same physical endpoint must not be started again in a new executor", 1, calls.get())
        } finally {
            release.countDown()
            pools.forEach { it.shutdownNow() }
            pools.forEach { assertTrue(it.awaitTermination(3, TimeUnit.SECONDS)) }
        }
    }

    @Test fun cancelBeforeRunReleasesOnce() {
        val first = Executors.newSingleThreadExecutor()
        val second = Executors.newSingleThreadExecutor()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val endpoint = ProbeEndpoint("queued", ProbeGroup.WEB, "https://test.invalid/queued")
        first.execute { held.countDown(); release.await() }
        try {
            assertTrue(held.await(2, TimeUnit.SECONDS))
            val skipped = ProbeBatch(first, 30).run(listOf(endpoint)) { _, _ ->
                throw AssertionError("The cancelled queued operation must never start")
            }.single()
            assertEquals(ProbeMeasurement.NOT_MEASURED, skipped.measurement)
            val attempted = AtomicInteger()
            val result = ProbeBatch(second, 100).run(listOf(endpoint)) { ep, _ ->
                attempted.incrementAndGet()
                ProbeOutcome(ep.name, ep.group, true, 1, "OK")
            }.single()
            assertTrue(result.reachable)
            assertEquals(1, attempted.get())
        } finally {
            release.countDown()
            first.shutdownNow(); second.shutdownNow()
            assertTrue(first.awaitTermination(2, TimeUnit.SECONDS))
            assertTrue(second.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun lateCompletionCannotReleaseAnotherPermit() {
        val admission = ProbeAdmission(1, 1)
        val old = admission.tryAcquire("same", ProbeLane.PRIMARY)!!
        assertTrue(old.start())
        old.cancelBeforeStart()
        assertEquals(1, admission.snapshot().primary)
        old.finished()
        val current = admission.tryAcquire("same", ProbeLane.PRIMARY)!!
        assertTrue(current.start())
        old.finished()
        old.cancelBeforeStart()
        assertNull(admission.tryAcquire("same", ProbeLane.PRIMARY))
        assertEquals(1, admission.snapshot().primary)
        current.finished()
        assertEquals(0, admission.snapshot().primary)
    }

    @Test fun secondaryBudgetSurvivesPoolReplacement() {
        val admission = ProbeAdmission(2, 3)
        val pools = (0 until 2).map { NetworkProbe.extraExecutor() }
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            pools.forEachIndexed { index, pool ->
                val endpoints = (0 until 3).map { ProbeEndpoint("extra$index-$it", ProbeGroup.EXTRA, "https://test.invalid/extra/$index/$it") }
                ProbeBatch(pool, 100, admission, ProbeLane.SECONDARY).run(endpoints) { ep, _ ->
                    calls.incrementAndGet()
                    while (release.count > 0) {
                        try { release.await() } catch (_: InterruptedException) { }
                    }
                    ProbeOutcome(ep.name, ep.group, true, 1, "released")
                }
            }
            assertEquals(3, calls.get())
            assertEquals(3, admission.snapshot().secondary)
            val primary = admission.tryAcquire("independent-primary", ProbeLane.PRIMARY)!!
            assertTrue(primary.start())
            primary.finished()
        } finally {
            release.countDown()
            pools.forEach { it.shutdownNow() }
            pools.forEach { assertTrue(it.awaitTermination(3, TimeUnit.SECONDS)) }
        }
        assertEquals(ProbeAdmissionSnapshot(0, 0), admission.snapshot())
    }
}
