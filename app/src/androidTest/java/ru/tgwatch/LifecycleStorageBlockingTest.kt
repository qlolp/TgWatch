package ru.tgwatch

import android.app.NotificationManager
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LifecycleStorageBlockingTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var preferences: Map<String, *>
    private lateinit var history: List<Observation>

    @Before fun isolate() {
        preferences = Prefs.sp(ctx).all.toMap()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        history = History.snapshot(ctx)
    }

    @After fun restore() {
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        History.replaceSnapshot(ctx, history)
        val edit = Prefs.sp(ctx).edit().clear()
        preferences.forEach { (key, value) -> put(edit, key, value) }
        assertTrue(edit.commit())
        MonitorService.clearRestoredState()
    }

    @Test fun blockedHistoryDoesNotBlockDestroy() {
        Prefs.sp(ctx).edit().putBoolean("enabled", true).putBoolean("keep_awake", false).commit()
        MonitorService.start(ctx)
        val deadline = SystemClock.elapsedRealtime() + 10_000
        val notifications = ctx.getSystemService(NotificationManager::class.java)
        while (notifications.activeNotifications.none { it.id == 1 } &&
            SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(MonitorService.running)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread(name = "lifecycle-held-history") {
            synchronized(History) { held.countDown(); release.await(10, TimeUnit.SECONDS) }
        }
        try {
            assertTrue(held.await(2, TimeUnit.SECONDS))
            val barrier = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                MonitorService.stop(ctx)
                Handler(Looper.getMainLooper()).post { barrier.countDown() }
            }
            assertTrue("Disk work must not block a stop/main-loop barrier", barrier.await(1, TimeUnit.SECONDS))
            assertEquals("The history lock must remain held for this assertion", 1L, release.count)
        } finally {
            release.countDown()
            holder.join(2_000)
        }
    }

    @Test fun historyFailureKeepsNotificationWorkerAlive() {
        Prefs.sp(ctx).edit().putBoolean("enabled", true).putBoolean("keep_awake", false).commit()
        MonitorService.start(ctx)
        val active = MonitorService::class.java.getDeclaredField("activeService").apply { isAccessible = true }
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (active.get(null) == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        val service = active.get(null) as MonitorService
        val delegate = MonitorService::class.java.getDeclaredField("ongoing\$delegate").apply { isAccessible = true }
        val controller = (delegate.get(service) as Lazy<*>).value!!
        val method = controller.javaClass.getDeclaredMethod("refreshStats", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
        synchronized(History) {
            val failure = History::class.java.getDeclaredField("failure").apply { isAccessible = true }
            val previous = failure.get(null)
            try {
                failure.set(null, HistoryFailure.WRITE)
                val read = runCatching { method.invoke(controller, System.currentTimeMillis() + 120_000) }
                assertTrue("Statistics failures must not escape and kill the service HandlerThread: $read", read.isSuccess)
            } finally { failure.set(null, previous) }
        }
    }

    @Test fun foregroundStartsBeforeRecoveryFinishes() {
        assertEquals(RecoveryState.READY, RecoveryCoordinator.state)
        Prefs.sp(ctx).edit().putBoolean("enabled", true).putBoolean("keep_awake", false).commit()
        val gate = RecoveryCoordinator::class.java.getDeclaredField("gate").apply { isAccessible = true }.get(null)
        val state = gate.javaClass.getDeclaredField("state").apply { isAccessible = true }
        state.set(gate, RecoveryState.INITIALIZING)
        val before = History.version
        try {
            MonitorService.start(ctx)
            val notifications = ctx.getSystemService(NotificationManager::class.java)
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (notifications.activeNotifications.none { it.id == 1 } &&
                SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertTrue("The foreground deadline must not wait for data replay",
                notifications.activeNotifications.any { it.id == 1 })
            val heartbeat = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { heartbeat.countDown() }
            assertTrue(heartbeat.await(1, TimeUnit.SECONDS))
            assertEquals(before, History.version)
            assertTrue(MonitorService.endpointOutcomes().isEmpty())
            MonitorService.send(ctx, MonitorService.ACTION_STOP)
            val stoppedBy = SystemClock.elapsedRealtime() + 2_000
            while (MonitorService.running && SystemClock.elapsedRealtime() < stoppedBy) SystemClock.sleep(25)
            assertFalse("Stop must work while recovery is still initializing", MonitorService.running)
            assertFalse(Prefs.isEnabled(ctx))
        } finally {
            Prefs.setEnabled(ctx, false)
            state.set(gate, RecoveryState.FAILED)
            RecoveryCoordinator.start(ctx)
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (RecoveryCoordinator.state != RecoveryState.READY &&
                SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertEquals(RecoveryState.READY, RecoveryCoordinator.state)
        }
    }

    private fun put(edit: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is String -> edit.putString(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }
}
