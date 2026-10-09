package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {

    @Test
    fun durationStr_seconds() {
        assertEquals("0 с", durationStr(0))
        assertEquals("12 с", durationStr(12_000))
        assertEquals("59 с", durationStr(59_000))
    }

    @Test
    fun durationStr_minutesAndHours() {
        assertEquals("1 мин", durationStr(60_000))
        assertEquals("5 мин", durationStr(5 * 60_000))
        assertEquals("2 ч", durationStr(2 * 60 * 60_000L))
        assertEquals("2 ч 15 мин", durationStr((2 * 60 + 15) * 60_000L))
    }

    @Test
    fun durationStr_days() {
        assertEquals("1 д", durationStr(24 * 60 * 60_000L))
        assertEquals("3 д 4 ч", durationStr((3 * 24 + 4) * 60 * 60_000L))
    }

    @Test
    fun agoStr_recent() {
        val now = 1_000_000L
        assertEquals("—", agoStr(0, now))
        assertEquals("только что", agoStr(now - 500, now))
        assertTrue(agoStr(now - 5_000, now).endsWith(" назад"))
    }

    @Test
    fun formatPercent_roundsNicely() {
        assertEquals("100 %", formatPercent(100.0))
        assertEquals("99,9 %", formatPercent(99.96))
        assertEquals("50 %", formatPercent(50.0))
        assertEquals("33,3 %", formatPercent(33.33))
    }
}
