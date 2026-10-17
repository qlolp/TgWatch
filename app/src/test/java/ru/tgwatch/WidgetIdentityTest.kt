package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class WidgetIdentityTest {
    @Test fun widgetTargetActionAndConfigurationAreIndependent() {
        val first = WidgetIdentity(41, TargetId.TELEGRAM, "check", "a".repeat(32))
        assertEquals(first, WidgetIdentity.parse(first.uri))
        assertNotEquals(first.uri, first.copy(widgetId = 42).uri)
        assertNotEquals(first.uri, first.copy(targetId = TargetId.MATRIX).uri)
        assertNotEquals(first.uri, first.copy(action = "mute").uri)
        assertNotEquals(first.uri, first.copy(token = "b".repeat(32)).uri)
        assertNull(WidgetIdentity.parse("tgwatch://widget/41/other/check?config=" + "a".repeat(32)))
        assertNull(WidgetIdentity.parse("tgwatch://widget/-1/telegram/check?config=" + "a".repeat(32)))
    }
    @Test fun stoppedLockedAndUnprovenBootClockAreNeverFreshGreen() {
        val state = TargetSnapshot(TargetId.CUSTOM, TargetStatus.OK, 1000, 1000, latencyMs = 20)
        assertFalse(WidgetSnapshot.fresh(state, true, true, 1100, 1100))
        assertFalse(WidgetSnapshot.fresh(state, false, false, 1100, 1100))
        assertFalse(WidgetSnapshot.fresh(state.copy(checkedElapsedMs = 0), true, false, 1100, 1100))
        assertTrue(WidgetSnapshot.fresh(state, true, false, 1100, 1100))
        assertFalse(WidgetSnapshot.fresh(state, true, false, 1100, 1000000))
    }
}
