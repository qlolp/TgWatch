package ru.tgwatch

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PendingShareIntegrationTest {
    @Test fun queuedCaptureSurvivesRecreationAndKeepsItsRequestedLocale() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        HistoryTestStorage.awaitReady()
        val field = StatusShareJobs::class.java.getDeclaredField("worker").apply { isAccessible = true }
        val worker = field.get(StatusShareJobs) as ThreadPoolExecutor
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val supportsLocale = Build.VERSION.SDK_INT >= 33
        val previous = if (supportsLocale) AppLocales.get(ctx) else ""
        try {
            if (supportsLocale) instrumentation.runOnMainSync { AppLocales.set(ctx, "ru") }
            worker.execute { entered.countDown(); release.await() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            ActivityScenario.launch<StatusShareActivity>(Intent(ctx, StatusShareActivity::class.java)).use { scenario ->
                var capturedRussian = false
                scenario.onActivity { capturedRussian = it.getString(R.string.lang) == "ru" }
                scenario.recreate()
                if (supportsLocale) {
                    instrumentation.runOnMainSync { AppLocales.set(ctx, "en") }
                    instrumentation.waitForIdleSync()
                }
                release.countDown()
                val deadline = SystemClock.elapsedRealtime() + 10_000
                var ready = false
                while (!ready && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { ready = it.findViewById<Button>(R.id.shareStatusText).isEnabled }
                    if (!ready) SystemClock.sleep(50)
                }
                assertTrue("Recreation must not strand a discarded queued capture behind the busy message", ready)
                scenario.onActivity {
                    val text = it.findViewById<TextView>(R.id.shareStatusPreview).text.toString()
                    assertTrue(text.contains(if (capturedRussian) "Последний результат:" else "Last result:"))
                }
            }
        } finally {
            release.countDown()
            if (supportsLocale) instrumentation.runOnMainSync { AppLocales.set(ctx, previous) }
        }
    }
}
