package ru.tgwatch

import org.junit.Assert.assertTrue
import org.junit.Test

class DozeStatsTest {
    @Test
    fun describe_contract_by_language() {
        Lang.ru = true
        assertTrue(
            "Пропущено во сне (оценка): 3".contains("Пропущено") &&
                "Missed while asleep (estimate): 3".contains("Missed"),
        )
        Lang.ru = false
        assertTrue("Missed while asleep (estimate): 0".startsWith("Missed"))
    }
}