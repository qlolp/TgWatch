package ru.tgwatch

import android.content.Intent
import android.net.ConnectivityManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegionalReviewRegressionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private lateinit var backup: TargetBackupSnapshot

    @Before fun isolate() {
        HistoryTestStorage.awaitReady()
        backup = BackupManager.snapshotTargets(ctx)
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        RegionalRepository.clearForRestore(ctx)
        EgressCountryUi.invalidate(ctx)
    }

    @After fun restore() {
        if (!::backup.isInitialized) return
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        BackupManager.restoreTargets(ctx, backup)
    }

    @Test fun aCountryLookupAdmittedBeforeClearCannotRecreateDeletedHistory() {
        val network = ctx.getSystemService(ConnectivityManager::class.java).activeNetwork
        assertNotNull(network)
        assertTrue(RegionalPreferences.setEnabled(ctx, RegionalService.GEMINI, true))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connection = object : HttpURLConnection(URL(EgressCountry.ENDPOINT)) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getInputStream(): java.io.InputStream {
                entered.countDown()
                check(release.await(15, TimeUnit.SECONDS))
                return "loc=DE\n".byteInputStream()
            }
        }
        try {
            EgressCountryUi.refresh(ctx, network!!.networkHandle, { connection }, force = true)
            assertTrue("The old physical lookup must be in flight", entered.await(10, TimeUnit.SECONDS))
            assertTrue(RegionalRepository.clear(ctx).get(10, TimeUnit.SECONDS))
            assertTrue(RegionalRepository.snapshot(ctx).rows.isEmpty())
            release.countDown()
            await { Prefs.egressCountry(ctx)?.iso == "DE" }
            // render() takes the lookup monitor, so its accepted persistence callback has finished.
            instrumentation.runOnMainSync { EgressCountryUi.render(TextView(ctx), ctx) }
            drainJournal()
            assertTrue("A pre-clear country response must not recreate cleared rows",
                RegionalRepository.snapshot(ctx).rows.isEmpty())
            assertTrue(RegionalJournalStorage(RegionalRepository.fileFor(ctx)).read().isEmpty())
        } finally { release.countDown() }
    }

    @Test fun aPublicProbeAdmittedBeforeClearCannotRecreateDeletedHistory() {
        assertTrue(RegionalPreferences.setEnabled(ctx, RegionalService.YOUTUBE, true))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val published = CountDownLatch(1)
        val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1))
        val monitor = RegionalMonitor(ctx, { true }, { published.countDown() },
            { service, network, _, revision, configuration ->
                entered.countDown()
                check(release.await(15, TimeUnit.SECONDS))
                RegionalResult(service, RegionalStatus.WEB_REACHABLE, System.currentTimeMillis(),
                    SystemClock.elapsedRealtime(), network.networkHandle, revision, configuration, httpCode = 200)
            }, execution = RegionalExecution(ProbeAdmission(), worker))
        try {
            monitor.tick()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertTrue(RegionalRepository.clear(ctx).get(10, TimeUnit.SECONDS))
            release.countDown()
            assertTrue("The accepted public callback must actually complete", published.await(10, TimeUnit.SECONDS))
            drainJournal()
            assertTrue("A pre-clear public response must not recreate cleared rows",
                RegionalRepository.snapshot(ctx).rows.isEmpty())
            assertTrue(RegionalJournalStorage(RegionalRepository.fileFor(ctx)).read().isEmpty())
        } finally { release.countDown(); monitor.close(); worker.shutdownNow() }
    }

    @Test fun aDamagedComparisonFileCanBeClearedThroughTheScreenAndThenSavedAgain() {
        RegionalComparisonRepository.fileFor(ctx).writeBytes(byteArrayOf(0, 1, 2))
        // Model a cold process cache while retaining the real storage and UI paths.
        RegionalComparisonRepository::class.java.getDeclaredField("store").apply {
            isAccessible = true
            set(null, null)
        }
        ActivityScenario.launch<RegionalComparisonActivity>(Intent(ctx, RegionalComparisonActivity::class.java))
            .use { scenario ->
                await { RegionalComparisonRepository.snapshot(ctx).error }
                var rendered = false
                await {
                    scenario.onActivity { activity ->
                        rendered = views(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>()
                            .any { it.text.contains(activity.getString(R.string.regional_compare_save_error)) }
                    }
                    rendered
                }
                scenario.onActivity { activity ->
                    val clear = views(activity.findViewById(android.R.id.content)).filterIsInstance<Button>()
                        .single { it.text == activity.getString(R.string.regional_compare_clear) }
                    assertTrue("Damaged storage must have an accessible clear action", clear.isEnabled)
                    clear.performClick()
                }
                instrumentation.waitForIdleSync()
                await { instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByViewId("android:id/button1")?.isNotEmpty() == true }
                val confirm = instrumentation.uiAutomation.rootInActiveWindow
                    .findAccessibilityNodeInfosByViewId("android:id/button1").single()
                assertTrue(confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await { RegionalComparisonRepository.snapshot(ctx).let { !it.loading && !it.error } }
                assertTrue(RegionalComparisonStorage(RegionalComparisonRepository.fileFor(ctx)).read().isEmpty())
                val now = System.currentTimeMillis()
                val connection = RegionalConnection("0123456789abcdef0123456789abcdef", RegionalRoute.WIFI)
                val row = RegionalJournalEntry(RegionalEventKind.PUBLIC, now - 2_000, connection.token,
                    connection.route, service = RegionalService.GEMINI,
                    status = RegionalStatus.WEB_REACHABLE, httpCode = 200)
                val side = RegionalComparisonSnapshot("A", now - 1_500, connection,
                    mapOf(RegionalService.GEMINI to row))
                val pair = RegionalComparisonPair(side, side.copy(label = "B", at = now - 1_000))
                assertTrue(RegionalComparisonRepository.save(ctx, pair, RegionalRepository.generation(ctx))
                    .get(10, TimeUnit.SECONDS))
                assertEquals(listOf(pair), RegionalComparisonStorage(RegionalComparisonRepository.fileFor(ctx)).read())
            }
    }

    private fun drainJournal() {
        assertTrue(RegionalRepository.transaction(ctx, RegionalRepository.generation(ctx)) { true }
            .get(10, TimeUnit.SECONDS))
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
        assertTrue("The real asynchronous operation must finish", condition())
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
