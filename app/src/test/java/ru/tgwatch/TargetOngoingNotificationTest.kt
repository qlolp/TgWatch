package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class TargetOngoingNotificationTest {
    @Test fun extraOnlyForegroundNeverClaimsTelegramIsMonitoredOrCombinesUptime() {
        val state = TargetSnapshot(TargetId.MATRIX, TargetStatus.OK, 9_000, 9_000, latencyMs = 25)
        val view = OngoingNotification.targets(listOf(state), 10_000, 10_000)
        assertTrue(view.notif.title.contains("Matrix"))
        assertFalse(view.notif.title.contains("Telegram"))
        assertEquals(TargetId.MATRIX, view.targetId)
        assertNull(view.progress)
        assertFalse(view.chronometer)
    }
    @Test fun unknownAndStaleExtrasAreNotPresentedAsFreshReachability() {
        val state = TargetSnapshot(TargetId.CUSTOM, TargetStatus.OK, 1_000, 1_000)
        val view = OngoingNotification.targets(listOf(state), 1_000_000, 1_000_000)
        assertTrue(view.notif.text.contains(Lang.t("устарело", "stale")))
        assertFalse(view.notif.colorized)
        assertNull(view.progress)
    }
}
