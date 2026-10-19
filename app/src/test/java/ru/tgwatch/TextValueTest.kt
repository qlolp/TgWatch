package ru.tgwatch

import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class TextValueTest {
    @Test fun durationPartsRetainMeasurementsWithoutChoosingALanguage() {
        assertEquals(DurationParts(0, 0, 0, 0), DurationParts.from(0))
        assertEquals(DurationParts(0, 0, 0, 5), DurationParts.from(5000))
        assertEquals(DurationParts(0, 2, 15, 0), DurationParts.from(135 * 60000L))
        assertEquals(DurationParts(3, 4, 0, 0), DurationParts.from(76 * 3600000L))
        assertEquals(TextKey.DURATION_SECONDS, DurationParts.from(0).text().key)
        assertEquals(listOf(TextArgument.Number(2), TextArgument.Number(15)),
            DurationParts.from(135 * 60000L).text().args)
        assertEquals(TextKey.RECOVERY_MINUTES_SECONDS, DurationParts.from(1044000).text(precise = true).key)
        assertEquals(listOf(TextArgument.Number(17), TextArgument.Number(24)),
            DurationParts.from(1044000).text(precise = true).args)
        assertEquals(DurationParts.from(0), DurationParts.from(-1))
        assertTrue(DurationParts.from(Long.MAX_VALUE).days > 0)
    }
    @Test fun argumentSnapshotCannotChangeAfterPreparation() {
        val arguments = mutableListOf<TextArgument>(TextArgument.Number(1))
        val text = TextValue(TextKey.DAILY_CHECKS, arguments)
        arguments[0] = TextArgument.Number(2)
        assertEquals(listOf(TextArgument.Number(1)), text.args)
    }
    @Test fun dailySummaryHasTypedPercentAndNoInventedMissingSections() {
        val stats = TimeStats(86400000, 0, 0, 0, 0, 0, 2880, 142)
        val text = DailySummary.text(stats)!!
        assertEquals(TextKey.DAILY_TITLE_PERCENT, text.title.key)
        assertEquals(TextArgument.Decimal(100.0), text.title.args.last())
        val lines = text.body.args.map { (it as TextArgument.Value).value }
        assertEquals(listOf(TextKey.DAILY_NO_OUTAGES, TextKey.DAILY_LATENCY, TextKey.DAILY_CHECKS), lines.map { it.key })
        assertEquals(TextArgument.Number(2880), lines.last().args.single())
        assertNull(DailySummary.text(TimeStats(0, 0, 0, 0, 86400000, 0, 0, -1)))
    }
    @Test fun ongoingSummaryPreservesPreciseDurationAndCount() {
        val stats = TimeStats(3600000, 90000, 0, 0, 0, 90000, 100, -1,
            outageCount = 1, lastOutageMs = 90000, lastOutageOngoing = true)
        val first = (DailySummary.text(stats)!!.body.args.first() as TextArgument.Value).value
        assertEquals(TextKey.DAILY_ONGOING, first.key)
        assertTrue(first.args.contains(TextArgument.Number(1)))
        assertTrue(first.args.contains(TextArgument.Duration(DurationParts.from(90000), precise = true)))
    }
    @Test fun lastSuccessCarriesTimestampZoneAndRollbackKey() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals(TextKey.LAST_SUCCESS_EMPTY, LastSuccessPolicy.label(0, 3000, utc).key)
        val value = LastSuccessPolicy.label(2000, 3000, utc)
        assertEquals(TextKey.LAST_SUCCESS, value.key)
        assertEquals(listOf(TextArgument.Number(2000), TextArgument.Text("UTC")), value.args)
        assertEquals(TextKey.LAST_SUCCESS_CLOCK_CHANGED, LastSuccessPolicy.label(2000, 1000, utc).key)
    }
    @Test fun profileAndPatternTitlesAreSemanticKeys() {
        assertEquals(TextKey.PROFILE_BALANCED, PowerProfile.BALANCED.title.key)
        assertEquals(TextKey.PATTERN_SHORT, AlarmPattern.SHORT.title.key)
    }
}
