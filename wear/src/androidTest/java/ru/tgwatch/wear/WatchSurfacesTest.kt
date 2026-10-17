package ru.tgwatch.wear

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.tiles.RequestBuilders
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchSurfacesTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Test fun tileAndComplicationNeverProbeOnRequestIncludingAmbient() {
        waitFor { WatchRuntime.ready }
        val calls = WatchBudget.transportStarts.get()
        val requests = WatchExecution.process.requestCount.get()
        val tile = attach(StatusTileService())
        val complication = attach(StatusComplicationService())
        instrumentation.runOnMainSync {
            repeat(3) {
                val frame = tile.onTileRequest(RequestBuilders.TileRequest.Builder().build()).get(1, TimeUnit.SECONDS)
                assertTrue(frame.tileTimeline!!.timelineEntries.isNotEmpty())
                assertEquals("1", tile.onTileResourcesRequest(RequestBuilders.ResourcesRequest.Builder().build())
                    .get(1, TimeUnit.SECONDS).version)
                for (type in listOf(ComplicationType.SHORT_TEXT, ComplicationType.LONG_TEXT)) {
                    var result: ComplicationData? = null
                    complication.onComplicationRequest(ComplicationRequest(1, type, true),
                        object : ComplicationDataSourceService.ComplicationRequestListener {
                            override fun onComplicationData(complicationData: ComplicationData?) { result = complicationData }
                        })
                    assertNotNull(result)
                    assertNotNull(complication.getPreviewData(type))
                }
            }
            val short = complication.getPreviewData(ComplicationType.SHORT_TEXT) as ShortTextComplicationData
            assertTrue(short.contentDescription!!.getTextAt(context.resources, Instant.now()).isNotEmpty())
        }
        assertEquals(calls, WatchBudget.transportStarts.get())
        assertEquals(requests, WatchExecution.process.requestCount.get())
    }
    @Test fun tapOpensOneManualCheckButForgedLaunchIsPassive() {
        waitFor { WatchRuntime.ready }
        assertNotNull(WatchSurfaceAction.ticket)
        val before = WatchExecution.process.requestCount.get()
        val activity = instrumentation.startActivitySync(Intent(context, WatchActivity::class.java)
            .putExtra(WatchSurfaceAction.EXTRA, "forged").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as WatchActivity
        try {
            instrumentation.waitForIdleSync()
            assertEquals(before, WatchExecution.process.requestCount.get())
            assertTrue(activity.findViewById<Button>(R.id.watch_check).minHeight >= (48 * context.resources.displayMetrics.density).toInt())
            val pending = WatchSurfaceAction.pendingIntent(context)
            // Fill-in extras cannot replace the valid capability in an immutable PendingIntent, including API30.
            pending.send(context, 0, Intent().putExtra(WatchSurfaceAction.EXTRA, "forged"))
            waitFor { WatchExecution.process.requestCount.get() == before + 1 }
            instrumentation.waitForIdleSync()
            assertEquals(before + 1, WatchExecution.process.requestCount.get())
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    private fun <T : ContextWrapper> attach(service: T): T {
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(service, context)
        return service
    }
    private fun waitFor(timeout: Long = 10_000, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) {
            if (SystemClock.elapsedRealtime() >= until) fail("Watch surface did not complete")
            SystemClock.sleep(40)
        }
    }
}
