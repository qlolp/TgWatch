package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

/** Host JVM allocation measurement, not an Android heap/frame timing claim. */
class HistoryPreparationAllocationTest {
    @Test fun repeatedSixtyFiveThousandRowPreparationHasBoundedAllocation() {
        // Android's compile stubs omit java.management; the host test JVM exposes it at runtime.
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val beanType = Class.forName("com.sun.management.ThreadMXBean")
        assertEquals(true, beanType.getMethod("isThreadAllocatedMemorySupported").invoke(bean))
        beanType.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
        val allocated = beanType.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val thread = Thread.currentThread().id
        fun bytes() = allocated.invoke(bean, thread) as Long
        val rows = List(65_000) { index ->
            val at = 1_000_000L + index * 1000L
            Observation(at, at + 1000, if (index % 3 == 0) "TG_DOWN" else "OK", 20)
        }
        val cache = HistoryPreparationCache()
        val first = cache.prepare(rows, 1, 0)
        repeat(20) { Timeline.retained(rows, 0) }
        repeat(100) { cache.prepare(rows, 1, it.toLong()) }
        var before = bytes()
        repeat(10) { Timeline.retained(rows, 0) }
        val uncachedPerCall = (bytes() - before) / 10
        before = bytes()
        repeat(1000) { assertSame(first, cache.prepare(rows, 1, 100 + it.toLong())) }
        val cachedPerCall = (bytes() - before) / 1000
        // Reproduce the production order: rollback preparation, prune preparation, then a minute/period hit.
        val now = rows.last().until + 4_000_000L
        val revision = first.epoch + 1
        val stats = Timeline.stats(first.checks, now - 86_400_000L, now)
        val results = mutableMapOf("$revision:${now / 60_000L}:1" to stats)
        fun warmStats(at: Long, days: Int): TimeStats {
            cache.prepare(rows, revision, at - 7 * 86_400_000L)
            val prepared = cache.prepare(rows, revision, at - 7 * 86_400_000L)
            val key = "$revision:${at / 60_000L}:${days.coerceIn(1, 7)}"
            return results[key] ?: Timeline.stats(prepared.checks, at - days * 86_400_000L, at)
        }
        repeat(100) { assertSame(stats, warmStats(now + it, 1)) }
        before = bytes()
        repeat(1000) { assertSame(stats, warmStats(now + 100 + it, 1)) }
        val statsHitPerCall = (bytes() - before) / 1000
        println("HOST_65K retainedBytes=$uncachedPerCall preparedHitBytes=$cachedPerCall statsHitBytes=$statsHitPerCall")
        assertTrue("Preparation hit must avoid row-sized allocation", cachedPerCall < 1024)
        assertTrue("Warm stats lookup must avoid row-sized allocation", statsHitPerCall < 1024)
    }
}
