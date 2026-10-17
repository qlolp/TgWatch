package ru.tgwatch.wear

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.graphics.Rect
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.widget.Button
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
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
import java.util.concurrent.CountDownLatch
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
    @Test fun roundLayoutAndTalkBackAtLargeFont() {
        val scale = context.resources.configuration.fontScale
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .use { it.readBytes() }
        }
        var activity: WatchActivity? = null
        try {
            shell("settings put system font_scale 2.0")
            activity = instrumentation.startActivitySync(Intent(context, WatchActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as WatchActivity
            val screen = activity
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(screen.resources.configuration.isScreenRound)
                assertEquals(2f, screen.resources.configuration.fontScale, 0.01f)
                val status = screen.findViewById<TextView>(R.id.watch_status)
                assertTrue(status.text.isNotEmpty())
                val button = screen.findViewById<Button>(R.id.watch_check)
                assertTrue(button.text.isNotEmpty()) // Native Button text is its TalkBack label.
                button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val button = screen.findViewById<Button>(R.id.watch_check)
                val visible = Rect()
                assertTrue(button.getGlobalVisibleRect(visible))
                assertTrue(visible.height() >= (48 * screen.resources.displayMetrics.density).toInt())
            }
            // A measured/scrolled view can still have its previous buffer on screen.
            val committed = CountDownLatch(1)
            instrumentation.runOnMainSync {
                screen.window.decorView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                screen.window.decorView.invalidate()
            }
            assertTrue("Scrolled frame must be submitted before capture", committed.await(15, TimeUnit.SECONDS))
            instrumentation.uiAutomation.waitForIdle(300, 5_000)
            renderedScreenshot().let { bitmap ->
                try {
                    FileOutputStream(File(context.filesDir, "wear-large-font.png")).use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { bitmap.recycle() }
            }
        } finally {
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            shell("settings put system font_scale $scale")
        }
    }
    private fun renderedScreenshot(): Bitmap {
        // Main-thread idleness proves view measurement, not that SurfaceFlinger latched the frame.
        val until = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < until) {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            if (bitmap != null) {
                val colors = mutableSetOf<Int>()
                for (y in bitmap.height / 5 until bitmap.height * 4 / 5 step 4) {
                    for (x in bitmap.width / 5 until bitmap.width * 4 / 5 step 4) colors.add(bitmap.getPixel(x, y))
                }
                if (colors.size > 12 && 0xff111319.toInt() in colors) return bitmap
                bitmap.recycle()
            }
            SystemClock.sleep(100)
        }
        error("Screenshot must contain rendered app content, not a flat starting window")
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
