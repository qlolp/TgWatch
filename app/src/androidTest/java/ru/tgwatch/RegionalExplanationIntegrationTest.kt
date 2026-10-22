package ru.tgwatch

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegionalExplanationIntegrationTest {
    @Test fun realLocalizedExplanationDistinguishesFailuresAndGenericDenials() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = mapOf("en" to listOf("DNS lookup failed", "TLS connection failed", "does not establish a regional block"),
            "ru" to listOf("Ошибка DNS", "Ошибка TLS", "не доказывает региональную блокировку"))
        expected.forEach { (locale, phrases) ->
            val ctx = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(locale))
            })
            fun result(status: RegionalStatus, failure: RegionalFailure? = null, code: Int? = null) =
                RegionalResult(RegionalService.GEMINI, status, 100, 100, 1, 1, 1, code, failure)
            val dns = RegionalExplanationUi.body(ctx, result(RegionalStatus.NETWORK_ERROR, RegionalFailure.DNS))
            val tls = RegionalExplanationUi.body(ctx, result(RegionalStatus.NETWORK_ERROR, RegionalFailure.TLS))
            val denial = RegionalExplanationUi.body(ctx, result(RegionalStatus.UNKNOWN, code = 403))
            assertTrue(dns, dns.contains(phrases[0]))
            assertTrue(tls, tls.contains(phrases[1]))
            assertTrue(denial, denial.contains(phrases[2]))
            assertTrue(denial.contains("403"))
            assertTrue(dns.contains(ctx.getString(R.string.regional_source_public)))
            assertEquals(ctx.getString(R.string.regional_why_unmeasured), RegionalExplanationUi.body(ctx, null))
        }
    }
}
