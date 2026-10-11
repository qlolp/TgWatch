package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Test

class DozeStatsTest {
    @Test
    fun describe_contract_by_language() {
        val previous = Lang.ru
        try {
            Lang.ru = true
            assertEquals("Пропущено во сне (оценка): 3", Lang.t("Пропущено во сне (оценка): 3", "Missed while asleep (estimate): 3"))
            Lang.ru = false
            assertEquals("Missed while asleep (estimate): 0", Lang.t("Пропущено во сне (оценка): 0", "Missed while asleep (estimate): 0"))
        } finally {
            Lang.ru = previous
        }
    }
}