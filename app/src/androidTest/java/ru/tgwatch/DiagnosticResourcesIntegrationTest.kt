package ru.tgwatch

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class DiagnosticResourcesIntegrationTest {
    private fun context(language: String): Context {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        return app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(language)))
        })
    }
    @Test fun networkFailuresUseCallerResourcesAndNeverExceptionMessages() {
        val failure = PhoneProbeAdapter.error(IllegalStateException("https://private.example/token"))
        for ((language, expected) in listOf("en" to "server returned HTTP error 503",
            "ru" to "сервер вернул ошибку HTTP 503")) {
            val ctx = context(language)
            assertEquals(expected, AndroidText.resolve(ctx, ProbeRules.describeHttpFailure(503)))
            assertEquals("IllegalStateException", AndroidText.resolve(ctx, failure))
            assertEquals(if (language == "ru") "Таймаут" else "Timeout", AndroidText.resolve(ctx,
                PhoneProbeAdapter.detail(ru.tgwatch.probe.ProbeDetail(ru.tgwatch.probe.ProbeDetailCode.TIMEOUT))))
            assertEquals(if (language == "ru") "Виджет: не удалось обратиться к мониторингу (IllegalStateException)"
                else "Widget: could not reach monitoring (IllegalStateException)", AndroidText.resolve(ctx,
                LaunchFailures.message(LaunchFailures.Source.WIDGET, IllegalStateException("secret"))))
        }
    }
    @Test fun formatAdaptersPreserveAgesPercentAndExactRecoveryAtAndroidBoundary() {
        for (language in listOf("en", "ru")) {
            val ctx = context(language)
            assertEquals("—", agoStr(ctx, 0, 10000))
            assertEquals(if (language == "ru") "только что" else "just now", agoStr(ctx, 9500, 10000))
            assertEquals(if (language == "ru") "5 с назад" else "5 s ago", agoStr(ctx, 5000, 10000))
            assertEquals(if (language == "ru") "1 ч 2 мин 3 с" else "1 h 2 min 3 s", recoveryDurationStr(ctx, 3723000))
            for ((value, english, russian) in listOf(Triple(100.0, "100%", "100 %"),
                Triple(99.96, "99.9%", "99,9 %"), Triple(50.0, "50%", "50 %"), Triple(33.33, "33.3%", "33,3 %")))
                assertEquals(if (language == "ru") russian else english, formatPercent(ctx, value))
        }
    }
    @Test fun incidentSummaryPreservesDatesCensoringAndSeparateCoverageKinds() {
        val episode = OutageEpisode(0, 60000, recovered = false, startCensored = true,
            downMs = 10000, partialMs = 20000, offlineMs = 5000, unknownMs = 25000, clockEpoch = 0)
        val value = IncidentPresentation.describe(episode, TimeZone.getTimeZone("UTC"), TargetId.CUSTOM)
        for (language in listOf("en", "ru")) {
            val text = AndroidText.resolve(context(language), value)
            assertTrue(text.contains("1970-01-01 00:00:00 Z"))
            assertTrue(text.contains("1970-01-01 00:01:00 Z"))
            assertTrue(text.contains(if (language == "ru") "Начало полностью не наблюдалось" else "The full onset was not observed"))
            assertTrue(text.contains(if (language == "ru") "Сбой Свой HTTPS: 10 с" else "Custom HTTPS outage: 10 s"))
            assertTrue(text.contains(if (language == "ru") "Восстановление не наблюдалось" else "Recovery was not observed"))
            for (marker in listOf("https://", "token", "nonce", "clockEpoch")) assertFalse(text.contains(marker))
        }
    }
}
