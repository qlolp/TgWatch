package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Test

class SlowAlertPresentationTest {
    private fun SlowAlertPresentation.render(active: Boolean, enabled: Boolean, muted: Boolean,
        quiet: Boolean, running: Boolean, now: Long) = reconcile(SlowDisplayState(active, enabled, muted, quiet, running), now)
    private val cooldown = 30 * 60_000L

    @Test fun disableMuteStopRemoveExistingSlowNotice() {
        for (blocker in listOf("disabled", "muted", "quiet", "stopped")) {
            val display = SlowAlertPresentation()
            assertEquals(SlowDisplayAction.SHOW, display.render(true, true, false, false, true, 0))
            assertEquals(blocker, SlowDisplayAction.CLEAR, display.render(true, blocker != "disabled",
                blocker == "muted", blocker == "quiet", blocker != "stopped", 1))
            assertEquals(SlowDisplayAction.NONE, display.render(false, true, false, false, true, 2))
        }
    }

    @Test fun quietEndDoesNotBypassCooldown() {
        val display = SlowAlertPresentation()
        assertEquals(SlowDisplayAction.SHOW, display.render(true, true, false, false, true, 0))
        assertEquals(SlowDisplayAction.CLEAR, display.render(true, true, false, true, true, 1))
        assertEquals(SlowDisplayAction.NONE, display.render(true, true, false, false, true, 2))
        assertEquals(SlowDisplayAction.SHOW, display.render(true, true, false, false, true, cooldown))
    }

    @Test fun aSlowFactFirstObservedWhileMutedCanAppearWhenMuteEnds() {
        val display = SlowAlertPresentation()
        assertEquals(SlowDisplayAction.NONE, display.render(true, true, true, false, true, 0))
        assertEquals(SlowDisplayAction.SHOW, display.render(true, true, false, false, true, 1))
        assertEquals(SlowDisplayAction.NONE, display.render(true, true, false, false, true, cooldown))
    }

    @Test fun recoveryClearsNoticeAndDoesNotResetItsPresentationCooldown() {
        val display = SlowAlertPresentation()
        assertEquals(SlowDisplayAction.SHOW, display.render(true, true, false, false, true, 0))
        assertEquals(SlowDisplayAction.CLEAR, display.render(false, true, false, false, true, 1))
        assertEquals(SlowDisplayAction.NONE, display.render(true, true, false, false, true, 2))
        assertEquals(SlowDisplayAction.SHOW, display.render(true, true, false, false, true, cooldown))
    }
}
