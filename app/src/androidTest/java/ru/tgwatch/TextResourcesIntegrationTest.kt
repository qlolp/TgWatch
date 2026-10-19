package ru.tgwatch

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextResourcesIntegrationTest {
    private fun context(language: String): Context {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        return app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(language)))
        })
    }
    @Test fun durationsAndPreciseRecoveryKeepExactRuAndEnCopy() {
        val en = context("en")
        val ru = context("ru")
        for ((ms, english, russian) in listOf(Triple(0L, "0 s", "0 с"), Triple(5000L, "5 s", "5 с"),
            Triple(12000L, "12 s", "12 с"), Triple(59000L, "59 s", "59 с"),
            Triple(60000L, "1 min", "1 мин"), Triple(300000L, "5 min", "5 мин"),
            Triple(7200000L, "2 h", "2 ч"), Triple(86400000L, "1 d", "1 д"),
            Triple(8100000L, "2 h 15 min", "2 ч 15 мин"), Triple(273600000L, "3 d 4 h", "3 д 4 ч"))) {
            assertEquals(english, AndroidText.resolve(en, DurationParts.from(ms).text()))
            assertEquals(russian, AndroidText.resolve(ru, DurationParts.from(ms).text()))
        }
        assertEquals("17 min 24 s", AndroidText.resolve(en, DurationParts.from(1044000).text(precise = true)))
        assertEquals("17 мин 24 с", AndroidText.resolve(ru, DurationParts.from(1044000).text(precise = true)))
    }
    @Test fun quietSummaryAndPercentRoundingResolveWithoutOpeningMainActivity() {
        val text = DailySummary.text(TimeStats(86400000, 0, 0, 0, 0, 0, 2880, 142))!!
        val en = context("en")
        val ru = context("ru")
        assertEquals("Telegram in the last 24 h: 100%", AndroidText.resolve(en, text.title))
        assertEquals("Telegram за сутки: 100 %", AndroidText.resolve(ru, text.title))
        assertEquals("No Telegram outages\nAverage response: 142 ms\nChecks: 2880", AndroidText.resolve(en, text.body))
        assertEquals("Сбоев Telegram не было\nСредний отклик: 142 мс\nПроверок: 2880", AndroidText.resolve(ru, text.body))
        assertEquals("99.9%", AndroidText.resolve(en, TextValue(TextKey.PERCENT, listOf(TextArgument.Decimal(99.96)))))
        assertEquals("99,9 %", AndroidText.resolve(ru, TextValue(TextKey.PERCENT, listOf(TextArgument.Decimal(99.96)))))
        assertEquals("33.3%", AndroidText.percent(en, 33.33))
        assertEquals("33,3 %", AndroidText.percent(ru, 33.33))
        assertEquals(AndroidText.resolve(en, text.title),
            AndroidText.resolve(en.createDeviceProtectedStorageContext(), text.title))
    }
    @Test fun countsUseQuantityResourcesAcrossRuPluralBoundaries() {
        for (count in listOf(0, 1, 2, 5, 21)) {
            assertEquals("Checks: $count", AndroidText.quantity(context("en"), TextKey.DAILY_CHECKS, count))
            assertEquals("Проверок: $count", AndroidText.quantity(context("ru"), TextKey.DAILY_CHECKS, count))
        }
    }
    @Test fun historySummaryAndNontrivialDailyCasesKeepExactTranslatedValues() {
        val stats = TimeStats(72000000, 600000, 0, 0, 14400000, 600000, 100, 150, outageCount = 1)
        val english = AndroidText.resolve(context("en"), History.describeStats(stats, 1)).lines()
        assertEquals("TG Monitor — last 24 hours", english.first())
        assertTrue(english.contains("Longest confirmed outage: 10 min"))
        val russian = AndroidText.resolve(context("ru"), History.describeStats(stats, 1)).lines()
        assertEquals("TG Монитор — за сутки", russian.first())
        assertTrue(russian.contains("Самый долгий подтверждённый сбой: 10 мин"))
        val ongoing = DailySummary.text(TimeStats(3600000, 90000, 0, 0, 0, 90000, 100, -1,
            outageCount = 1, lastOutageMs = 90000, lastOutageOngoing = true))!!
        assertEquals("Сбоев Telegram: 1, сбой продолжается 1 мин 30 с",
            AndroidText.resolve(context("ru"), ongoing.body).lines().first())
        assertEquals("Telegram outages: 1, ongoing for 1 min 30 s",
            AndroidText.resolve(context("en"), ongoing.body).lines().first())
    }

    @Test fun contextFormattingResolvesTypedArgumentsBeforeAndroidInterpolation() {
        val value = DurationParts.from(5000).text()
        assertEquals("Custom HTTPS outage: 5 s; no internet: 5 s", context("en").renderString(
            R.string.text_history_outage, TargetNames.text(TargetId.CUSTOM), value, value))
        assertEquals("Сбой Свой HTTPS: 5 с; нет сети: 5 с", context("ru").renderString(
            R.string.text_history_outage, TargetNames.text(TargetId.CUSTOM), value, value))
    }

    @Test fun lastSuccessAndSemanticTitlesUseTheSuppliedResourceLocale() {
        val utc = TimeZone.getTimeZone("UTC")
        val value = LastSuccessPolicy.label(2000, 1000, utc)
        assertEquals("Clock changed · OK 01.01 00:00:02", AndroidText.resolve(context("en"), value))
        assertEquals("Часы изменились · OK 01.01 00:00:02", AndroidText.resolve(context("ru"), value))
        assertEquals("Balanced", AndroidText.resolve(context("en"), PowerProfile.BALANCED.title))
        assertEquals("Обычный", AndroidText.resolve(context("ru"), PowerProfile.BALANCED.title))
        assertEquals("Short", AndroidText.resolve(context("en"), AlarmPattern.SHORT.title))
        assertEquals("Короткий", AndroidText.resolve(context("ru"), AlarmPattern.SHORT.title))
    }
}
