package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class TargetSummaryTest {
    @Test fun independentTargetsNeverBecomeOneAvailabilityPercentage() {
        val stats = TimeStats(60_000, 0, 0, 0, 0, 0, 1, 10)
        val text = DailySummary.targets(linkedMapOf(TargetId.TELEGRAM to stats,
            TargetId.MATRIX to stats.copy(okMs = 0, downMs = 60_000)))!!
        assertTrue(text.body.contains("Telegram") && text.body.contains("Matrix"))
        assertTrue(text.body.contains(formatPercent(100.0)))
        assertTrue(text.body.contains(formatPercent(0.0)))
        assertFalse(text.body.contains(formatPercent(50.0)))
    }
    @Test fun targetWithUnavailableHistoryIsExplicitWhileOtherTargetCanBeReported() {
        val stats = TimeStats(60_000, 0, 0, 0, 0, 0, 1, 10)
        val text = DailySummary.targets(linkedMapOf(TargetId.MATRIX to null, TargetId.WHATSAPP to stats))!!
        assertTrue(text.body.contains("Matrix"))
        assertTrue(text.body.contains(Lang.t("история недоступна", "history unavailable")))
        assertNull(DailySummary.targets(emptyMap()))
    }
    @Test fun incidentNamesOnlySelectedGoal() {
        val episode = OutageEpisode(1_000, 2_000, true, false, 1_000, 0, 0, 0, 0)
        val card = IncidentPresentation.describe(episode, targetId = TargetId.MATRIX)
        assertTrue(card.contains("Matrix"))
        assertFalse(card.contains("Telegram"))
    }
}
