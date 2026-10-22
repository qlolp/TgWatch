package ru.tgwatch

import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RegionalVerificationIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private lateinit var backup: TargetBackupSnapshot

    @Before fun isolate() {
        HistoryTestStorage.awaitReady()
        backup = BackupManager.snapshotTargets(ctx)
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        RegionalRepository.clearForRestore(ctx)
    }

    @After fun restore() {
        if (::backup.isInitialized) BackupManager.restoreTargets(ctx, backup)
    }

    @Test fun realReportLabelsAreAttributedInBothLanguages() {
        listOf("en" to "reported by you", "ru" to "сообщено вами").forEach { (locale, expected) ->
            val config = android.content.res.Configuration(ctx.resources.configuration).apply {
                setLocales(android.os.LocaleList.forLanguageTags(locale))
            }
            val localized = ctx.createConfigurationContext(config)
            RegionalVerificationLayer.entries.forEach {
                assertTrue(RegionalVerificationUi.source(localized, it).contains(expected))
            }
            val noError = RegionalVerificationUi.outcome(localized, RegionalVerificationOutcome.NO_EXPLICIT_ERROR)
            assertTrue(noError.contains(if (locale == "ru") "функции не проверены" else "features unverified"))
        }
    }

    @Test fun reportedFeatureWritesOnlyManualHistoryAndRestoreRejectsAnOldCapturedReport() {
        val service = RegionalService.GEMINI
        val original = RegionalResult(service, RegionalStatus.AUTH_REQUIRED, System.currentTimeMillis(),
            android.os.SystemClock.elapsedRealtime(), 7, 1, 2, 401)
        RegionalStateStore.put(original)
        val generation = RegionalRepository.generation(ctx)
        val note = RegionalVerification(service, RegionalVerificationLayer.FEATURE,
            RegionalVerificationOutcome.WORKS, System.currentTimeMillis(),
            android.os.SystemClock.elapsedRealtime(), RegionalContext.active(ctx))
        assertTrue(RegionalVerificationRepository.record(ctx, note, generation).get(10, TimeUnit.SECONDS))
        assertEquals(original, RegionalStateStore.get(service))
        val saved = RegionalRepository.snapshot(ctx).rows.single { it.kind == RegionalEventKind.FEATURE }
        assertEquals(RegionalVerificationOutcome.WORKS, saved.verification)
        assertNull(saved.status)
        BackupManager.restoreTargets(ctx, backup)
        assertNull(RegionalVerificationRepository.current(ctx, service, RegionalVerificationLayer.FEATURE))
        assertFalse(RegionalVerificationRepository.record(ctx, note, generation).get(10, TimeUnit.SECONDS))
        assertTrue(RegionalRepository.snapshot(ctx).rows.isEmpty())
    }

    @Test fun threeSourcesRemainSeparateAtLargeFontAndOpeningTheBrowserRecordsNoSuccess() {
        val previous = android.content.res.Configuration(ctx.resources.configuration)
        val enlarged = android.content.res.Configuration(previous).apply { fontScale = 2f }
        @Suppress("DEPRECATION")
        ctx.resources.updateConfiguration(enlarged, ctx.resources.displayMetrics)
        // A filter without a data scheme also matches an explicit intent with no action/data.
        // Intercept only the HTTPS browser navigation, never ActivityScenario's own launch/recreation.
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("https") },
            Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null), true)
        try {
            ActivityScenario.launch<RegionalServicesActivity>(Intent(ctx, RegionalServicesActivity::class.java))
                .use { scenario ->
                    scenario.onActivity { activity ->
                        val all = views(activity.findViewById(android.R.id.content))
                        val texts = all.filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
                        listOf(R.string.regional_source_public, R.string.regional_source_browser_reported,
                            R.string.regional_source_feature_reported).forEach {
                            assertTrue(texts, texts.contains(activity.getString(it)))
                        }
                        all.filterIsInstance<Button>().forEach {
                            assertTrue(it.minimumHeight >= (48 * activity.resources.displayMetrics.density).toInt())
                        }
                        RegionalExplanationUi.open(activity, RegionalService.GEMINI)
                    }
                    instrumentation.waitForIdleSync()
                    assertEquals(1, monitor.hits)
                    assertTrue(RegionalRepository.snapshot(ctx).rows.isEmpty())
                    scenario.recreate()
                    scenario.onActivity { activity ->
                        assertTrue(views(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>()
                            .any { it.text.contains(activity.getString(R.string.regional_source_feature_reported)) })
                    }
                }
        } finally {
            instrumentation.removeMonitor(monitor)
            @Suppress("DEPRECATION")
            ctx.resources.updateConfiguration(previous, ctx.resources.displayMetrics)
        }
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
