package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** English text built in code; every other JVM test keeps the Russian default. */
class LangTest {
    private val hour = 3_600_000L

    private fun english(block: () -> Unit) {
        Lang.ru = false
        try {
            block()
        } finally {
            Lang.ru = true
        }
    }

    @Test fun durationsAndAgesUseEnglishUnits() = english {
        assertEquals("0 s", durationStr(0))
        assertEquals("5 min", durationStr(5 * 60_000L))
        assertEquals("2 h 15 min", durationStr(135 * 60_000L))
        assertEquals("3 d 4 h", durationStr((3 * 24 + 4) * hour))
        assertEquals("17 min 24 s", recoveryDurationStr(1_044_000))
        assertEquals("just now", agoStr(999_500, 1_000_000))
        assertEquals("5 s ago", agoStr(995_000, 1_000_000))
    }

    @Test fun percentagesUseEnglishFormatting() = english {
        assertEquals("100%", formatPercent(100.0))
        assertEquals("99.9%", formatPercent(99.96))
        assertEquals("33.3%", formatPercent(33.33))
    }

    @Test fun dailySummaryIsEnglish() = english {
        val text = DailySummary.text(TimeStats(24 * hour, 0, 0, 0, 0, 0, 2880, 142))!!
        assertTrue(text.title, text.title.startsWith("Telegram in the last 24 h"))
        assertEquals("Telegram in the last 24 h: 100%", text.title)
        assertEquals("No Telegram outages\nAverage response: 142 ms\nChecks: 2880", text.body)
    }

    @Test fun statsSummaryAndTitlesAreEnglish() = english {
        val stats = TimeStats(20 * hour, 10 * 60_000L, 0, 0, 4 * hour, 10 * 60_000L, 100, 150, outageCount = 1)
        val lines = History.describeStats(stats, 1).lines()
        assertEquals("TG Monitor — last 24 hours", lines.first())
        assertTrue(lines.any { it.startsWith("Longest confirmed outage: 10 min") })
        assertEquals("Short", AlarmPattern.SHORT.title)
        assertEquals("Balanced", PowerProfile.BALANCED.title)
        assertEquals("Last OK: —", LastSuccessPolicy.label(0, 1000))
    }

    @Test fun russianRemainsTheJvmDefault() {
        assertTrue(Lang.ru)
        assertEquals("2 ч 15 мин", durationStr(135 * 60_000L))
        assertEquals("Обычный", PowerProfile.BALANCED.title)
    }
}
