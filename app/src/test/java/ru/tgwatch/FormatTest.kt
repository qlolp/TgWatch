package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class FormatTest {
    @Test fun durationsRetainWholeSeconds() {
        for (seconds in listOf(0L, 12L, 59L)) {
            val value = DurationParts.from(seconds * 1000).text()
            assertEquals(TextKey.DURATION_SECONDS, value.key)
            assertEquals(listOf(TextArgument.Number(seconds)), value.args)
        }
    }
    @Test fun minutesAndHoursRetainAmounts() {
        for ((ms, key, values) in listOf(Triple(60_000L, TextKey.DURATION_MINUTES, listOf(1L)),
            Triple(300_000L, TextKey.DURATION_MINUTES, listOf(5L)),
            Triple(7_200_000L, TextKey.DURATION_HOURS, listOf(2L)),
            Triple(8_100_000L, TextKey.DURATION_HOURS_MINUTES, listOf(2L, 15L)))) {
            val value = DurationParts.from(ms).text()
            assertEquals(key, value.key)
            assertEquals(values.map(TextArgument::Number), value.args)
        }
    }
    @Test fun daysRetainAmounts() {
        assertEquals(listOf(TextArgument.Number(1)), DurationParts.from(86_400_000).text().args)
        assertEquals(listOf(TextArgument.Number(3), TextArgument.Number(4)),
            DurationParts.from(273_600_000).text().args)
    }
    @Test fun recentAgesDistinguishMissingAndJustNowFromMeasuredDuration() {
        val now = 1_000_000L
        assertEquals(textLiteral("—"), ageText(0, now))
        assertEquals(TextKey.JUST_NOW, ageText(now - 500, now).key)
        val elapsed = ageText(now - 5000, now)
        assertEquals(TextKey.AGO, elapsed.key)
        assertEquals(listOf(TextArgument.Duration(DurationParts(0, 0, 0, 5))), elapsed.args)
    }
    @Test fun percentageRetainsUnroundedMeasurementUntilResourceFormatting() {
        for (value in listOf(100.0, 99.96, 50.0, 33.33))
            assertEquals(listOf(TextArgument.Decimal(value)), textOf(TextKey.PERCENT, TextArgument.Decimal(value)).args)
    }
}
