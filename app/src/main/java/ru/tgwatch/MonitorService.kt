package ru.tgwatch

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded parallel MTProto/HTTPS monitoring with a persistent status and optional event alerts.
 */
class MonitorService : Service() {

    enum class Status { UNKNOWN, OK, PARTIAL, TG_DOWN, NO_NETWORK }

    data class State(
        val status: Status = Status.UNKNOWN,
        val checkedAt: Long = 0L,
        val since: Long = System.currentTimeMillis(),
        val latencyMs: Long = -1L,
        val reason: String = "",
        val lastVibrationAt: Long = 0L,
        val diagnostics: String = "",
        val expectedIntervalSec: Int = 30,
        val checkedElapsed: Long = 0L,
    ) {
        val bad get() = status == Status.TG_DOWN || status == Status.NO_NETWORK

        /**
         * Judged against the interval this result scheduled: [expectedIntervalSec] already holds the
         * profile, screen-off sleep and offline backoff, so current settings are not re-read here.
         */
        fun isStale(now: Long, elapsedNow: Long = SystemClock.elapsedRealtime()): Boolean =
            checkedAt > 0L && (now < checkedAt || if (checkedElapsed > 0L)
                ProbeRules.isStale(checkedElapsed, elapsedNow, expectedIntervalSec)
                else ProbeRules.isStale(checkedAt, now, expectedIntervalSec))
    }

    private data class Probe(val status: Status, val latencyMs: Long, val reason: String, val diagnostics: String = "",
        val outcomes: List<ProbeOutcome> = emptyList(), val extraCheck: ExtraCheck? = null) {
        val publishable get() = if (outcomes.isEmpty()) status != Status.UNKNOWN else ProbeReport(outcomes).publishable
    }

    private data class ExtraCheck(val token: ExtraProbeCoordinator.Token, val probe: NetworkProbe,
        val endpoints: List<ProbeEndpoint>)

    companion object {
        private const val TAG = "TgWatch"

        const val ACTION_START = "ru.tgwatch.action.START"
        const val ACTION_STOP = "ru.tgwatch.action.STOP"
        const val ACTION_CHECK_NOW = "ru.tgwatch.action.CHECK_NOW"
        const val ACTION_HEALTH = "ru.tgwatch.action.HEALTH"
        const val ACTION_SETTINGS = "ru.tgwatch.action.SETTINGS"
        const val ACTION_STATE_CHANGED = "ru.tgwatch.action.STATE_CHANGED"

        /** Будильник системы: пора проверять, даже если телефон спит. */
        private const val ACTION_ALARM = "ru.tgwatch.action.ALARM"

        const val CHECK_URL = "https://api.telegram.org/"

        private const val NOTIFICATION_ID = 1
        private const val VIBRATION_GAP_MS = AlertRules.GAP_MS
        private const val WAKE_LOCK_TIMEOUT_MS = 60_000L
        private const val UNMEASURED_RETRY_MS = 1_000L
        private const val INTERRUPTED_RETRY_MS = 10_000L

        /** Будильник ставим только на настоящие паузы, а не на мгновенные перепроверки. */
        private const val ALARM_MIN_DELAY_MS = 5_000L

        private val EMPTY_NOTIFICATION_STATS = TimeStats(0, 0, 0, 0, 0, 0, 0, -1)

        @Volatile
        var running = false
            private set

        @Volatile
        var state = State()
            private set

        @Volatile private var activeService: MonitorService? = null
        @Volatile private var lastEndpoints: List<ProbeOutcome> = emptyList()
        @Volatile private var lastScheduleDiagnostics = ScheduleDiagnostics.Snapshot()

        fun endpointOutcomes(): List<ProbeOutcome> = lastEndpoints
        fun schedulingDiagnostics(): ScheduleDiagnostics.Snapshot =
            activeService?.scheduleDiagnostics?.snapshot() ?: lastScheduleDiagnostics

        /** Cached counters only: this path never opens or waits for the history journal. */
        fun healthSnapshot(): MonitorHealth {
            val busy = ProbeAdmission.process.snapshot()
            return MonitorHealth(activeService?.checkStartedMono ?: 0L,
                lastEndpoints.count { it.measurement == ProbeMeasurement.NOT_MEASURED }, busy.primary, busy.secondary,
                if (HistorySessions.backpressured) HistoryFailure.BACKPRESSURE else History.healthFailure,
                RecoveryCoordinator.state)
        }

        /** Queue against this service instance only; never create or resurrect a service. */
        fun requestRunningCheck(ctx: Context, id: TargetId = TargetId.TELEGRAM,
            admission: () -> Boolean = { true }): Boolean {
            val service = activeService ?: return false
            return if (id != TargetId.TELEGRAM) service.requestSecondaryCheck(ctx, id, admission)
            else synchronized(service) {
                if (service.acceptsRunningCheck(ctx) && TargetPreferences.definition(ctx, TargetId.TELEGRAM).enabled) {
                    var admitted = false
                    // The queued task cannot acquire this lock until durable admission completes.
                    val queued = service.worker.post {
                        synchronized(service) {
                            val enabled = TargetPreferences.definition(ctx, TargetId.TELEGRAM).enabled
                            if (admitted && service.acceptsRunningCheck(ctx) && enabled) {
                                service.offlineBackoff.reset()
                                service.scheduleCheck(0L)
                            }
                        }
                    }
                    if (queued) admitted = admission()
                    if (admitted) service.scheduleDiagnostics.manual(service.checkStartedMono > 0L)
                    admitted
                } else false
            }
        }

        internal val targetRepository: TargetRepository? get() = activeService
            ?.takeUnless { !running || it.destroyed || it.stopping }?.targetMonitoring?.repository

        fun start(ctx: Context) {
            if (BackupManager.hasPending() && RecoveryCoordinator.state != RecoveryState.INITIALIZING) return
            ctx.startForegroundService(Intent(ctx, MonitorService::class.java).setAction(ACTION_START))
        }

        fun send(ctx: Context, action: String, targetId: TargetId = TargetId.TELEGRAM) {
            if (BackupManager.hasPending() && RecoveryCoordinator.state != RecoveryState.INITIALIZING) return
            val intent = Intent(ctx, MonitorService::class.java).setAction(action)
                .putExtra(TargetAutomation.EXTRA_TARGET_ID, targetId.wire)
            if (running) {
                ctx.startService(intent)
            } else {
                ctx.startForegroundService(intent)
            }
        }

        fun stop(ctx: Context) {
            activeService?.let { synchronized(it) { it.stopping = true; it.extraCoordinator.invalidate() } }
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }

        /** Wait for history/log teardown, not merely the early running=false publication. */
        fun stopAndAwait(ctx: Context, timeoutMs: Long = 10_000L) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                error("Cannot await service/history teardown on the main thread")
            }
            val captured = AtomicReference<MonitorService?>()
            val requested = CountDownLatch(1)
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            Handler(Looper.getMainLooper()).post {
                captured.set(activeService)
                stop(ctx)
                requested.countDown()
            }
            check(requested.await(timeoutMs, TimeUnit.MILLISECONDS)) { "Service stop request timed out" }
            captured.get()?.let {
                check(it.stopped.await((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0),
                    TimeUnit.MILLISECONDS)) { "Service teardown timed out" }
            }
            HistorySessions.awaitClosed(ctx, deadline - SystemClock.elapsedRealtime())
        }

        private val resumeAfterRecovery: () -> Unit = {
            Handler(Looper.getMainLooper()).post {
                activeService?.takeUnless { it.destroyed || it.stopping }?.let {
                    it.onStartCommand(Intent(it, MonitorService::class.java).setAction(ACTION_START), 0, 0)
                }
            }
        }

        fun clearRestoredState() {
            activeService?.let { synchronized(it) { it.extraCoordinator.invalidate() } }
            state = State()
            lastEndpoints = emptyList()
            lastScheduleDiagnostics = ScheduleDiagnostics.Snapshot()
        }

        fun restorePersistedState(ctx: Context) {
            if (state.checkedAt > 0L) return
            Prefs.loadLastState(ctx)?.let { state = it }
        }
    }

    private var historySession = 0L
    private var targetMonitoring: TargetMonitoring? = null
    @Volatile private var telegramDueElapsed: Long? = null
    @Volatile private var primarySettingsRevision: Long? = null
    private var deferredExtra: Probe? = null
    private lateinit var workerThread: HandlerThread
    private val stopped = CountDownLatch(1)
    private lateinit var worker: Handler
    private lateinit var nm: NotificationManager
    private lateinit var cm: ConnectivityManager
    private lateinit var pm: PowerManager
    private lateinit var am: AlarmManager
    private lateinit var eventNotifications: EventNotifications
    private var slowController: SlowAlertController? = null
    private var targetAlertController: TargetAlertController? = null
    private val offlineBackoff = OfflineBackoff()

    @Synchronized private fun requestSecondaryCheck(ctx: Context, id: TargetId, admission: () -> Boolean): Boolean {
        val definition = TargetPreferences.definition(ctx, id)
        val unlocked = getSystemService(android.os.UserManager::class.java).isUserUnlocked
        val available = acceptsRunningCheck(ctx) && definition.enabled && (!definition.requiresEndpoint || unlocked)
        if (!available) return false
        var admitted = false
        val queued = worker.post {
            synchronized(this) {
                if (admitted && acceptsRunningCheck(ctx)) {
                    targetMonitoring?.let { targets ->
                        targets.refresh(notify = false)
                        targets.request(id)
                        targets.startDue()
                    }
                }
            }
        }
        if (queued) admitted = admission()
        return admitted
    }
    private val checkSchedule = CheckSchedule(offlineBackoff::reset, ::enqueueCheck)
    private val scheduleDiagnostics = ScheduleDiagnostics()
    @Volatile private var stopping = false
    private val notificationStats = DailyStatsCache()
    private val notificationGate = RedrawGate()
    private val statisticsPending = AtomicBoolean()

    private fun acceptsRunningCheck(ctx: Context): Boolean = activeService === this && running &&
        !destroyed && !stopping && RecoveryCoordinator.state == RecoveryState.READY &&
        Prefs.isEnabled(ctx) && !BackupManager.hasPending()

    /**
     * Ограниченный пул; один зависший адрес не занимает новые потоки при повторных проверках.
     */
    private val probeExecutor: ExecutorService = NetworkProbe.executor()
    private val extraProbeExecutor: ExecutorService = NetworkProbe.extraExecutor()
    private val extraCoordinator = ExtraProbeCoordinator()

    private var wakeLock: PowerManager.WakeLock? = null
    private var loopStarted = false
    private var lastVibrationMono = -1L
    private var probeNetwork = NetworkKind.UNKNOWN
    @Volatile private var lastProgressMono = 0L
    @Volatile private var checkStartedMono = 0L
    private val healthHandler = Handler(android.os.Looper.getMainLooper())
    private val healthTick = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (RecoveryCoordinator.state == RecoveryState.FAILED) { stopSelf(); return }
            slowController?.reconcile()
            targetAlertController?.reconcile()
            postNotification(state, periodic = true)
            StatusWidget.refreshIfChanged(this@MonitorService)
            scheduleStatisticsRefresh()
            ensureProgress()
            healthHandler.postDelayed(this, 15_000L)
        }
    }
    private fun ensureProgress() {
        val now = SystemClock.elapsedRealtime()
        // Primary checks have a 12-second network budget. If overdue, cancel wait and retry.
        if (checkStartedMono > 0 && now - checkStartedMono > 30_000L) {
            workerThread.interrupt()
        } else if (checkStartedMono == 0L && ServiceHealth.isOverdue(lastProgressMono, now, state.expectedIntervalSec)) {
            scheduleCheck(0L)
        }
    }

    @Volatile
    private var destroyed = false

    private val checkRunnable = Runnable {
        val targets = targetMonitoring
        val now = SystemClock.elapsedRealtime()
        val ready = telegramDueElapsed?.let { it <= now } == true
        if (targets == null || targets.telegramEnabled && ready) {
            telegramDueElapsed = null
            scheduleDiagnostics.arrived(now, pm.isDeviceIdleMode)
            runCheck()
        }
        targets?.startDue()
        if (targets != null) { releaseWakeLock(); rearmChecks() }
    }

    private val networkTransitions = NetworkTransitions()

    /** Only identity and validated/captive-portal changes invalidate a running or good result. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (networkTransitions.available(network.networkHandle)) networkChanged(1_500L)
        }
        override fun onLost(network: Network) {
            networkTransitions.lost()
            networkChanged(500L)
        }
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (networkTransitions.capabilities(network.networkHandle,
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL))) {
                networkChanged(800L)
            }
        }
    }

    @Synchronized private fun networkChanged(delayMs: Long) {
        extraCoordinator.invalidate()
        deferredExtra = null
        checkSchedule.networkChanged(delayMs)
        targetMonitoring?.networkChanged(checkSchedule.revision)
    }

    /** Unlocking after sleep should not show a result from the long screen-off interval. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                checkAlarm.screenOff(SystemClock.elapsedRealtime())
                return
            }
            if (checkStartedMono == 0L &&
                ProbeRules.refreshOnScreenOn(state.checkedElapsed, SystemClock.elapsedRealtime())) {
                scheduleCheck(0L)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeService = this
        historySession = HistorySessions.queue(this).open()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        createChannels()
        eventNotifications = EventNotifications(this, nm)
        targetAlertController = TargetAlertController(this, nm) { acceptsRunningCheck(this) }
        slowController = SlowAlertController(this, eventNotifications, {
            acceptsRunningCheck(this) && TargetPreferences.definition(this, TargetId.TELEGRAM).enabled
        }, { text ->
            HistorySessions.queue(this).submit(historySession) { EventLog.add(this, text) }
        })
        restorePersistedState(this)
        seedVibrationCooldown()

        workerThread = HandlerThread("tg-check")
        workerThread.start()
        worker = Handler(workerThread.looper)
        targetMonitoring = createTargetMonitoring()

        running = true
        lastProgressMono = SystemClock.elapsedRealtime()
        healthHandler.postDelayed(healthTick, 15_000L)
        WatchdogReceiver.schedule(this)
        try {
            cm.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "registerDefaultNetworkCallback", e)
        }
        // SCREEN_ON/OFF are protected system broadcasts, so no export flag is needed.
        val screen = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
        registerReceiver(screenReceiver, screen)
    }

    private fun createTargetMonitoring() = TargetMonitoring(object : TargetMonitorHost {
        override val context: Context get() = this@MonitorService
        override val worker: Handler get() = this@MonitorService.worker
        override val primaryPool: ExecutorService get() = probeExecutor
        override val secondaryPool: ExecutorService get() = extraProbeExecutor
        override fun isCurrent(): Boolean =
            activeService === this@MonitorService && acceptsRunningCheck(this@MonitorService)
        override fun acquireWakeLock() = acquireWakeLockForCheck()
        override fun onProgress() {
            lastProgressMono = SystemClock.elapsedRealtime()
            releaseWakeLock()
            drainDeferredExtras()
            rearmChecks()
        }
        override fun onPublished(snapshot: TargetSnapshot, report: TargetReport) {
            if (snapshot.id != TargetId.TELEGRAM && report.publishable) {
                TargetHistories.publish(this@MonitorService, historySession, snapshot, report.publishable) { recovery ->
                    val current = targetMonitoring?.repository?.snapshot(snapshot.id) === snapshot
                    if (isCurrent() && current) targetAlertController?.recovered(snapshot, recovery)
                }
                runCatching { targetAlertController?.onCheck(snapshot) }.onFailure {
                    Log.w(TAG, "target alert: ${it.javaClass.simpleName}")
                }
                WebhookNotifier.onPublished(this@MonitorService, TargetWebhookObservation.from(snapshot, report,
                    targetMonitoring?.repository?.currentNetworkRevision ?: 0L,
                    System.currentTimeMillis(), SystemClock.elapsedRealtime()))
                postNotification(state)
                broadcastState()
            }
        }
    })

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val intended = TargetId.entries.filterTo(mutableSetOf()) {
            Prefs.isEnabled(this) && TargetPreferences.storedDefinition(this, it).enabled
        }
        val command = TargetServiceCommands.route(intent?.action,
            intent?.getStringExtra(TargetAutomation.EXTRA_TARGET_ID), intended)
        if (command.ignored) return START_STICKY
        val action = command.action
        if (command.stopTarget) TargetPreferences.stop(this, command.targetId)
        recordRequestedTrigger(action)
        if (command.stopAll) Prefs.setEnabled(this, false)
        if (awaitingRecovery()) return START_NOT_STICKY
        if (action == ACTION_SETTINGS || action == ACTION_CHECK_NOW) offlineBackoff.reset()
        if (!Prefs.isEnabled(this) || BackupManager.hasPending()) {
            stopSelf()
            return START_NOT_STICKY
        }

        targetMonitoring?.let { targets -> worker.post { targets.refresh() } }
        if (action == ACTION_HEALTH) ensureProgress()
        val first = beginLoop()
        slowController?.reconcile()
        targetAlertController?.reconcile()
        dispatchStartAction(action, first, command.targetId)

        return START_STICKY
    }

    private fun dispatchStartAction(action: String?, first: Boolean, targetId: TargetId) {
        if (action == ACTION_ALARM) {
            val now = SystemClock.elapsedRealtime()
            if (targetMonitoring == null || telegramDueElapsed?.let { it <= now } == true)
                scheduleDiagnostics.arrived(now, pm.isDeviceIdleMode, checkStartedMono > 0L)
            // Будильник разбудил процессор лишь на мгновение: держим его, пока не пройдёт проверка.
            acquireWakeLockForCheck(force = true)
        }
        if (first) {
            scheduleCheck(0L)
        } else if (action == ACTION_CHECK_NOW || action == ACTION_SETTINGS) {
            if (targetId == TargetId.TELEGRAM && TargetPreferences.definition(this, targetId).enabled) {
                scheduleCheck(0L)
            } else worker.post {
                targetMonitoring?.let { targets ->
                    targets.refresh(notify = false)
                    if (action == ACTION_CHECK_NOW) targets.request(targetId)
                    rearmChecks()
                }
            }
        } else if (action == ACTION_ALARM) {
            // The alarm duplicates the pending timer; it must not queue a check after a running one.
            if (targetMonitoring == null) checkSchedule.wake() else rearmChecks()
        }

    }

    private fun beginLoop(): Boolean {
        val first = !loopStarted
        loopStarted = true
        if (first) HistorySessions.queue(this).submit(historySession) {
            EventLog.add(this, Lang.t("Мониторинг запущен", "Monitoring started"))
        }
        return first
    }

    private fun awaitingRecovery(): Boolean {
        val current = RecoveryCoordinator.state
        if (current == RecoveryState.INITIALIZING && !stopping) RecoveryCoordinator.whenReady(resumeAfterRecovery)
        if (current == RecoveryState.FAILED || stopping) stopSelf()
        return current != RecoveryState.READY || stopping
    }

    @Synchronized private fun recordRequestedTrigger(action: String?) {
        if (action == ACTION_STOP) {
            stopping = true
            extraCoordinator.invalidate()
        }
        if (action == ACTION_CHECK_NOW) scheduleDiagnostics.manual(checkStartedMono > 0L)
    }

    @Synchronized override fun onDestroy() {
        try {
        destroyed = true
        stopping = true
        slowController?.close()
        targetAlertController?.close()
        targetMonitoring?.close()
        deferredExtra = null
        extraCoordinator.close()
        scheduleDiagnostics.canceled()
        lastScheduleDiagnostics = scheduleDiagnostics.snapshot()
        lastEndpoints = emptyList()
        healthHandler.removeCallbacksAndMessages(null)
        running = false
        worker.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
        workerThread.interrupt()
        cancelAlarm()
        probeExecutor.shutdownNow()
        extraProbeExecutor.shutdownNow()
        try {
            cm.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterNetworkCallback", e)
        }
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterReceiver", e)
        }
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        nm.cancel(NOTIFICATION_ID)
        HistorySessions.queue(this).close(historySession, System.currentTimeMillis())
        if (!Prefs.isEnabled(this)) {
            if (loopStarted) Prefs.clearLastState(this)
            WatchdogReceiver.cancel(this)
        } else {
            // Процесс умирает, а мониторинг всё ещё нужен — watchdog поднимет службу.
            WatchdogReceiver.schedule(this)
        }
        state = State()
        broadcastState()
        super.onDestroy()
        } finally {
            if (activeService === this) activeService = null
            stopped.countDown()
        }
    }

    private fun seedVibrationCooldown() {
        val last = state.lastVibrationAt
        if (last <= 0L) return
        val age = System.currentTimeMillis() - last
        if (age in 0 until VIBRATION_GAP_MS) {
            lastVibrationMono = SystemClock.elapsedRealtime() - age
        }
    }

    // ---------------------------------------------------------------- проверка

    private fun scheduleCheck(delayMs: Long) {
        checkSchedule.request(delayMs)
    }

    private fun enqueueCheck(delayMs: Long) {
        if (destroyed || stopping) return
        val now = SystemClock.elapsedRealtime()
        telegramDueElapsed = now + delayMs
        if (delayMs >= ALARM_MIN_DELAY_MS) scheduleDiagnostics.scheduled(now, delayMs)
        else scheduleDiagnostics.canceled()
        if (targetMonitoring != null) { rearmChecks(); return }
        worker.removeCallbacks(checkRunnable)
        worker.postDelayed(checkRunnable, delayMs)
        // Обычный таймер замирает, когда телефон засыпает с выключенным экраном.
        // Поэтому дублируем его системным будильником, который будит процессор.
        // Короткие перепроверки будильник не трогают: если телефон уснёт раньше,
        // уже поставленный будильник всё равно разбудит его к следующей проверке.
        if (delayMs >= ALARM_MIN_DELAY_MS) scheduleAlarm(delayMs)
    }

    private fun rearmChecks() {
        if (destroyed || stopping) return
        val targets = targetMonitoring ?: return
        val primary = telegramDueElapsed.takeIf { targets.telegramEnabled }
        val delay = CheckAlarm.nextDelay(SystemClock.elapsedRealtime(), primary, targets.nextWakeElapsed())
        worker.removeCallbacks(checkRunnable)
        if (delay == null) cancelAlarm() else {
            worker.postDelayed(checkRunnable, delay)
            if (delay >= ALARM_MIN_DELAY_MS) scheduleAlarm(delay)
        }
    }

    private fun drainDeferredExtras() {
        if (targetMonitoring?.hasInFlight == true) return
        val pending = deferredExtra ?: return
        deferredExtra = null
        startExtras(pending)
    }

    private fun servicePending(requestCode: Int, action: String): PendingIntent {
        val intent = Intent(this, MonitorService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= 26) {
            PendingIntent.getForegroundService(this, requestCode, intent, flags)
        } else {
            PendingIntent.getService(this, requestCode, intent, flags)
        }
    }

    private fun alarmIntent(): PendingIntent = servicePending(3, ACTION_ALARM)

    private val checkAlarm = CheckAlarm(::armAlarm, ::disarmAlarm, { pm.isInteractive })

    private fun scheduleAlarm(delayMs: Long) {
        if (Prefs.keepAwake(this)) checkAlarm.schedule(SystemClock.elapsedRealtime(), delayMs)
        else cancelAlarm()
    }

    private fun armAlarm(at: Long) {
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmIntent())
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmIntent())
            }
        } catch (e: Exception) {
            Log.w(TAG, "alarm", e)
        }
    }

    private fun cancelAlarm() = checkAlarm.cancel()

    private fun disarmAlarm() {
        try {
            am.cancel(alarmIntent())
        } catch (e: Exception) {
            Log.w(TAG, "cancel alarm", e)
        }
    }

    private fun runCheck() {
        if (!acceptsRunningCheck(this)) return
        // An interrupt from the health monitor must not poison the following check.
        Thread.interrupted()
        deferredExtra = null
        extraCoordinator.invalidate()
        checkStartedMono = SystemClock.elapsedRealtime()
        val revision = checkSchedule.begin()
        primarySettingsRevision = TargetPreferences.fence.snapshot(TargetId.TELEGRAM).revision
        var retryDelayMs: Long? = null
        acquireWakeLockForCheck()
        try {
            val result = performCheck(revision)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("cancelled")
            if (!acceptsRunningCheck(this)) return
            publishCheckedResult(result, revision)
            lastProgressMono = SystemClock.elapsedRealtime()
        } catch (_: InterruptedException) {
            // No completed observation: retain the prior state/alerts and let freshness expire.
            Thread.currentThread().interrupt()
            retryDelayMs = INTERRUPTED_RETRY_MS
            lastProgressMono = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            if (Thread.currentThread().isInterrupted) retryDelayMs = INTERRUPTED_RETRY_MS
            else if (acceptsRunningCheck(this)) {
                try {
                    publishCheckedResult(Probe(Status.UNKNOWN, -1,
                        Lang.t("Проверка не завершена: ${describeProbeError(e)}",
                            "Check did not finish: ${describeProbeError(e)}")), revision)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    retryDelayMs = INTERRUPTED_RETRY_MS
                }
            }
            lastProgressMono = SystemClock.elapsedRealtime()
        } finally {
            primarySettingsRevision = null
            checkStartedMono = 0L
            releaseWakeLock()
        }
        if (acceptsRunningCheck(this)) {
            checkSchedule.afterCheck(revision, retryDelayMs ?: state.expectedIntervalSec * 1000L)
            // Re-arming replaces the pending watchdog: it wakes the phone only when checks stop landing.
            WatchdogReceiver.schedule(this, ServiceHealth.watchdogDelayMs(state.expectedIntervalSec))
        }
    }

    private fun performCheck(revision: Long): Probe {
        val result = PrimaryAndroidProbe.create(cm, probeExecutor, extraProbeExecutor,
            extraCoordinator, ::informationalEndpoints).check(revision)
        probeNetwork = result.networkKind
        return Probe(result.status, result.latencyMs, result.reason, result.diagnostics, result.outcomes,
            result.extras?.let { ExtraCheck(it.token, it.probe, it.endpoints) })
    }

    private fun informationalEndpoints(): List<ProbeEndpoint> {
        val extras = Prefs.extraProbeEndpoints(this)
        val targets = targetMonitoring ?: return extras
        return TargetEndpoints.informational(extras, targets.repository.definitions()) {
            TargetPreferences.endpoint(this, it)
        }
    }

    /** Network callbacks and primary publication share this lock, including revision validation. */
    @Synchronized private fun publishCheckedResult(result: Probe, revision: Long) {
        val settingsRevision = TargetPreferences.fence.snapshot(TargetId.TELEGRAM).revision
        val currentSettings = primarySettingsRevision?.let { it == settingsRevision } ?: true
        if (!acceptsRunningCheck(this) || !TargetPreferences.definition(this, TargetId.TELEGRAM).enabled ||
            !currentSettings) return
        if (Thread.currentThread().isInterrupted) throw InterruptedException("cancelled")
        // A result from a network that has since changed is no observation: keep the prior state,
        // history and alerts. afterCheck() runs the replacement check one second later.
        if (revision != checkSchedule.revision) return
        if (result.publishable) {
            handleResult(result)
            targetMonitoring?.startDue()
            startExtras(result)
        } else {
            // Admission failure is monitor health, not a new observation about Telegram.
            lastEndpoints = result.outcomes.toList()
            checkSchedule.request(UNMEASURED_RETRY_MS)
        }
    }

    @Synchronized private fun startExtras(primary: Probe) {
        val extra = primary.extraCheck ?: return
        if (extra.endpoints.isEmpty() || Thread.currentThread().isInterrupted || !acceptsExtras(extra.token)) return
        if (targetMonitoring?.hasInFlight == true) deferredExtra = primary
        else extraCoordinator.submit(extra.token, { extra.probe.checkExtras(extra.endpoints) }) { outcomes ->
            worker.post { publishExtras(primary, extra.token, outcomes) }
        }
    }

    private fun acceptsExtras(token: ExtraProbeCoordinator.Token): Boolean = acceptsRunningCheck(this) &&
        extraCoordinator.accepts(token, checkSchedule.revision, cm.activeNetwork?.networkHandle)

    /** Diagnostic delivery deliberately does not enter handleResult or any persistence/alert/scheduling path. */
    @Synchronized private fun publishExtras(primary: Probe, token: ExtraProbeCoordinator.Token,
        outcomes: List<ProbeOutcome>) {
        if (!acceptsExtras(token)) return
        val details = primary.diagnostics + Lang.t("\nДругие сервисы (не влияют на статус Telegram):\n",
            "\nOther services (do not affect Telegram status):\n") +
            outcomes.joinToString("\n", transform = PrimaryAndroidProbe::outcomeLine)
        lastEndpoints = primary.outcomes + outcomes
        state = state.copy(diagnostics = details)
        broadcastState()
    }

    @Synchronized private fun handleResult(p: Probe) {
        if (!acceptsRunningCheck(this)) return
        lastEndpoints = p.outcomes.toList()
        val now = System.currentTimeMillis()
        val prev = state
        val changed = prev.status != p.status
        var lastVibration = prev.lastVibrationAt

        val partialAlert = Prefs.vibratePartial(this)
        val mono = SystemClock.elapsedRealtime()
        val alertsAllowed = Prefs.alertsAllowed(this, now)
        val vibrate = AlertRules.shouldAlarm(p.status.name, Prefs.vibrateEnabled(this), Prefs.vibrateOffline(this),
            partialAlert, alertsAllowed, mono, lastVibrationMono)
        val sound = AlertRules.shouldAlarm(p.status.name,
            Prefs.notifyFor(this, "TG_DOWN") && Prefs.soundFor(this, "TG_DOWN"), false,
            Prefs.notifyFor(this, "PARTIAL") && Prefs.soundFor(this, "PARTIAL"),
            alertsAllowed, mono, lastVibrationMono)
        if (vibrate || sound) {
            lastVibrationMono = mono
            lastVibration = now
            try {
                if (vibrate) Vibe.alarm(this)
            } catch (e: Exception) {
                Log.w(TAG, "vibrate", e)
            }
        }

        val newState = State(
            status = p.status,
            checkedAt = now,
            checkedElapsed = SystemClock.elapsedRealtime(),
            since = if (changed) now else prev.since,
            latencyMs = p.latencyMs,
            reason = p.reason,
            lastVibrationAt = lastVibration,
            diagnostics = p.diagnostics,
            expectedIntervalSec = offlineBackoff.next(p.status.name,
                Prefs.effectiveIntervalSec(this, p.status == Status.TG_DOWN || p.status == Status.NO_NETWORK, !pm.isInteractive)),
        )
        state = newState
        Prefs.saveLastState(
            this, newState.status, newState.checkedAt, newState.since,
            newState.latencyMs, newState.reason, newState.lastVibrationAt, newState.diagnostics, newState.expectedIntervalSec,
        )

        MonitorHistory.publish(this, historySession,
            HistoryPublication(newState, changed, partialAlert || Prefs.notifyFor(this, "PARTIAL")), { recovery ->
                synchronized(this) {
                    if (acceptsRunningCheck(this) && state === newState) {
                        if (Prefs.notifyFor(this, "RECOVERY")) eventNotifications.recovered(recovery,
                            Prefs.soundFor(this, "RECOVERY"), Prefs.alertsAllowed(this))
                        if (Prefs.vibrateOnRecovery(this) && Prefs.alertsAllowed(this)) Vibe.recovery(this)
                    }
                }
            }, { refreshNotificationStats(now) })

        try {
            if (p.status !in setOf(Status.TG_DOWN, Status.PARTIAL)) eventNotifications.clearOutage()
            if (changed && p.status != Status.OK) eventNotifications.clearRecovery()
            if ((changed || sound) && p.status == Status.TG_DOWN && Prefs.notifyFor(this, "TG_DOWN"))
                eventNotifications.outage(p.reason, sound, alertsAllowed)
            if ((changed || sound) && p.status == Status.PARTIAL && Prefs.notifyFor(this, "PARTIAL"))
                eventNotifications.partial(p.reason, sound, alertsAllowed)
            if (p.status == Status.TG_DOWN && !Prefs.notifyFor(this, "TG_DOWN")) eventNotifications.clearOutage()
            if (p.status == Status.PARTIAL && !Prefs.notifyFor(this, "PARTIAL")) eventNotifications.clearOutage()
            slowController?.onCheck(p.status.name, p.latencyMs, mono)
        } catch (e: Exception) { Log.w(TAG, "event alert", e) }

        targetMonitoring?.onTelegram(newState, p.outcomes)
        postNotification(newState)
        broadcastState()
        // Opt-in, credential-protected and asynchronous; never blocks or alters the published state.
        WebhookNotifier.onPublished(this, WebhookObservation("telegram", newState.status.name,
            newState.checkedAt, newState.latencyMs.takeIf { it >= 0L }, probeNetwork, checkSchedule.revision,
            measured = newState.status != Status.UNKNOWN, stale = newState.isStale(now),
            expectedIntervalSec = newState.expectedIntervalSec))
    }

    private fun broadcastState() {
        StatusWidget.updateAll(this)
        try {
            sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
        } catch (e: Exception) {
            Log.w(TAG, "broadcastState", e)
        }
    }

    // ------------------------------------------------------------ уведомление

    private fun createChannels() = OngoingNotification.createChannels(nm)

    private fun goForeground(): Boolean {
        val view = notificationView(state)
        val notification = buildNotification(view)
        notificationGate.shouldDraw(view, SystemClock.elapsedRealtime())
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            scheduleStatisticsRefresh()
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            false
        }
    }

    private fun scheduleStatisticsRefresh() {
        val refreshDue = notificationStats.needsRefresh(History.version, System.currentTimeMillis())
        if (!acceptsRunningCheck(this) || !refreshDue) return
        if (!statisticsPending.compareAndSet(false, true)) return
        val admitted = HistorySessions.queue(this).submit(historySession) {
            try {
                if (acceptsRunningCheck(this)) {
                    refreshNotificationStats()
                    postNotification(state)
                }
            } finally { statisticsPending.set(false) }
        }
        if (!admitted) statisticsPending.set(false)
    }

    private fun refreshNotificationStats(now: Long = System.currentTimeMillis()) {
        if (!notificationStats.needsRefresh(History.version, now)) return
        val read = History.readStats(this, now = now)
        if (read is HistoryRead.Ready) notificationStats.get(History.version, now) { read.value }
    }

    /** [periodic] — тик раз в 15 с: одинаковое содержимое повторно не отправляем в шторку. */
    private fun postNotification(s: State, periodic: Boolean = false) {
        if (destroyed) return
        val view = notificationView(s)
        if (!notificationGate.shouldDraw(view, SystemClock.elapsedRealtime()) && periodic) return
        try {
            nm.notify(NOTIFICATION_ID, buildNotification(view))
        } catch (e: Exception) {
            Log.w(TAG, "notify", e)
        }
        if (destroyed) nm.cancel(NOTIFICATION_ID)
    }

    private fun buildNotification(s: State): Notification = buildNotification(notificationView(s))

    /** Complete presentation key, derived only from the immutable state and cached aggregates. */
    private fun notificationView(s: State, now: Long = System.currentTimeMillis()): OngoingView =
        if (TargetPreferences.definition(this, TargetId.TELEGRAM).enabled)
            OngoingNotification.view(s, currentNotificationStats(), now)
        else OngoingNotification.targets(TargetId.entries.filter { it != TargetId.TELEGRAM &&
            Prefs.isEnabled(this) && TargetPreferences.storedDefinition(this, it).enabled }
            .map { TargetStateStore.current(this, it) }, now, SystemClock.elapsedRealtime())

    private fun buildNotification(view: OngoingView): Notification =
        OngoingNotification.build(this, view, ::servicePending)

    private fun currentNotificationStats(): TimeStats = notificationStats.peek() ?: EMPTY_NOTIFICATION_STATS

    // --------------------------------------------------------------- wake lock

    /** Держим wake lock только на время одной проверки, а не весь день. */
    private fun acquireWakeLockForCheck(force: Boolean = false) {
        if (!force && !Prefs.keepAwake(this)) return
        try {
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TgWatch:check").apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "wakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        val checksActive = checkStartedMono > 0L || targetMonitoring?.hasInFlight == true
        if (!destroyed && checksActive) return
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
    }
}

/** 99,8 % in Russian, 99.8% in English. */
fun formatPercent(p: Double): String =
    if (Lang.ru) {
        if (p >= 99.95 && p < 100.0) "99,9 %"
        else String.format(java.util.Locale("ru"), "%.1f %%", p).replace(",0 %", " %")
    } else {
        if (p >= 99.95 && p < 100.0) "99.9%"
        else String.format(java.util.Locale.US, "%.1f%%", p).replace(".0%", "%")
    }

