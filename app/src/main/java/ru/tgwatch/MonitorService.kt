package ru.tgwatch

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.drawable.Icon
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
import java.net.HttpURLConnection
import java.util.concurrent.ExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        val outcomes: List<ProbeOutcome> = emptyList(), val extraCheck: ExtraCheck? = null)

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

        private const val CHANNEL_OK = "status_ok_v2"
        private const val CHANNEL_ALERT = "status_alert_v2"
        private const val NOTIFICATION_ID = 1
        private const val VIBRATION_GAP_MS = AlertRules.GAP_MS
        private const val NETWORK_GRACE_MS = 4_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 60_000L
        private const val INTERRUPTED_RETRY_MS = 10_000L

        /** Будильник ставим только на настоящие паузы, а не на мгновенные перепроверки. */
        private const val ALARM_MIN_DELAY_MS = 5_000L

        private val COLOR_OK = Color.rgb(0x22, 0x9E, 0xD9)
        private val COLOR_FAIL = Color.rgb(0xE5, 0x39, 0x35)
        private val COLOR_OFFLINE = Color.rgb(0xF5, 0x7C, 0x00)
        private val COLOR_STALE = Color.rgb(0x8A, 0x96, 0xA3)
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

        /** Queue against this service instance only; never create or resurrect a service. */
        fun requestRunningCheck(ctx: Context, admission: () -> Boolean = { true }): Boolean {
            val service = activeService ?: return false
            return synchronized(service) {
                if (service.acceptsRunningCheck(ctx)) {
                    var admitted = false
                    // The queued task cannot acquire this lock until durable admission completes.
                    val queued = service.worker.post {
                        synchronized(service) {
                            if (admitted && service.acceptsRunningCheck(ctx)) {
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

        fun start(ctx: Context) {
            if (BackupManager.hasPending(ctx)) return
            ctx.startForegroundService(Intent(ctx, MonitorService::class.java).setAction(ACTION_START))
        }

        fun send(ctx: Context, action: String) {
            if (BackupManager.hasPending(ctx)) return
            val intent = Intent(ctx, MonitorService::class.java).setAction(action)
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
                check(activeService == null) { "Cannot await a live service on the main thread" }
                return // Application startup, before service creation.
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

    private lateinit var workerThread: HandlerThread
    private val stopped = CountDownLatch(1)
    private lateinit var worker: Handler
    private lateinit var nm: NotificationManager
    private lateinit var cm: ConnectivityManager
    private lateinit var pm: PowerManager
    private lateinit var am: AlarmManager
    private lateinit var eventNotifications: EventNotifications
    private val offlineBackoff = OfflineBackoff()
    private val slowAlert = SlowAlert()
    private val checkSchedule = CheckSchedule(offlineBackoff::reset, ::enqueueCheck)
    private val scheduleDiagnostics = ScheduleDiagnostics()
    @Volatile private var stopping = false
    private val notificationStats = DailyStatsCache()
    private val notificationGate = RedrawGate()
    private val statisticsRunnable = Runnable {
        if (!destroyed) {
            refreshNotificationStats()
            postNotification(state)
        }
    }

    private fun acceptsRunningCheck(ctx: Context): Boolean = activeService === this && running &&
        !destroyed && !stopping && Prefs.isEnabled(ctx) && !BackupManager.hasPending(ctx)

    /**
     * Ограниченный пул; один зависший адрес не занимает новые потоки при повторных проверках.
     */
    private val probeExecutor: ExecutorService = NetworkProbe.executor()
    private val extraProbeExecutor: ExecutorService = NetworkProbe.extraExecutor()
    private val extraCoordinator = ExtraProbeCoordinator()

    private var wakeLock: PowerManager.WakeLock? = null
    private var loopStarted = false
    private var lastVibrationMono = -1L
    @Volatile private var lastProgressMono = 0L
    @Volatile private var checkStartedMono = 0L
    private val healthHandler = Handler(android.os.Looper.getMainLooper())
    private val healthTick = object : Runnable {
        override fun run() {
            if (destroyed) return
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
        scheduleDiagnostics.arrived(SystemClock.elapsedRealtime(), pm.isDeviceIdleMode)
        runCheck()
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
        checkSchedule.networkChanged(delayMs)
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
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        createChannels()
        eventNotifications = EventNotifications(this, nm)
        restorePersistedState(this)
        seedVibrationCooldown()

        workerThread = HandlerThread("tg-check")
        workerThread.start()
        worker = Handler(workerThread.looper)

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val action = intent?.action
        recordRequestedTrigger(action)
        if (action == ACTION_SETTINGS || action == ACTION_CHECK_NOW) offlineBackoff.reset()
        if (action == ACTION_STOP) Prefs.setEnabled(this, false)
        if (!Prefs.isEnabled(this) || BackupManager.hasPending(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_HEALTH) ensureProgress()
        val first = !loopStarted
        loopStarted = true
        if (first) EventLog.add(this, Lang.t("Мониторинг запущен", "Monitoring started"))
        if (action == ACTION_ALARM) {
            scheduleDiagnostics.arrived(SystemClock.elapsedRealtime(), pm.isDeviceIdleMode, checkStartedMono > 0L)
            // Будильник разбудил процессор лишь на мгновение: держим его, пока не пройдёт проверка.
            acquireWakeLockForCheck(force = true)
        }
        if (first || action == ACTION_CHECK_NOW || action == ACTION_SETTINGS) {
            scheduleCheck(0L)
        } else if (action == ACTION_ALARM) {
            // The alarm duplicates the pending timer; it must not queue a check after a running one.
            checkSchedule.wake()
        }

        return START_STICKY
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
        if (loopStarted) EventLog.add(this, Lang.t("Мониторинг остановлен", "Monitoring stopped"))
        if (loopStarted) History.endSession(this)
        if (!Prefs.isEnabled(this)) {
            Prefs.clearLastState(this)
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
        if (delayMs >= ALARM_MIN_DELAY_MS) scheduleDiagnostics.scheduled(SystemClock.elapsedRealtime(), delayMs)
        else scheduleDiagnostics.canceled()
        worker.removeCallbacks(checkRunnable)
        worker.postDelayed(checkRunnable, delayMs)
        // Обычный таймер замирает, когда телефон засыпает с выключенным экраном.
        // Поэтому дублируем его системным будильником, который будит процессор.
        // Короткие перепроверки будильник не трогают: если телефон уснёт раньше,
        // уже поставленный будильник всё равно разбудит его к следующей проверке.
        if (delayMs >= ALARM_MIN_DELAY_MS) scheduleAlarm(delayMs)
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
        extraCoordinator.invalidate()
        checkStartedMono = SystemClock.elapsedRealtime()
        val revision = checkSchedule.begin()
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
            checkStartedMono = 0L
            releaseWakeLock()
        }
        if (acceptsRunningCheck(this)) {
            checkSchedule.afterCheck(revision, retryDelayMs ?: state.expectedIntervalSec * 1000L)
            // Re-arming replaces the pending watchdog: it wakes the phone only when checks stop landing.
            WatchdogReceiver.schedule(this, ServiceHealth.watchdogDelayMs(state.expectedIntervalSec))
        }
    }

    @Suppress("ReturnCount") // Early returns distinguish absent, switching, captive and changed Android networks.
    private fun performCheck(revision: Long): Probe {
        var network = cm.activeNetwork
        if (network == null) {
            Thread.sleep(NETWORK_GRACE_MS)
            network = cm.activeNetwork
        }
        val selected = network ?: return Probe(Status.NO_NETWORK, -1, Lang.t("Нет активной сети", "No active network"), Lang.t("Сеть: отсутствует", "Network: none"))
        val caps = cm.getNetworkCapabilities(selected)
            ?: return Probe(Status.UNKNOWN, -1, Lang.t("Сеть переключается", "Network is switching"))
        val transport = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(Lang.t("Мобильная", "Mobile"))
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
        }.joinToString(" + ").ifEmpty { Lang.t("Другая", "Other") }
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
            return Probe(Status.NO_NETWORK, -1, Lang.t("Требуется вход в сеть Wi-Fi", "Wi-Fi sign-in required"), Lang.t("Сеть: $transport; captive portal", "Network: $transport; captive portal"))
        }
        val token = extraCoordinator.begin(revision, selected.networkHandle)
        val probe = NetworkProbe(probeExecutor, extraProbeExecutor,
            { url -> selected.openConnection(url) as HttpURLConnection },
            { selected.socketFactory.createSocket() })
        val report = probe.checkPrimary()
        val checkedAt = timeStr(System.currentTimeMillis())
        val details = Lang.t("Сеть: $transport\nПроверка: $checkedAt\n", "Network: $transport\nCheck: $checkedAt\n") +
            report.results.joinToString("\n", transform = ::outcomeLine) +
            Lang.t("\nMTProto: только приветствие без авторизации. Доставка сообщений не проверяется. ",
                "\nMTProto: handshake only, without authorization. Message delivery is not checked. ") +
            Lang.t("Прокси, настроенный внутри Telegram, здесь не используется.",
                "A proxy configured inside Telegram is not used here.")
        if (selected != cm.activeNetwork) return Probe(Status.UNKNOWN, -1,
            Lang.t("Сеть изменилась во время проверки", "Network changed during the check"), details, report.results)
        return Probe(Status.valueOf(report.status), report.latencyMs, report.reason, details, report.results,
            ExtraCheck(token, probe, Prefs.extraProbeEndpoints(this)))
    }

    /** Network callbacks and primary publication share this lock, including revision validation. */
    @Synchronized private fun publishCheckedResult(result: Probe, revision: Long) {
        if (!acceptsRunningCheck(this)) return
        if (Thread.currentThread().isInterrupted) throw InterruptedException("cancelled")
        // A result from a network that has since changed is no observation: keep the prior state,
        // history and alerts. afterCheck() runs the replacement check one second later.
        if (revision != checkSchedule.revision) return
        handleResult(result)
        startExtras(result)
    }

    private fun outcomeLine(r: ProbeOutcome) = "${if (r.reachable) "✓" else "?"} ${r.endpoint}: ${r.detail}" +
        if (r.latencyMs >= 0) Lang.t(" · ${r.latencyMs} мс", " · ${r.latencyMs} ms") else ""

    @Synchronized private fun startExtras(primary: Probe) {
        val extra = primary.extraCheck ?: return
        if (extra.endpoints.isEmpty() || Thread.currentThread().isInterrupted || !acceptsExtras(extra.token)) return
        extraCoordinator.submit(extra.token, { extra.probe.checkExtras(extra.endpoints) }) { outcomes ->
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
            outcomes.joinToString("\n", transform = ::outcomeLine)
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
        LastSuccessStore.record(this, newState.status.name, newState.checkedAt)
        state = newState
        Prefs.saveLastState(
            this, newState.status, newState.checkedAt, newState.since,
            newState.latencyMs, newState.reason, newState.lastVibrationAt, newState.diagnostics, newState.expectedIntervalSec,
        )

        History.record(
            this, now,
            when (p.status) {
                Status.OK -> History.Kind.OK
                Status.NO_NETWORK -> History.Kind.OFFLINE
                Status.TG_DOWN -> History.Kind.FAIL
                Status.PARTIAL -> History.Kind.PARTIAL
                Status.UNKNOWN -> History.Kind.UNKNOWN
            },
            p.latencyMs, newState.expectedIntervalSec,
        )

        val recovery = if (p.status == Status.OK) History.recovery(this, now,
            partialAlert || Prefs.notifyFor(this, "PARTIAL")) else null
        try {
            if (p.status !in setOf(Status.TG_DOWN, Status.PARTIAL)) eventNotifications.clearOutage()
            if (changed && p.status != Status.OK) eventNotifications.clearRecovery()
            if ((changed || sound) && p.status == Status.TG_DOWN && Prefs.notifyFor(this, "TG_DOWN"))
                eventNotifications.outage(p.reason, sound, alertsAllowed)
            if ((changed || sound) && p.status == Status.PARTIAL && Prefs.notifyFor(this, "PARTIAL"))
                eventNotifications.partial(p.reason, sound, alertsAllowed)
            if (p.status == Status.TG_DOWN && !Prefs.notifyFor(this, "TG_DOWN")) eventNotifications.clearOutage()
            if (p.status == Status.PARTIAL && !Prefs.notifyFor(this, "PARTIAL")) eventNotifications.clearOutage()
            if (recovery != null) {
                if (Prefs.notifyFor(this, "RECOVERY")) eventNotifications.recovered(recovery,
                    Prefs.soundFor(this, "RECOVERY"), alertsAllowed)
                if (Prefs.vibrateOnRecovery(this) && alertsAllowed) Vibe.recovery(this)
            }
            SlowAlert.apply(this, eventNotifications, slowAlert.onCheck(p.status.name, p.latencyMs, mono),
                p.status.name, p.latencyMs)
        } catch (e: Exception) { Log.w(TAG, "recovery alert", e) }

        if (changed) {
            val downFor = recovery?.let { Lang.t(" (наблюдаемый сбой ${recoveryDurationStr(it.durationMs)})", " (observed outage ${recoveryDurationStr(it.durationMs)})") }.orEmpty()
            EventLog.add(
                this,
                when (p.status) {
                    Status.OK -> Lang.t("MTProto и веб отвечают, ${p.latencyMs} мс$downFor", "MTProto and web are responding, ${p.latencyMs} ms$downFor")
                    Status.PARTIAL -> Lang.t("Telegram частично доступен: ${p.reason}", "Telegram is partially reachable: ${p.reason}")
                    Status.TG_DOWN -> Lang.t("Telegram НЕДОСТУПЕН: ${p.reason}", "Telegram is UNREACHABLE: ${p.reason}")
                    Status.NO_NETWORK -> Lang.t("Нет интернета: ${p.reason}", "No internet: ${p.reason}")
                    Status.UNKNOWN -> Lang.t("Статус неизвестен", "Status unknown")
                }
            )
        }
        refreshNotificationStats(now)
        postNotification(newState)
        broadcastState()
        // Opt-in, credential-protected and asynchronous; never blocks or alters the published state.
        WebhookNotifier.onPublished(this, newState.status)
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

    private fun createChannels() {
        val ok = NotificationChannel(
            CHANNEL_OK, Lang.t("Telegram доступен", "Telegram is reachable"), NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = Lang.t("Постоянный значок, когда Telegram отвечает", "Persistent icon while Telegram is responding")
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val alert = NotificationChannel(
            CHANNEL_ALERT, Lang.t("Telegram недоступен", "Telegram is unreachable"), NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = Lang.t("Тревожный значок, когда связи нет", "Alert icon when there is no connection")
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ok)
        nm.createNotificationChannel(alert)
        // Старый канал больше не используем.
        try {
            nm.deleteNotificationChannel("status_v1")
        } catch (_: Exception) {
        }
    }

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
        if (destroyed || !notificationStats.needsRefresh(History.version, System.currentTimeMillis())) return
        worker.removeCallbacks(statisticsRunnable)
        worker.post(statisticsRunnable)
    }

    private fun refreshNotificationStats(now: Long = System.currentTimeMillis()) {
        val revision = History.version
        notificationStats.get(revision, now) { History.stats(this, now = now) }
        // Loading/migration/retention can advance History.version inside the first read.
        val refreshedRevision = History.version
        if (refreshedRevision != revision)
            notificationStats.get(refreshedRevision, now) { History.stats(this, now = now) }
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

    /** Всё, что видно в постоянном уведомлении: по этому ключу тик решает, нужна ли перерисовка. */
    private fun notificationView(s: State, now: Long = System.currentTimeMillis()): NotifView {
        val stale = s.isStale(now)
        val dailyStats = currentNotificationStats()
        val uptime = dailyStats.uptimePercent
        val uptimeText = if (uptime >= 0) " · Telegram ${formatPercent(uptime)}" else ""

        val notif = when {
            s.status == Status.UNKNOWN -> Notif(
                if (s.checkedAt > 0) Lang.t("Доступность не определена", "Reachability unknown") else Lang.t("Проверяю связь с Telegram…", "Checking the connection to Telegram…"),
                s.reason.ifEmpty { CHECK_URL }, CHANNEL_OK,
                R.drawable.ic_stat_wait, COLOR_OK, false
            )
            stale -> Notif(
                Lang.t("Мониторинг задерживается", "Monitoring is delayed"),
                Lang.t("Последняя проверка ${agoStr(s.checkedAt, now)}; нужен свежий результат", "Last check ${agoStr(s.checkedAt, now)}; a fresh result is needed"),
                CHANNEL_OK, R.drawable.ic_stat_wait, COLOR_STALE, false
            )
            s.status == Status.PARTIAL -> Notif(
                Lang.t("Telegram частично доступен", "Telegram is partially reachable"), s.reason, CHANNEL_OK,
                R.drawable.ic_stat_wait, COLOR_OFFLINE, false
            )
            s.status == Status.OK -> Notif(
                Lang.t("Telegram отвечает · ${s.latencyMs} мс", "Telegram is responding · ${s.latencyMs} ms"),
                Lang.t("Проверено в ${timeStr(s.checkedAt)}$uptimeText", "Checked at ${timeStr(s.checkedAt)}$uptimeText"),
                CHANNEL_OK, R.drawable.ic_stat_ok, COLOR_OK, false
            )
            s.status == Status.TG_DOWN -> Notif(
                Lang.t("Telegram НЕДОСТУПЕН", "Telegram is UNREACHABLE"),
                Lang.t("Нет связи с ${timeStr(s.since)} · ${s.reason}", "No connection since ${timeStr(s.since)} · ${s.reason}"),
                CHANNEL_ALERT, R.drawable.ic_stat_fail_blink, COLOR_FAIL, true
            )
            else -> Notif(
                Lang.t("Нет подключения к интернету", "No internet connection"),
                Lang.t("С ${timeStr(s.since)} · ${s.reason}", "Since ${timeStr(s.since)} · ${s.reason}"),
                CHANNEL_ALERT, R.drawable.ic_stat_offline_blink, COLOR_OFFLINE, true
            )
        }
        return NotifView(
            notif,
            whenMs = if (s.bad && !stale) s.since else if (s.checkedAt > 0L) s.checkedAt else now,
            chronometer = s.bad && !stale,
            progress = NotificationProgress.percent(dailyStats),
        )
    }

    private fun buildNotification(view: NotifView): Notification {
        val piFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            piFlags
        )
        val checkNow = servicePending(1, ACTION_CHECK_NOW)
        val stop = servicePending(2, ACTION_STOP)
        val n = view.notif

        val builder = Notification.Builder(this, n.channel)
            .setSmallIcon(n.icon)
            .setColor(n.color)
            .setColorized(n.colorized)
            .setContentTitle(n.title)
            .setContentText(n.text)
            .setStyle(Notification.BigTextStyle().bigText(n.text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(view.whenMs)
            .setUsesChronometer(view.chronometer)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp)
            .setDeleteIntent(checkNow)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_ok), Lang.t("Проверить", "Check"), checkNow
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_pause), Lang.t("Остановить", "Stop"), stop
                ).build()
            )
        view.progress?.let { builder.setProgress(NotificationProgress.MAX_PERCENT, it, false) }
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun currentNotificationStats(): TimeStats = notificationStats.peek() ?: EMPTY_NOTIFICATION_STATS

    private data class Notif(
        val title: String,
        val text: String,
        val channel: String,
        val icon: Int,
        val color: Int,
        val colorized: Boolean,
    )

    private data class NotifView(val notif: Notif, val whenMs: Long, val chronometer: Boolean, val progress: Int?)

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

