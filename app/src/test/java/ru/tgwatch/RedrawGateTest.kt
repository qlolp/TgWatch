package ru.tgwatch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedrawGateTest {
    @Test fun firstDrawAlwaysPasses() {
        assertTrue(RedrawGate().shouldDraw("OK · 120 мс", 0L))
    }

    @Test fun identicalContentIsSkippedUntilTheSilenceLimit() {
        val gate = RedrawGate(maxSilenceMs = 300_000L)
        assertTrue(gate.shouldDraw("OK", 1_000L))
        assertFalse("A 15-second tick with the same text must not redraw", gate.shouldDraw("OK", 16_000L))
        assertFalse(gate.shouldDraw("OK", 300_999L))
        assertTrue("A periodic refresh still reaches the host", gate.shouldDraw("OK", 301_000L))
        assertFalse(gate.shouldDraw("OK", 316_000L))
    }

    @Test fun changedContentPassesImmediately() {
        val gate = RedrawGate()
        assertTrue(gate.shouldDraw("OK", 1_000L))
        assertTrue(gate.shouldDraw("TG_DOWN", 2_000L))
        assertFalse(gate.shouldDraw("TG_DOWN", 3_000L))
        assertTrue("Returning to an earlier text is still a change", gate.shouldDraw("OK", 4_000L))
    }

    @Test fun dataClassKeysCompareByValue() {
        data class View(val title: String, val whenMs: Long)
        val gate = RedrawGate()
        assertTrue(gate.shouldDraw(View("OK", 10L), 0L))
        assertFalse(gate.shouldDraw(View("OK", 10L), 15_000L))
        assertTrue(gate.shouldDraw(View("OK", 20L), 30_000L))
    }

    @Test fun monotonicClockGoingBackwardsForcesARedraw() {
        val gate = RedrawGate()
        assertTrue(gate.shouldDraw("OK", 50_000L))
        assertTrue(gate.shouldDraw("OK", 10_000L))
    }

    @Test fun resetForgetsTheLastDraw() {
        val gate = RedrawGate()
        assertTrue(gate.shouldDraw("OK", 0L))
        gate.reset()
        assertTrue(gate.shouldDraw("OK", 1_000L))
    }
}
