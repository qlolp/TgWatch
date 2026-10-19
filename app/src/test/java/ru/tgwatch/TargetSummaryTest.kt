package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class TargetSummaryTest {
    private fun tree(value: TextValue): List<TextValue> = listOf(value) +
        value.args.filterIsInstance<TextArgument.Value>().flatMap { tree(it.value) }

    @Test fun independentTargetsNeverBecomeOneAvailabilityPercentage() {
        val stats = TimeStats(60000, 0, 0, 0, 0, 0, 1, 10)
        val text = DailySummary.targets(linkedMapOf(TargetId.TELEGRAM to stats,
            TargetId.MATRIX to stats.copy(okMs = 0, downMs = 60000)))!!
        val values = tree(text.body)
        assertTrue(values.any { it.key == TextKey.TARGET_TELEGRAM })
        assertTrue(values.any { it.key == TextKey.TARGET_MATRIX })
        val percentages = values.flatMap { it.args }.filterIsInstance<TextArgument.Decimal>().map { it.value }
        assertEquals(listOf(100.0, 0.0), percentages)
        assertFalse(50.0 in percentages)
    }
    @Test fun targetWithUnavailableHistoryIsExplicitWhileOtherTargetCanBeReported() {
        val stats = TimeStats(60000, 0, 0, 0, 0, 0, 1, 10)
        val text = DailySummary.targets(linkedMapOf(TargetId.MATRIX to null, TargetId.WHATSAPP to stats))!!
        val unavailable = tree(text.body).first { it.key == TextKey.DAILY_UNAVAILABLE }
        assertEquals(listOf(TextArgument.Value(TargetNames.text(TargetId.MATRIX))), unavailable.args)
        assertNull(DailySummary.targets(emptyMap()))
    }
    @Test fun incidentNamesOnlySelectedGoal() {
        val episode = OutageEpisode(1_000, 2_000, true, false, 1_000, 0, 0, 0, 0)
        val card = IncidentPresentation.describe(episode, targetId = TargetId.MATRIX)
        assertTrue(card.allKeys().contains(TextKey.TARGET_MATRIX))
        assertFalse(card.allKeys().contains(TextKey.TARGET_TELEGRAM))
    }
}
