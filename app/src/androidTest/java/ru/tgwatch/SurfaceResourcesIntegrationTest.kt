package ru.tgwatch

import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SurfaceResourcesIntegrationTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = ctx.getSystemService(NotificationManager::class.java)
    private fun context(language: String): Context = ctx.createConfigurationContext(
        Configuration(ctx.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(language))) })
    @After fun restoreVisibleChannelNames() { EventNotifications(ctx, manager) }

    @Test fun channelNamesUseTheirOwnContextBeforeMainActivityOpens() {
        for ((language, expected) in listOf("en" to "Silent events", "ru" to "События без звука")) {
            EventNotifications(context(language), manager)
            assertEquals(expected, manager.getNotificationChannel(EventNotifications.SILENT_CHANNEL).name.toString())
        }
    }
    @Test fun deliveryHealthAndTextWebhookResolveExactlyInBothLanguages() {
        val event = WebhookEvent(WebhookEventKind.UNREACHABLE,
            WebhookObservation("custom", "DOWN", 1000, null, NetworkKind.WIFI, 1, true, false), "OK")
        for ((language, expected) in listOf("en" to "TG Monitor: Custom HTTPS is unreachable",
            "ru" to "TG Монитор: Свой HTTPS недоступен")) {
            val display = context(language)
            val body = WebhookPayload.encode(event, WebhookFormat.TEXT).resolved(display)
            assertNull(body.textValue)
            assertEquals(expected, body.bytes.toString(Charsets.UTF_8))
            val health = AndroidText.resolve(display, WebhookHealthText.format(
                WebhookHealth(10_000, 5_000, WebhookOutcome.TIMED_OUT, true, transportBlocked = true), 20_000))
            assertTrue(health.contains(if (language == "en") "Last attempt: 10 s ago" else "Последняя попытка: 10 с назад"))
            assertTrue(health.contains(if (language == "en") "Last success: 15 s ago" else "Последний успех: 15 с назад"))
            assertTrue(health.contains(if (language == "en") "Outcome: timed out" else "Результат: время ожидания истекло"))
            assertTrue(health.contains(if (language == "en") "Delivery blocked: the previous request has not finished"
                else "Доставка заблокирована: предыдущий запрос ещё не завершён"))
        }
    }
}
