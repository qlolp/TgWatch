package ru.tgwatch

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompletionContractsIntegrationTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun realEnglishAndRussianResourcesNameTheCalendarPeriod() {
        val totals = mapOf(NetworkKind.WIFI to NetCounts(ok = 5), NetworkKind.CELLULAR to NetCounts(ok = 5))
        for ((locale, calendarWord) in listOf(Locale.ENGLISH to "calendar", Locale.forLanguageTag("ru") to "календар")) {
            val localized = ctx.createConfigurationContext(Configuration(ctx.resources.configuration).apply {
                setLocale(locale)
            })
            for (days in listOf(1, 7)) {
                val rendered = AndroidText.resolve(localized, requireNotNull(NetworkTally.describe(totals, days)))
                assertTrue(rendered, rendered.contains(calendarWord))
                assertTrue(rendered, rendered.contains("Wi-Fi"))
                assertTrue(rendered, rendered.contains("100"))
                if (days == 7) assertTrue(rendered, rendered.contains("7"))
            }
        }
    }

    @Test fun realPlatformMetadataKeepsCodeFramesAndDropsPrivateNamesAndPaths() {
        val directory = File(ctx.cacheDir, "completion-crash-contract").apply { mkdirs() }
        try {
            val failure = IllegalStateException("SECRET_NATIVE").apply {
                stackTrace = arrayOf(StackTraceElement("java.lang.Thread", "run", "Thread.java", 42),
                    StackTraceElement("ru.tgwatch.SECRET_NATIVE", "work", "Source.kt", 1),
                    StackTraceElement("java.lang.Thread", "SECRET_NATIVE", "/private/SECRET_NATIVE.kt", 3))
            }
            CrashJournal.record(directory, Thread({}, "https://private.example/SECRET_NATIVE"), failure, 1_000)
            val raw = File(directory, "crashes-v1.txt").readText()
            assertFalse(raw, raw.contains("SECRET_NATIVE"))
            assertFalse(raw, raw.contains("private.example"))
            assertTrue(raw, raw.contains("java.lang.Thread.run(Thread.java:42)"))
            assertEquals("other", CrashJournal.read(directory).single().thread)
        } finally {
            directory.deleteRecursively()
        }
    }
}
