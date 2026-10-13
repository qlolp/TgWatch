package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class WebhookHealthTextTest {
    @Test fun englishUsesMonotonicAgesAndShowsTimeoutWhilePhysicalRequestRemains() {
        withLanguage(false) {
            val text = WebhookHealthText.format(
                WebhookHealth(10_000, 5_000, WebhookOutcome.TIMED_OUT, true, transportBlocked = true), 20_000)
            assertTrue(text.contains("Last attempt: 10 s ago"))
            assertTrue(text.contains("Last success: 15 s ago"))
            assertTrue(text.contains("Outcome: timed out"))
            assertTrue(text.contains("Request still running: yes"))
            assertTrue(text.contains("Delivery blocked: the previous request has not finished"))
            assertFalse(text.contains("https://"))
        }
    }

    @Test fun russianShowsProcessLifetimeAndClearedHealth() {
        withLanguage(true) {
            val text = WebhookHealthText.format(WebhookHealth(), 500_000)
            assertTrue(text.contains("в этом процессе"))
            assertTrue(text.contains("Последняя попытка: нет"))
            assertTrue(text.contains("Последний успех: нет"))
            assertTrue(text.contains("Результат: попыток не было"))
            assertTrue(text.contains("Запрос ещё выполняется: нет"))
        }
    }

    private fun withLanguage(ru: Boolean, block: () -> Unit) {
        val previous = Lang.ru
        try { Lang.ru = ru; block() } finally { Lang.ru = previous }
    }
}
