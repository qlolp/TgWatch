package ru.tgwatch

import android.app.Notification
import android.app.NotificationManager
import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real publication/lifecycle boundaries; primary transports stay queued and never touch the network. */
@RunWith(AndroidJUnit4::class)
class MonitorReliabilityIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private val manager get() = ctx.getSystemService(NotificationManager::class.java)
    private val type = MonitorService::class.java
    private fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
    private lateinit var originalPreferences: Map<String, *>
    private lateinit var originalHistory: List<Observation>
    private lateinit var originalLog: List<String>
    private var fixtureService: MonitorService? = null

    @Before fun prepare() {
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        originalPreferences = Prefs.sp(ctx).all.toMap()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        originalHistory = History.snapshot(ctx)
        originalLog = EventLog.all(ctx).toList()
        // The webhook singleton must capture the real credential-protected application, not a DP fault fixture.
        WebhookNotifier.health(ctx)
        manager.cancel(1)
        EventNotifications(ctx, manager).clearOutage()
        manager.cancel(EventNotifications.RECOVERY_ID)
        manager.cancel(EventNotifications.SUMMARY_ID)
    }

    @After fun restore() {
        fixtureService?.let { service ->
            HistorySessions.queue(ctx).close(field("historySession").getLong(service), System.currentTimeMillis()).get()
            (field("extraCoordinator").get(service) as ExtraProbeCoordinator).close()
            (field("probeExecutor").get(service) as ExecutorService).shutdownNow()
            (field("extraProbeExecutor").get(service) as ExecutorService).shutdownNow()
            field("activeService").set(null, null)
            field("running").setBoolean(null, false)
        }
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        History.replaceSnapshot(ctx, originalHistory)
        EventLog.replace(ctx, originalLog)
        val edit = Prefs.sp(ctx).edit().clear()
        originalPreferences.forEach { (key, value) -> when (value) {
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is String -> edit.putString(key, value)
            is Set<*> -> edit.putStringSet(key, value.map { it as String }.toSet())
        } }
        assertTrue(edit.commit())
        manager.cancel(1)
        EventNotifications(ctx, manager).clearOutage()
        manager.cancel(EventNotifications.RECOVERY_ID)
        manager.cancel(EventNotifications.SUMMARY_ID)
        MonitorService.clearRestoredState()
        WatchdogReceiver.schedule(ctx)
    }

    @Test fun foregroundAndMainHeartbeatDoNotWaitForHistory() {
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread(name = "held-history") {
            synchronized(History) {
                locked.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        try {
            assertTrue(locked.await(2, TimeUnit.SECONDS))
            assertTrue(Prefs.sp(ctx).edit().putBoolean("enabled", true).commit())
            MonitorService.start(ctx)
            val notification = awaitNotification(1)
            assertTrue(notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
            assertEquals("History must still be blocked when foreground publication finishes", 1L, release.count)
            val heartbeat = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { heartbeat.countDown() }
            assertTrue("Service startup must leave the Android main loop responsive",
                heartbeat.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            holder.join(2000)
            assertFalse(holder.isAlive)
        }
    }

    @Test fun interruptedPrimaryRetainsStateHistoryAndOutageAndRetriesInTenSeconds() {
        val service = service()
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork
        assumeNotNull("Requires the emulator's active Android Network", network)
        val caps = cm.getNetworkCapabilities(network!!)
        assumeNotNull(caps)
        assumeFalse(caps!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL))
        val previous = seedOutage()
        val history = History.snapshot(ctx)
        val preferences = Prefs.sp(ctx).all.toMap()
        val pending = AtomicLong(-1)
        field("checkSchedule").set(service, CheckSchedule({}, pending::set))
        // A prior real service may still own physical endpoint permits after cancellation.
        // Interrupt after the first admitted probe is queued behind our occupied worker.
        val admitted = CountDownLatch(2)
        val occupied = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = object : ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()) {
            override fun execute(command: Runnable) { super.execute(command); admitted.countDown() }
        }
        pool.execute { occupied.countDown(); try { release.await() } catch (_: InterruptedException) {} }
        assertTrue(occupied.await(2, TimeUnit.SECONDS))
        (field("probeExecutor").get(service) as ExecutorService).shutdownNow()
        field("probeExecutor").set(service, pool)
        val failure = AtomicReference<Throwable?>()
        val coordinator = thread(name = "cancelled-check") {
            try { type.getDeclaredMethod("runCheck").apply { isAccessible = true }.invoke(service) }
            catch (e: InvocationTargetException) { failure.set(e.cause) }
        }
        try {
            assertTrue("The probe batch must admit work before it is interrupted", admitted.await(2, TimeUnit.SECONDS))
            coordinator.interrupt()
            coordinator.join(2000)
            assertFalse(coordinator.isAlive)
            assertNull(failure.get())
            assertEquals(previous, MonitorService.state)
            assertEquals(history, History.snapshot(ctx))
            assertEquals(preferences, Prefs.sp(ctx).all)
            assertEquals(10_000L, pending.get())
            assertNotNull(awaitNotification(EventNotifications.OUTAGE_ID))
        } finally {
            release.countDown()
            coordinator.interrupt()
            coordinator.join(2000)
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun diagnosticCommitFailureStillPublishesOkAndRecovery() {
        val service = service(failingLastSuccessContext())
        seedOutage()
        publish(service, MonitorService.Status.OK, 0L)
        val result = MonitorService.state
        assertEquals(MonitorService.Status.OK, result.status)
        assertEquals("measured result", result.reason)
        assertEquals("OK", History.snapshot(ctx).single { it.at == result.checkedAt }.kind)
        assertEquals("OK", Prefs.sp(ctx).getString("last_status", null))
        assertNotNull(awaitNotification(EventNotifications.RECOVERY_ID))
    }

    @Test fun interruptedAdapterFailureDoesNotPublishAnUnknownObservation() {
        val service = service()
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork
        assumeNotNull("Requires the emulator's active Android Network", network)
        val caps = cm.getNetworkCapabilities(network!!)
        assumeNotNull(caps)
        assumeFalse(caps!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL))
        val previous = seedOutage()
        val history = History.snapshot(ctx)
        val pending = AtomicLong(-1)
        field("checkSchedule").set(service, CheckSchedule({}, pending::set))
        val pool = object : ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()) {
            override fun execute(command: Runnable) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("adapter cancelled while failing")
            }
        }
        (field("probeExecutor").get(service) as ExecutorService).shutdownNow()
        field("probeExecutor").set(service, pool)
        val failure = AtomicReference<Throwable?>()
        val coordinator = thread(name = "cancelled-adapter") {
            try { type.getDeclaredMethod("runCheck").apply { isAccessible = true }.invoke(service) }
            catch (e: InvocationTargetException) { failure.set(e.cause) }
        }
        coordinator.join(2000)
        assertFalse(coordinator.isAlive)
        assertNull(failure.get())
        assertEquals(previous, MonitorService.state)
        assertEquals(history, History.snapshot(ctx))
        assertEquals(10_000L, pending.get())
        assertNotNull(awaitNotification(EventNotifications.OUTAGE_ID))
    }

    @Test fun cancelledGenerationDoesNotAppend() {
        val service = service()
        val previous = seedOutage()
        val history = History.snapshot(ctx)
        val schedule = CheckSchedule({}, {})
        field("checkSchedule").set(service, schedule)
        val revision = schedule.begin()
        schedule.networkChanged(800L)
        publish(service, MonitorService.Status.OK, revision)
        assertEquals(previous, MonitorService.state)
        assertEquals(history, History.snapshot(ctx))
        assertNotNull(awaitNotification(EventNotifications.OUTAGE_ID))
    }

    @Test fun unmeasuredBatchDoesNotAppend() {
        val service = service()
        val previous = seedOutage()
        val history = History.snapshot(ctx)
        val preferences = Prefs.sp(ctx).all.toMap()
        val pending = AtomicLong(-1L)
        val schedule = CheckSchedule({}, pending::set)
        field("checkSchedule").set(service, schedule)
        val probeType = Class.forName("ru.tgwatch.MonitorService\$Probe")
        val outcomes = listOf(ProbeOutcome("busy", ProbeGroup.MTPROTO, false, -1,
            "not measured", ProbeMeasurement.NOT_MEASURED))
        val probe = probeType.declaredConstructors.first { it.parameterCount == 6 }.apply { isAccessible = true }
            .newInstance(MonitorService.Status.UNKNOWN, -1L, "not measured", "busy probes", outcomes, null)
        type.getDeclaredMethod("publishCheckedResult", probeType, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(service, probe, schedule.begin())
        assertEquals(previous, MonitorService.state)
        assertEquals(history, History.snapshot(ctx))
        assertEquals(preferences, Prefs.sp(ctx).all)
        assertEquals(1_000L, pending.get())
        assertNotNull(awaitNotification(EventNotifications.OUTAGE_ID))
    }

    @Test fun emptyUnknownRetainsStateAndHistory() {
        val service = service()
        val previous = seedOutage()
        val history = History.snapshot(ctx)
        val preferences = Prefs.sp(ctx).all.toMap()
        val pending = AtomicLong(-1L)
        val schedule = CheckSchedule({}, pending::set)
        field("checkSchedule").set(service, schedule)
        val probeType = Class.forName("ru.tgwatch.MonitorService\$Probe")
        val outcomes = emptyList<ProbeOutcome>()
        val probe = probeType.declaredConstructors.first { it.parameterCount == 6 }.apply { isAccessible = true }
            .newInstance(MonitorService.Status.UNKNOWN, -1L, "not measured", "busy probes", outcomes, null)
        type.getDeclaredMethod("publishCheckedResult", probeType, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(service, probe, schedule.begin())
        assertEquals(previous, MonitorService.state)
        assertEquals(history, History.snapshot(ctx))
        assertEquals(preferences, Prefs.sp(ctx).all)
        assertEquals(1_000L, pending.get())
        assertNotNull(awaitNotification(EventNotifications.OUTAGE_ID))
    }

    @Test fun quittingWorkerRejectsBeforeChargingManualAdmission() {
        val service = service()
        val workerThread = HandlerThread("quitting-check-fixture").apply { start() }
        val handler = Handler(workerThread.looper)
        field("workerThread").set(service, workerThread)
        field("worker").set(service, handler)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val charged = AtomicInteger()
        assertTrue(handler.post { started.countDown(); release.await() })
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue(workerThread.quitSafely())
            assertTrue("A quitting looper can still have a live thread", workerThread.isAlive)
            assertFalse(MonitorService.requestRunningCheck(ctx) { charged.incrementAndGet(); true })
            assertEquals("Rejected Handler.post must not consume the automation budget", 0, charged.get())
        } finally {
            release.countDown()
            workerThread.quitSafely()
            workerThread.join(2000)
            assertFalse(workerThread.isAlive)
        }
    }

    @Test fun stoppingMonitoringWhileSummaryWaitsForHistorySuppressesDelivery() {
        seedSummary()
        withHeldHistory {
            val finished = CountDownLatch(1)
            val result = AtomicReference<Boolean?>()
            assertTrue(DailySummaryReceiver.postAsync(ctx, force = false) { result.set(it); finished.countDown() })
            awaitSummaryHistoryWait()
            Prefs.setEnabled(ctx, false)
            it.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(false, result.get())
            assertTrue(manager.activeNotifications.none { notification -> notification.id == EventNotifications.SUMMARY_ID })
            assertEquals(0L, Prefs.sp(ctx).getLong("daily_summary_last_at", 0L))
        }
    }

    @Test fun expiredSummaryFinishesBeforeHistoryReturnsAndNeverPostsLate() {
        seedSummary()
        withHeldHistory { release ->
            var expire: (() -> Unit)? = null
            val dispatcher = SummaryDispatcher(deadline = { _, callback -> expire = callback; {} })
            val finished = AtomicInteger()
            val physicalEnd = CountDownLatch(1)
            val result = AtomicReference<Boolean?>()
            assertTrue(dispatcher.submit({ current ->
                try { DailySummaryReceiver.post(ctx, force = false, isCurrent = current) }
                finally { physicalEnd.countDown() }
            }, { result.set(it) }, { finished.incrementAndGet() }))
            awaitSummaryHistoryWait()
            expire!!()
            assertEquals(false, result.get())
            assertEquals(1, finished.get())
            assertEquals(1L, release.count)
            release.countDown()
            assertTrue(physicalEnd.await(2, TimeUnit.SECONDS))
            assertEquals(1, finished.get())
            assertTrue(manager.activeNotifications.none { notification -> notification.id == EventNotifications.SUMMARY_ID })
        }
    }

    @Test fun stopDuringNotificationPreparationSuppressesSummaryBeforeNotify() {
        seedSummary()
        val preparing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<Boolean?>()
        val failure = AtomicReference<Throwable?>()
        val context = object : ContextWrapper(ctx) {
            override fun getPackageName(): String {
                if (Thread.currentThread().name == "summary-preparation") {
                    preparing.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                return super.getPackageName()
            }
        }
        val worker = thread(name = "summary-preparation") {
            try { result.set(DailySummaryReceiver.post(context, force = false)) }
            catch (e: Throwable) { failure.set(e) }
        }
        try {
            assertTrue("The real Notification/PendingIntent preparation must begin first",
                preparing.await(2, TimeUnit.SECONDS))
            Prefs.setEnabled(ctx, false)
            release.countDown()
            worker.join(2000)
            assertFalse(worker.isAlive)
            assertNull(failure.get())
            assertEquals(false, result.get())
            assertTrue(manager.activeNotifications.none { notification -> notification.id == EventNotifications.SUMMARY_ID })
        } finally {
            release.countDown()
            worker.join(2000)
            assertFalse(worker.isAlive)
        }
    }

    private fun seedSummary() {
        val now = System.currentTimeMillis()
        History.replaceSnapshot(ctx, listOf(Observation(now - 60_000, now, "OK", 12)))
        assertTrue(Prefs.sp(ctx).edit().putBoolean("enabled", true).putBoolean("daily_summary", true)
            .remove("daily_summary_last_at").commit())
    }

    private fun withHeldHistory(block: (CountDownLatch) -> Unit) {
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread(name = "held-summary-history") {
            synchronized(History) { locked.countDown(); release.await(10, TimeUnit.SECONDS) }
        }
        try { assertTrue(locked.await(2, TimeUnit.SECONDS)); block(release) }
        finally { release.countDown(); holder.join(2000); assertFalse(holder.isAlive) }
    }

    private fun awaitSummaryHistoryWait() {
        repeat(40) {
            if (Thread.getAllStackTraces().any { (worker, stack) -> worker.name == "tg-summary" &&
                    worker.state == Thread.State.BLOCKED && stack.any { frame ->
                        frame.className == DailySummaryReceiver.Companion::class.java.name && frame.methodName == "post" } }) return
            SystemClock.sleep(50)
        }
        val stacks = Thread.getAllStackTraces().filter { it.key.name == "tg-summary" }
            .entries.joinToString("\n") { (worker, frames) -> "$worker ${worker.state}\n" + frames.joinToString("\n") }
        throw AssertionError("Summary worker did not wait for the real History monitor\n$stacks")
    }

    private fun service(context: Context = ctx): MonitorService {
        val service = MonitorService()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(service, context)
        field("historySession").setLong(service, HistorySessions.queue(ctx).open())
        field("cm").set(service, ctx.getSystemService(ConnectivityManager::class.java))
        field("pm").set(service, ctx.getSystemService(PowerManager::class.java))
        field("nm").set(service, manager)
        field("eventNotifications").set(service, EventNotifications(context, manager))
        field("activeService").set(null, service)
        field("running").setBoolean(null, true)
        assertTrue(Prefs.sp(ctx).edit().putBoolean("enabled", true).putBoolean("keep_awake", false)
            .putBoolean(TargetPreferences.key(TargetId.TELEGRAM, "enabled"), true)
            .putBoolean("vibrate", false).putBoolean("notify_outage", true).putBoolean("notify_recovery", true)
            .putBoolean("quiet_hours", false).remove("alerts_muted_until").remove("alert_mute_until_elapsed").commit())
        fixtureService = service
        return service
    }

    private fun seedOutage(): MonitorService.State {
        val now = System.currentTimeMillis()
        val previous = MonitorService.State(MonitorService.Status.TG_DOWN, now - 5000, now - 5000,
            reason = "confirmed outage", checkedElapsed = SystemClock.elapsedRealtime() - 5000)
        field("state").set(null, previous)
        History.replaceSnapshot(ctx, listOf(Observation(now - 5000, now + 30_000, "TG_DOWN")))
        EventNotifications(ctx, manager).outage(previous.reason, false, true)
        awaitNotification(EventNotifications.OUTAGE_ID)
        return previous
    }

    private fun publish(service: MonitorService, status: MonitorService.Status, revision: Long) {
        val probeType = Class.forName("ru.tgwatch.MonitorService\$Probe")
        val probe = probeType.declaredConstructors.first { it.parameterCount == 6 }.apply { isAccessible = true }
            .newInstance(status, 12L, "measured result", "primary diagnostics", emptyList<ProbeOutcome>(), null)
        type.getDeclaredMethod("publishCheckedResult", probeType, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(service, probe, revision)
        HistorySessions.queue(ctx).exclusive {}.get(5, TimeUnit.SECONDS)
    }

    private fun failingLastSuccessContext(): Context {
        val actual = Prefs.sp(ctx)
        val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            if (method.name != "edit") method.invoke(actual, *(args ?: emptyArray())) else {
                val actualEdit = actual.edit()
                var lastSuccess = false
                lateinit var edit: SharedPreferences.Editor
                edit = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java)) { _, editMethod, editArgs ->
                    if (editMethod.name == "putLong" && editArgs?.get(0) == "last_success_at") lastSuccess = true
                    if (editMethod.name == "commit" && lastSuccess) throw IllegalStateException("private fixture failure")
                    val result = editMethod.invoke(actualEdit, *(editArgs ?: emptyArray()))
                    if (result === actualEdit) edit else result
                } as SharedPreferences.Editor
                edit
            }
        } as SharedPreferences
        return object : ContextWrapper(ctx.createDeviceProtectedStorageContext()) {
            override fun getApplicationContext(): Context = this
            override fun createDeviceProtectedStorageContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        }
    }

    private fun awaitNotification(id: Int): Notification {
        repeat(40) {
            manager.activeNotifications.firstOrNull { it.id == id }?.let { return it.notification }
            SystemClock.sleep(50)
        }
        throw AssertionError("Notification $id was not posted")
    }
}
