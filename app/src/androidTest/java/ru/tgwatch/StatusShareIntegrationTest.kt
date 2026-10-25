package ru.tgwatch

import android.app.Activity
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StatusShareIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private lateinit var saved: Map<String, *>
    private lateinit var privateSaved: Map<String, *>
    private lateinit var history: TargetBackupSnapshot
    private val privatePrefs get() = ctx.getSharedPreferences(TargetPreferences.PRIVATE_FILE, Context.MODE_PRIVATE)
    private val secrets = listOf("https://private.example/never-share", "192.0.2.10", "secret-ssid", "Bearer never-share")
    @Before fun isolate() {
        HistoryTestStorage.awaitReady()
        saved = Prefs.sp(ctx).all.toMap()
        privateSaved = privatePrefs.all.toMap()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        history = BackupManager.snapshotTargets(ctx)
        BackupManager.restoreTargets(ctx, history.copy(observations =
            history.targets.associate { it.id to emptyList<TargetObservation>() }))
        check(privatePrefs.edit().putString("custom", secrets.first()).commit())
        check(Prefs.sp(ctx).edit().putString("last_reason", secrets.joinToString(" "))
            .putString("last_diagnostics", secrets.joinToString(" "))
            .putString("custom_label", "Bearer never-share").commit())
    }
    @After fun restore() {
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        if (::history.isInitialized) BackupManager.restoreTargets(ctx, history)
        if (::privateSaved.isInitialized) put(privatePrefs, privateSaved)
        if (::saved.isInitialized) put(Prefs.sp(ctx), saved)
        if (!Prefs.isEnabled(ctx)) WatchdogReceiver.cancel(ctx)
    }
    @Test fun actualPreviewAndSharesUseTheSameRedactedSnapshotAcrossRotation() {
        TargetId.entries.forEach { id ->
            val captured = StatusShareSnapshot.capture(ctx, id)
            val text = StatusShareDocument.prepare(ctx, captured).text
            secrets.forEach { secret -> assertFalse(text.contains(secret)) }
            assertTrue(captured.chart.isEmpty())
        }
        val outgoing = mutableListOf<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                @Suppress("DEPRECATION")
                val send = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (send != null) outgoing += send
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            val intent = Intent(ctx, StatusShareActivity::class.java).putExtra(TargetAutomation.EXTRA_TARGET_ID, "custom")
            ActivityScenario.launch<StatusShareActivity>(intent).use { scenario ->
                awaitPreview(scenario)
                var preview = ""
                scenario.onActivity { preview = it.findViewById<TextView>(R.id.shareStatusPreview).text.toString() }
                check(Prefs.sp(ctx).edit().putString(TargetPreferences.key(TargetId.CUSTOM, "last_status"), "DOWN").commit())
                scenario.recreate()
                scenario.onActivity {
                    assertEquals(preview, it.findViewById<TextView>(R.id.shareStatusPreview).text.toString())
                    it.findViewById<Button>(R.id.shareStatusText).performClick()
                }
                assertEquals(preview, outgoing.last().getStringExtra(Intent.EXTRA_TEXT))
                scenario.onActivity {
                    assertTrue("PNG must have a real rendered preview", it.findViewById<Button>(R.id.shareStatusPng).isEnabled)
                    it.findViewById<Button>(R.id.shareStatusPng).performClick()
                }
                val png = outgoing.last()
                assertEquals("image/png", png.type)
                assertTrue(png.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertEquals(0, png.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                @Suppress("DEPRECATION")
                val uri = png.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
                assertEquals(ctx.packageName + ".status-share", uri.authority)
                ctx.contentResolver.openInputStream(uri).use { input ->
                    val bitmap = BitmapFactory.decodeStream(input)!!
                    assertTrue(bitmap.width <= 1024 && bitmap.height <= 1024)
                    assertTrue(bitmap.allocationByteCount <= 4 * 1024 * 1024)
                    bitmap.recycle()
                }
                secrets.forEach { assertFalse(preview.contains(it)) }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }
    @Test fun otherUidNeedsGrantAndCleanupRevokesItAndRemovesExpiredPng() {
        val snapshot = StatusShareSnapshot.capture(ctx, TargetId.TELEGRAM)
        val file = StatusShareCache.write(ctx, StatusShareDocument.prepare(ctx, snapshot))
        val uri = StatusShareCache.uri(ctx, file)
        assertFalse(helperRead(uri, false))
        assertTrue(helperRead(uri, true))
        val testPackage = instrumentation.context.packageName
        ctx.grantUriPermission(testPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(helperRead(uri, false))
        assertTrue(file.setLastModified(System.currentTimeMillis() - 86_400_001L))
        StatusShareCache.cleanup(ctx)
        assertFalse(file.exists())
        assertFalse(helperRead(uri, false))
        val forged = uri.buildUpon().encodedPath("/status-card/..%2F..%2Fshared_prefs%2Ftgwatch.xml").build()
        assertThrows(SecurityException::class.java) { ctx.contentResolver.openFileDescriptor(forged, "r")?.close() }
        assertFalse(helperRead(forged, false))
    }
    private fun helperRead(uri: Uri, grant: Boolean): Boolean {
        val action = "ru.tgwatch.test.SHARE_REPLY." + UUID.randomUUID()
        val nonce = UUID.randomUUID().toString()
        val done = CountDownLatch(1)
        var readable = false
        var uid = -1
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra("nonce") != nonce) return
                uid = intent.getIntExtra("uid", -1)
                readable = intent.getBooleanExtra("readable", false)
                done.countDown()
            }
        }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_EXPORTED)
        else ctx.registerReceiver(receiver, IntentFilter(action))
        try {
            val testPackage = instrumentation.context.packageName
            val request = Intent().setComponent(ComponentName(testPackage, "ru.tgwatch.ShareGrantClientActivity"))
                // A reply can arrive before the previous helper task finishes.
                // Reusing its identical root intent can swallow the next request.
                .setData(uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra("reply_action", action)
                .putExtra("reply_package", ctx.packageName).putExtra("nonce", nonce)
            if (grant) {
                request.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                request.clipData = ClipData.newRawUri("status", uri)
            }
            ctx.startActivity(request)
            assertTrue("Other UID must actually execute its read", done.await(20, TimeUnit.SECONDS))
            assertEquals(ctx.packageManager.getApplicationInfo(testPackage, 0).uid, uid)
            assertNotEquals(ctx.applicationInfo.uid, uid)
            return readable
        } finally { ctx.unregisterReceiver(receiver) }
    }
    private fun awaitPreview(scenario: ActivityScenario<StatusShareActivity>) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { ready = it.findViewById<Button>(R.id.shareStatusText).isEnabled }
            SystemClock.sleep(100)
        }
        assertTrue("A frozen text preview must be available without history", ready)
    }
    private fun put(preferences: android.content.SharedPreferences, values: Map<String, *>) {
        val edit = preferences.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Long -> edit.putLong(key, value)
            is Int -> edit.putInt(key, value)
            is Boolean -> edit.putBoolean(key, value)
        } }
        check(edit.commit())
    }
}
