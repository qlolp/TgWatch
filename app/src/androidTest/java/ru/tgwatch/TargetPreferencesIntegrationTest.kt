package ru.tgwatch

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TargetPreferencesIntegrationTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = TargetId.CUSTOM
    private lateinit var saved: Map<String, *>
    private lateinit var privateSaved: Map<String, *>
    private lateinit var rows: List<TargetObservation>
    private val privateStore get() = ctx.getSharedPreferences(TargetPreferences.PRIVATE_FILE, Context.MODE_PRIVATE)

    @Before fun isolate() {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (RecoveryCoordinator.state != RecoveryState.READY && SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(25)
        assertEquals(RecoveryState.READY, RecoveryCoordinator.state)
        saved = Prefs.sp(ctx).all.toMap()
        privateSaved = privateStore.all.toMap()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        rows = TargetPersistence.storage().read(id)
        assertTrue(Prefs.sp(ctx).edit().putBoolean("enabled", true)
            .putBoolean(TargetPreferences.key(id, "enabled"), true)
            .putBoolean(TargetPreferences.key(id, "endpoint_review"), false)
            .putLong(TargetPreferences.key(id, "endpoint_revision"), 0).commit())
        assertTrue(privateStore.edit().putString(id.wire, "https://old.example").commit())
        val now = System.currentTimeMillis()
        TargetPersistence.storage().replaceRows(id,
            listOf(TargetObservation(Observation(now - 2_000, now - 1_000, "OK"))))
    }

    @After fun restore() {
        if (::rows.isInitialized) {
            TargetConfigTransactions.retry(ctx, id)
            HistorySessions.queue(ctx).exclusive { TargetPersistence.storage().replaceRows(id, rows) }.get()
        }
        if (::privateSaved.isInitialized) restore(privateStore, privateSaved)
        if (::saved.isInitialized) restore(Prefs.sp(ctx), saved)
        if (!Prefs.isEnabled(ctx)) WatchdogReceiver.cancel(ctx)
    }

    @Test fun blockedPrivateCommitParksOnlyCustomAndDoesNotHoldThePublicationLock() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<Boolean>()
        val wrapped = intercept { editor ->
            editor.commit().also { entered.countDown(); assertTrue(release.await(15, TimeUnit.SECONDS)) }
        }
        val thread = Thread { result.set(TargetPreferences.setEndpoint(wrapped, id, "https://new.example")) }
        thread.start()
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertFalse(TargetPreferences.definition(ctx, id).enabled)
            assertNull(TargetPreferences.endpoint(ctx, id))
            val handlerThread = HandlerThread("test-config-publication").apply { start() }
            val primary = NetworkProbe.executor()
            val secondary = NetworkProbe.extraExecutor()
            try {
                val published = CountDownLatch(1)
                val monitor = TargetMonitoring(object : TargetMonitorHost {
                    override val context = ctx
                    override val worker = Handler(handlerThread.looper)
                    override val primaryPool = primary
                    override val secondaryPool = secondary
                    override fun isCurrent() = true
                    override fun acquireWakeLock() = Unit
                    override fun onProgress() = Unit
                    override fun onPublished(snapshot: TargetSnapshot, report: TargetReport) { published.countDown() }
                })
                Handler(handlerThread.looper).post {
                    monitor.onTelegram(MonitorService.State(MonitorService.Status.OK, System.currentTimeMillis()), emptyList())
                }
                assertTrue("Custom CP commit must not block Telegram publication", published.await(2, TimeUnit.SECONDS))
                monitor.close()
            } finally { primary.shutdownNow(); secondary.shutdownNow(); handlerThread.quitSafely(); handlerThread.join(3_000) }
        } finally { release.countDown(); thread.join(15_000) }
        assertFalse(thread.isAlive)
        assertEquals(true, result.get())
        assertEquals("https://new.example", TargetPreferences.endpoint(ctx, id))
        assertEquals(1, TargetPreferences.endpointRevision(ctx, id))
        val restored = TargetPersistence.storage().read(id)
        assertTrue(restored.any { it.endpointRevision == 0L && it.row.kind == "OK" })
        assertTrue(restored.any { it.endpointRevision == 1L && it.row.isEpochMetadata })
    }

    @Test fun failedCommitKeepsReplayableOwnPendingAndDoesNotChangeOtherGoal() {
        val before = TargetPreferences.definition(ctx, TargetId.TELEGRAM)
        val generation = TargetPreferences.fence.snapshot(id).revision
        val failOnce = AtomicBoolean(true)
        val wrapped = intercept { editor -> editor.commit() && !failOnce.getAndSet(false) }
        assertFalse(TargetPreferences.setEndpoint(wrapped, id, "https://new.example"))
        assertTrue(TargetConfigTransactions.isPending(id))
        assertFalse(TargetPreferences.definition(ctx, id).enabled)
        assertNull(TargetPreferences.endpoint(ctx, id))
        assertEquals(before, TargetPreferences.definition(ctx, TargetId.TELEGRAM))
        assertTrue(TargetPreferences.fence.snapshot(id).revision > generation)
        assertTrue(TargetConfigTransactions.retry(ctx, id))
        assertEquals("https://new.example", TargetPreferences.endpoint(ctx, id))
        assertFalse(TargetConfigTransactions.isPending(id))
    }

    @Test fun stopDuringPrivateCommitCannotBeUndoneByItsCompletion() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val wrapped = intercept { editor -> editor.commit().also { entered.countDown(); release.await(15, TimeUnit.SECONDS) } }
        val thread = Thread { TargetPreferences.setEndpoint(wrapped, id, "https://new.example") }
        thread.start()
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            TargetPreferences.stop(ctx, id)
        } finally { release.countDown(); thread.join(15_000) }
        assertFalse(thread.isAlive)
        assertFalse(TargetPreferences.definition(ctx, id).enabled)
        assertFalse(TargetPreferences.fence.snapshot(id).changing)
    }

    private fun intercept(commit: (SharedPreferences.Editor) -> Boolean): Context = object : ContextWrapper(ctx) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val store = super.getSharedPreferences(name, mode)
            if (name != TargetPreferences.PRIVATE_FILE) return store
            return object : SharedPreferences by store {
                override fun edit(): SharedPreferences.Editor {
                    val real = store.edit()
                    return object : SharedPreferences.Editor by real {
                        override fun putString(key: String, value: String?): SharedPreferences.Editor { real.putString(key, value); return this }
                        override fun remove(key: String): SharedPreferences.Editor { real.remove(key); return this }
                        override fun clear(): SharedPreferences.Editor { real.clear(); return this }
                        override fun commit() = commit(real)
                    }
                }
            }
        }
    }
    private fun restore(store: SharedPreferences, data: Map<String, *>) {
        val edit = store.edit().clear()
        data.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        assertTrue(edit.commit())
    }
}
