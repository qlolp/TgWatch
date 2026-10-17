package ru.tgwatch

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class TargetAutomationCacheTest {
    @After fun clear() { AutomationStateCache.clear() }

    @Test fun targetAndVersionNeverBorrowAnotherGoalsCachedState() {
        val legacy = AutomationStateKey()
        val telegram = AutomationStateKey(TargetId.TELEGRAM, 2)
        val matrix = AutomationStateKey(TargetId.MATRIX, 2)
        val value = AutomationStateSnapshot("OK", 100, false, true)
        assertTrue(AutomationStateCache.beginCompute(100, legacy))
        AutomationStateCache.endCompute(100, value, legacy)
        assertEquals(value, AutomationStateCache.take(100, legacy))
        assertNull(AutomationStateCache.take(100, telegram))
        assertNull(AutomationStateCache.take(100, matrix))
        assertTrue(AutomationStateCache.beginCompute(100, matrix))
        AutomationStateCache.endCompute(100, value.copy(status = "DOWN", apiVersion = 2, targetId = TargetId.MATRIX), matrix)
        assertEquals("DOWN", AutomationStateCache.take(100, matrix)!!.status)
        assertEquals("OK", AutomationStateCache.take(100, legacy)!!.status)
        AutomationStateCache.clear()
        assertNull(AutomationStateCache.take(100, matrix))
    }

    @Test fun targetsShareOnePhysicalComputeEvenAfterAnotherTargetTimesOut() {
        var work: Runnable? = null
        var expire: (() -> Unit)? = null
        val dispatcher = AutomationStateDispatcher(Executor { work = it }, { 100L }, { _, action ->
            expire = action; {}
        })
        val matrix = AutomationStateKey(TargetId.MATRIX, 2)
        val custom = AutomationStateKey(TargetId.CUSTOM, 2)
        assertTrue(AutomationStateCache.beginCompute(100, matrix))
        assertTrue(dispatcher.submit({ AutomationStateSnapshot("OK", 100, false, true, 2, TargetId.MATRIX) }, {}, {}, matrix))
        checkNotNull(expire).invoke()
        assertTrue(AutomationStateCache.beginCompute(100, custom))
        assertFalse(dispatcher.submit({ error("Cannot start a second physical compute") }, {}, {}, custom))
        assertNull(AutomationStateCache.take(100, custom))
        checkNotNull(work).run()
        assertNull(AutomationStateCache.take(100, matrix))
        assertFalse(dispatcher.health().busy)
    }
}
