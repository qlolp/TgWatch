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
        RegionalRepository.recordEntry(ctx, row)
        RegionalRepository.awaitIdle(ctx)
        assertEquals(listOf(row), RegionalRepository.snapshot(ctx).rows)
        val disk = RegionalJournalStorage(RegionalRepository.fileFor(ctx)).read()
        assertEquals(listOf(row), disk)
        assertFalse(RegionalRepository.fileFor(ctx).readText().contains("https://"))
        BackupManager.restoreTargets(ctx, backup)
        RegionalRepository.awaitIdle(ctx)
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
        RegionalRepository.recordEntry(ctx, RegionalJournalEntry(RegionalEventKind.PUBLIC, now,
            scope, RegionalRoute.WIFI, service = RegionalService.YOUTUBE,
            status = RegionalStatus.AUTH_REQUIRED, httpCode = 401))
        RegionalRepository.awaitIdle(ctx)
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

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
