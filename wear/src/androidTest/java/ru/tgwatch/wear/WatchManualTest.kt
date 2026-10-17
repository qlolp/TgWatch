package ru.tgwatch.wear

import android.content.Intent
import android.os.SystemClock
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchManualTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Test fun standaloneLaunchIsPassiveAndManualCheckIsBounded() {
        assertEquals("ru.tgwatch.wear", context.packageName)
        val before = WatchExecution.process.requestCount.get()
        val activity = instrumentation.startActivitySync(Intent(context, WatchActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as WatchActivity
        try {
            waitFor { WatchRuntime.ready }
            instrumentation.waitForIdleSync()
            assertEquals(before, WatchExecution.process.requestCount.get())
            val started = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync { activity.findViewById<Button>(R.id.watch_check).performClick() }
            assertEquals(before + 1, WatchExecution.process.requestCount.get())
            waitFor(10_000) { var enabled = false
                instrumentation.runOnMainSync { enabled = activity.findViewById<Button>(R.id.watch_check).isEnabled }
                enabled
            }
            assertTrue(SystemClock.elapsedRealtime() - started < 10_000)
            assertTrue(WatchBudget.admission.snapshot().primary <= 4)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun cacheSurvivesReopenAndCannotReuseElapsedAcrossBoot() {
        val snapshot = WatchSnapshot("PARTIAL", 123_456, 12_345, 89, 41, 7)
        val isolated = context.createDeviceProtectedStorageContext()
        val store = WatchStore(isolated)
        try {
            store.save(snapshot)
            val loaded = WatchStore(isolated).load()!!
            assertEquals(snapshot, loaded)
            assertFalse(loaded.stale(12_400, 7))
            assertTrue(loaded.stale(12_400, 8))
        } finally { isolated.deleteFile("watch-result-v1") }
    }
    private fun waitFor(timeout: Long = 5_000, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) {
            if (SystemClock.elapsedRealtime() >= until) fail("Watch condition did not complete")
            SystemClock.sleep(40)
        }
    }
}
