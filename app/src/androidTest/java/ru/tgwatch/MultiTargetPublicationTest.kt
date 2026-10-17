package ru.tgwatch

import android.content.Context
import android.content.SharedPreferences
import android.content.ContextWrapper
import android.app.AlarmManager
import android.os.PowerManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.RejectedExecutionException
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Android Handler/network/configuration boundaries; HTTP transports are deterministic and never leave the emulator. */
@RunWith(AndroidJUnit4::class)
class MultiTargetPublicationTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var previous: Map<String, *>
    private lateinit var privatePrevious: Map<String, *>
    private lateinit var host: Host
    private lateinit var monitor: TargetMonitoring
    private val release = CountDownLatch(1)
    private val customStarted = CountDownLatch(1)
    private val events = ConcurrentLinkedQueue<TargetSnapshot>()
    private val privateStore get() = ctx.getSharedPreferences(TargetPreferences.PRIVATE_FILE, Context.MODE_PRIVATE)

    @Before fun isolate() {
        previous = Prefs.sp(ctx).all.toMap()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        assumeNotNull(ctx.getSystemService(ConnectivityManager::class.java).activeNetwork)
        privatePrevious = privateStore.all.toMap()
        val edit = Prefs.sp(ctx).edit().putBoolean("enabled", true)
        previous.keys.filter { it.startsWith("target.") }.forEach(edit::remove)
        edit.putBoolean(TargetPreferences.key(TargetId.MATRIX, "enabled"), true)
            .putBoolean(TargetPreferences.key(TargetId.CUSTOM, "enabled"), true)
        assertTrue(edit.commit())
        assertTrue(privateStore.edit().clear().putString("custom", "https://custom.example").commit())
        host = Host()
        monitor = TargetMonitoring(host) { _ ->
            val probe = NetworkProbe(host.primaryPool, host.secondaryPool, openConnection = { url ->
                Connection(url)
            })
            TargetRunner({ error("The primary owner runs Telegram") }, probe::checkSecondary)
        }
    }

    @After fun restore() {
        release.countDown()
        if (::monitor.isInitialized) monitor.close()
        if (::host.isInitialized) host.close()
        if (::privatePrevious.isInitialized) restore(privateStore, privatePrevious)
        if (::previous.isInitialized) restore(Prefs.sp(ctx), previous)
    }

    @Test fun matrixPublicationAndTelegramBridgeDoNotWaitForCustom() {
        worker { monitor.startDue() }
        assertTrue(customStarted.await(5, TimeUnit.SECONDS))
        await { events.any { it.id == TargetId.MATRIX && it.status == TargetStatus.OK } }
        val telegram = MonitorService.State(MonitorService.Status.TG_DOWN, System.currentTimeMillis(),
            checkedElapsed = SystemClock.elapsedRealtime())
        worker { monitor.onTelegram(telegram, emptyList()) }
        assertEquals(1L, release.count)
        assertEquals(TargetStatus.DOWN, monitor.repository.snapshot(TargetId.TELEGRAM)!!.status)
        assertEquals(TargetStatus.OK, monitor.repository.snapshot(TargetId.MATRIX)!!.status)
        assertTrue(ProbeAdmission.process.snapshot().secondary >= 1)
        release.countDown()
        await { !monitor.hasInFlight }
        assertEquals(TargetStatus.OK, monitor.repository.snapshot(TargetId.CUSTOM)!!.status)
    }

    @Test fun disablingDuringFlightRejectsOldReportAndPreservesMatrix() {
        worker { monitor.startDue() }
        assertTrue(customStarted.await(5, TimeUnit.SECONDS))
        await { events.any { it.id == TargetId.MATRIX } }
        assertTrue(Prefs.sp(ctx).edit().putBoolean(TargetPreferences.key(TargetId.CUSTOM, "enabled"), false).commit())
        worker { monitor.refresh() }
        release.countDown()
        await { !monitor.hasInFlight }
        assertNull(monitor.repository.snapshot(TargetId.CUSTOM))
        assertEquals(TargetStatus.OK, monitor.repository.snapshot(TargetId.MATRIX)!!.status)
        assertFalse(events.any { it.id == TargetId.CUSTOM })
    }

    @Test fun unavailablePrivateEndpointIsParkedWithoutAFreshDownOrUnknown() {
        assertTrue(privateStore.edit().clear().commit())
        worker { monitor.startDue() }
        await { events.any { it.id == TargetId.MATRIX } }
        await { !monitor.hasInFlight }
        assertEquals(1L, customStarted.count)
        assertNull(monitor.repository.snapshot(TargetId.CUSTOM))
        assertFalse(events.any { it.id == TargetId.CUSTOM })
    }

    @Test fun serviceSecondaryWakeDoesNotRunTelegramBeforeItsDeadline() {
        val type = MonitorService::class.java
        fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        val service = MonitorService()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(service, ctx)
        val primaryStarts = AtomicInteger()
        val rejected = object : ThreadPoolExecutor(0, 1, 0L, TimeUnit.MILLISECONDS, SynchronousQueue()) {
            override fun execute(command: Runnable) { primaryStarts.incrementAndGet(); throw RejectedExecutionException() }
        }
        val previousRunning = MonitorService.running
        val previousService = field("activeService").get(null)
        try {
            assertTrue(Prefs.sp(ctx).edit().putBoolean("keep_awake", false).commit())
            field("worker").set(service, host.worker)
            field("workerThread").set(service, host.thread)
            field("pm").set(service, ctx.getSystemService(PowerManager::class.java))
            field("cm").set(service, ctx.getSystemService(ConnectivityManager::class.java))
            field("am").set(service, ctx.getSystemService(AlarmManager::class.java))
            field("probeExecutor").set(service, rejected)
            field("targetMonitoring").set(service, monitor)
            field("telegramDueElapsed").set(service, SystemClock.elapsedRealtime() + 300_000)
            field("running").setBoolean(null, true)
            field("activeService").set(null, service)
            worker { (field("checkRunnable").get(service) as Runnable).run() }
            await { events.any { it.id == TargetId.MATRIX } }
            assertEquals("A secondary timer must not perform an early Telegram check", 0, primaryStarts.get())
        } finally {
            host.worker.removeCallbacks(field("checkRunnable").get(service) as Runnable)
            field("running").setBoolean(null, previousRunning)
            field("activeService").set(null, previousService)
            rejected.shutdownNow()
            WatchdogReceiver.cancel(ctx)
        }
    }

    @Test fun networkChangedBeforeCallbackDiscardsOldMeasuredEndpoints() {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val selected = checkNotNull(cm.activeNetwork)
        var reads = 0
        val access = object : PrimaryNetworkAccess {
            override val activeNetwork: Network? get() = if (reads++ == 0) selected else null
            override fun capabilities(network: Network): NetworkCapabilities? = cm.getNetworkCapabilities(network)
            override fun probe(network: Network) = NetworkProbe(host.primaryPool, host.secondaryPool,
                { url -> Connection(url) }, { throw IOException("fixture") })
        }
        ExtraProbeCoordinator().use { coordinator ->
            val result = PrimaryAndroidProbe(access, coordinator, { emptyList() }).check(0)
            assertEquals(MonitorService.Status.UNKNOWN, result.status)
            assertTrue("Endpoints measured on the old network are not evidence for the current one", result.outcomes.isEmpty())
        }
    }

    private inner class Connection(private val address: URL) : HttpURLConnection(address) {
        private val body = "{\"versions\":[\"v1.13\"]}".toByteArray()
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() = Unit
        override fun getContentLengthLong() = body.size.toLong()
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getResponseCode(): Int {
            if (address.host == "custom.example") {
                customStarted.countDown()
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
            }
            return 200
        }
    }

    private inner class Host : TargetMonitorHost, java.io.Closeable {
        override val context = ctx
        val thread = HandlerThread("test-target-owner").apply { start() }
        override val worker = Handler(thread.looper)
        override val primaryPool = NetworkProbe.executor()
        override val secondaryPool = NetworkProbe.extraExecutor()
        @Volatile var current = true
        override fun isCurrent() = current
        override fun acquireWakeLock() = Unit
        override fun onProgress() = Unit
        override fun onPublished(snapshot: TargetSnapshot, report: TargetReport) { events.add(snapshot) }
        override fun close() {
            current = false
            primaryPool.shutdownNow(); secondaryPool.shutdownNow()
            assertTrue(secondaryPool.awaitTermination(5, TimeUnit.SECONDS))
            thread.quitSafely(); thread.join(5_000)
            assertFalse(thread.isAlive)
        }
    }

    private fun worker(action: () -> Unit) {
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        host.worker.post { try { action() } catch (e: Throwable) { failure.set(e) } finally { done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError(it) }
    }
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
        assertTrue(condition())
    }
    private fun restore(preferences: SharedPreferences, saved: Map<String, *>) {
        val edit = preferences.edit().clear()
        saved.forEach { (key, value) -> when (value) {
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
