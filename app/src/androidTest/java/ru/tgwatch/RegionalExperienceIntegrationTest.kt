package ru.tgwatch

import android.content.Intent
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

@RunWith(AndroidJUnit4::class)
class RegionalExperienceIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private lateinit var backup: TargetBackupSnapshot
    private val scope = "0123456789abcdef0123456789abcdef"

    @Before fun isolate() {
        HistoryTestStorage.awaitReady()
        backup = BackupManager.snapshotTargets(ctx)
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        RegionalRepository.clearForRestore(ctx)
    }

    @After fun restore() {
        if (!::backup.isInitialized) return
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        BackupManager.restoreTargets(ctx, backup)
    }

    @Test fun realRepositoryPersistsOnlyFixedMetadataAndRestoreClearsIt() {
        val now = System.currentTimeMillis()
        val row = RegionalJournalEntry(RegionalEventKind.PUBLIC, now, scope, RegionalRoute.VPN,
            "DE", now, RegionalService.GEMINI, RegionalStatus.REGION_RESTRICTED, true, 403)
        RegionalRepository.recordEntries(ctx, listOf(row)).get(10, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(listOf(row), RegionalRepository.snapshot(ctx).rows)
        val disk = RegionalJournalStorage(RegionalRepository.fileFor(ctx)).read()
        assertEquals(listOf(row), disk)
        assertFalse(RegionalRepository.fileFor(ctx).readText().contains("https://"))
        BackupManager.restoreTargets(ctx, backup)
        assertTrue(RegionalRepository.snapshot(ctx).rows.isEmpty())
        assertTrue(RegionalJournalStorage(RegionalRepository.fileFor(ctx)).read().isEmpty())
    }

    @Test fun contextChangesNeverReviveOldCountryAndHistoryShowsSources() {
        val first = RegionalContext.current(ctx, 42, 1)
        val next = RegionalContext.current(ctx, 43, 1)
        assertNotEquals(first.token, next.token)
        val changed = RegionalContext.current(ctx, 43, 2)
        assertNotEquals(next.token, changed.token)
        val now = System.currentTimeMillis()
        RegionalRepository.recordEntries(ctx, listOf(RegionalJournalEntry(RegionalEventKind.PUBLIC, now,
            scope, RegionalRoute.WIFI, service = RegionalService.YOUTUBE,
            status = RegionalStatus.AUTH_REQUIRED, httpCode = 401))).get(10, java.util.concurrent.TimeUnit.SECONDS)
        ActivityScenario.launch<RegionalHistoryActivity>(Intent(ctx, RegionalHistoryActivity::class.java)).use { scenario ->
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
            var ready = false
            while (!ready && android.os.SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { activity ->
                    ready = views(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>()
                        .any { it.text.contains("YouTube") }
                }
                if (!ready) Thread.sleep(25)
            }
            assertTrue("Prepared history must arrive", ready)
            scenario.onActivity { activity ->
                val all = views(activity.findViewById(android.R.id.content))
                val text = all.filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
                assertTrue(text.contains("YouTube"))
                assertTrue(text.contains(activity.getString(R.string.regional_source_public)))
                val buttons = all.filterIsInstance<Button>()
                assertTrue(buttons.isNotEmpty())
                buttons.forEach { assertTrue(it.minimumHeight >= (48 * activity.resources.displayMetrics.density).toInt()) }
            }
        }
    }

    @Test fun savedComparisonsStayHistoricalAndCannotReturnAfterRestore() {
        val now = System.currentTimeMillis()
        val connection = RegionalConnection(scope, RegionalRoute.VPN)
        val row = RegionalJournalEntry(RegionalEventKind.PUBLIC, now - 2_000, scope, RegionalRoute.VPN,
            service = RegionalService.GEMINI, status = RegionalStatus.WEB_REACHABLE, httpCode = 200)
        val a = RegionalComparisonSnapshot("Connection A", now - 1_500, connection,
            mapOf(RegionalService.GEMINI to row))
        val pair = RegionalComparisonPair(a, a.copy(label = "Connection B", at = now - 1_000))
        val generation = RegionalRepository.generation(ctx)
        assertTrue(RegionalComparisonRepository.save(ctx, pair, generation)
            .get(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(listOf(pair), RegionalComparisonRepository.snapshot(ctx).pairs)
        val previous = android.content.res.Configuration(ctx.resources.configuration)
        val enlarged = android.content.res.Configuration(previous).apply { fontScale = 2f }
        @Suppress("DEPRECATION")
        ctx.resources.updateConfiguration(enlarged, ctx.resources.displayMetrics)
        try {
            ActivityScenario.launch<RegionalComparisonActivity>(Intent(ctx, RegionalComparisonActivity::class.java))
                .use { scenario ->
                    awaitText(scenario, "Connection A")
                    scenario.onActivity { activity ->
                        val all = views(activity.findViewById(android.R.id.content))
                        val buttons = all.filterIsInstance<Button>()
                        assertFalse(buttons.single { it.text == activity.getString(R.string.regional_compare_b) }.isEnabled)
                        buttons.filter { it.visibility == View.VISIBLE }.forEach {
                            assertTrue(it.height >= (48 * activity.resources.displayMetrics.density).toInt())
                        }
                        val text = all.filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
                        assertTrue(text.contains(activity.getString(R.string.regional_source_public)))
                    }
                    scenario.recreate()
                    scenario.onActivity { activity ->
                        assertFalse(views(activity.findViewById(android.R.id.content)).filterIsInstance<Button>()
                            .single { it.text == activity.getString(R.string.regional_compare_b) }.isEnabled)
                    }
                }
        } finally {
            @Suppress("DEPRECATION")
            ctx.resources.updateConfiguration(previous, ctx.resources.displayMetrics)
        }
        BackupManager.restoreTargets(ctx, backup)
        assertTrue(RegionalComparisonRepository.snapshot(ctx).pairs.isEmpty())
        assertFalse(RegionalComparisonRepository.save(ctx, pair, generation)
            .get(10, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(RegionalComparisonStorage(RegionalComparisonRepository.fileFor(ctx)).read().isEmpty())
    }

    @Test fun aQueuedSuccessfulSaveCallbackCannotClaimSuccessAfterTheRestoreFenceMoves() {
        val now = System.currentTimeMillis()
        val row = RegionalJournalEntry(RegionalEventKind.PUBLIC, now - 2_000, scope, RegionalRoute.WIFI,
            service = RegionalService.GEMINI, status = RegionalStatus.WEB_REACHABLE, httpCode = 200)
        val side = RegionalComparisonSnapshot("A", now - 1_500, RegionalConnection(scope, RegionalRoute.WIFI),
            mapOf(RegionalService.GEMINI to row))
        val pair = RegionalComparisonPair(side, side.copy(label = "B", at = now - 1_000))
        val state = RegionalComparisonUiState().apply { generation = RegionalRepository.generation(ctx) }
        lateinit var controller: RegionalComparisonController
        instrumentation.runOnMainSync {
            controller = RegionalComparisonController(ctx.applicationContext, state) {}
            controller.save(pair)
            // Keep delivery queued on main while real background I/O saves, then replaces the data.
            java.util.concurrent.CompletableFuture.runAsync {
                check(RegionalRepository.transaction(ctx, state.generation) { true }
                    .get(10, java.util.concurrent.TimeUnit.SECONDS))
                RegionalRepository.clearForRestore(ctx)
            }.get(10, java.util.concurrent.TimeUnit.SECONDS)
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertFalse(state.saving)
            assertEquals(R.string.regional_compare_changed, state.message)
            assertNull(state.latest)
            controller.close()
        }
        assertTrue(RegionalComparisonRepository.snapshot(ctx).pairs.isEmpty())
    }

    private fun <T : android.app.Activity> awaitText(scenario: ActivityScenario<T>, text: String) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        var ready = false
        while (!ready && android.os.SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { activity ->
                ready = views(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>()
                    .any { it.text.contains(text) }
            }
            if (!ready) Thread.sleep(25)
        }
        assertTrue("Prepared comparison must arrive", ready)
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
