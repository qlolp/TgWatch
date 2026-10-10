package ru.tgwatch

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.net.HttpURLConnection
import java.util.concurrent.ExecutorService

/**
 * Фоновая служба: раз в N секунд стучится на https://api.telegram.org/,
 * показывает результат значком в строке состояния и вибрирует,
 * если связи нет (не чаще одного раза в 5 минут).
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

        fun isStale(now: Long, intervalSec: Int, slowExpected: Boolean = false): Boolean =
            checkedAt > 0L && (now < checkedAt || if (checkedElapsed > 0L)
                ProbeRules.isStale(checkedElapsed, SystemClock.elapsedRealtime(), expectedIntervalSec, false)
                else ProbeRules.isStale(checkedAt, now, expectedIntervalSec, false))
    }

    private data class Probe(val status: Status, val latencyMs: Long, val reason: String, val diagnostics: String = "")

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
        private const val VIBRATION_GAP_MS = 5 * 60 * 1000L
        private const val NETWORK_GRACE_MS = 4_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 60_000L

        /** Будильник ставим только на настоящие паузы, а не на мгновенные перепроверки. */
        private const val ALARM_MIN_DELAY_MS = 5_000L

        private val COLOR_OK = Color.rgb(0x22, 0x9E, 0xD9)
        private val COLOR_FAIL = Color.rgb(0xE5, 0x39, 0x35)
        private val COLOR_OFFLINE = Color.rgb(0xF5, 0x7C, 0x00)
        private val COLOR_STALE = Color.rgb(0x8A, 0x96, 0xA3)

        @Volatile
        var running = false
            private set

        @Volatile
        var state = State()
            private set

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, MonitorService::class.java).setAction(ACTION_START))
        }

        fun send(ctx: Context, action: String) {
            val intent = Intent(ctx, MonitorService::class.java).setAction(action)
            if (running) {
                ctx.startService(intent)
            } else {
                ctx.startForegroundService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }

        fun restorePersistedState(ctx: Context) {
            if (state.checkedAt > 0L) return
            Prefs.loadLastState(ctx)?.let { state = it }
        }
    }

    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var nm: NotificationManager
    private lateinit var cm: ConnectivityManager
    private lateinit var pm: PowerManager
    private lateinit var am: AlarmManager

    /**
     * Ограниченный пул; один зависший адрес не занимает новые потоки при повторных проверках.
     */
    private val probeExecutor: ExecutorService = NetworkProbe.executor()

    private var wakeLock: PowerManager.WakeLock? = null
    private var loopStarted = false
    private var lastVibrationMono = -1L
    @Volatile private var lastProgressMono = 0L
    @Volatile private var checkStartedMono = 0L
    @Volatile private var networkRevision = 0L
    private val healthHandler = Handler(android.os.Looper.getMainLooper())
    private val healthTick = object : Runnable {
        override fun run() {
            if (destroyed) return
            postNotification(state)
            StatusWidget.updateAll(this@MonitorService)
            ensureProgress()
            healthHandler.postDelayed(this, 15_000L)
        }
    }
    private fun ensureProgress() {
        val now = SystemClock.elapsedRealtime()
        // Checks have a 12-second total network budget. If overdue, cancel wait and retry.
        if (checkStartedMono > 0 && now - checkStartedMono > 30_000L) {
            workerThread.interrupt()
        } else if (checkStartedMono == 0L && ServiceHealth.isOverdue(lastProgressMono, now, state.expectedIntervalSec)) {
            scheduleCheck(0L)
        }
    }

    @Volatile
    private var destroyed = false

    private val checkRunnable = Runnable { runCheck() }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { networkRevision++; scheduleCheck(1_500L) }
        override fun onLost(network: Network) { networkRevision++; scheduleCheck(500L) }
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                scheduleCheck(800L)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        createChannels()
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val action = intent?.action
        if (action == ACTION_STOP) Prefs.setEnabled(this, false)
        if (!Prefs.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_HEALTH) ensureProgress()
        val first = !loopStarted
        loopStarted = true
        if (first) EventLog.add(this, "Мониторинг запущен")
        if (action == ACTION_ALARM) {
            // Будильник разбудил процессор лишь на мгновение: держим его, пока не пройдёт проверка.
            acquireWakeLockForCheck(force = true)
        }
        if (first || action == ACTION_CHECK_NOW || action == ACTION_SETTINGS || action == ACTION_ALARM) {
            scheduleCheck(0L)
        }

        return START_STICKY
    }

    @Synchronized override fun onDestroy() {
        destroyed = true
        healthHandler.removeCallbacksAndMessages(null)
        running = false
        worker.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
        cancelAlarm()
        probeExecutor.shutdownNow()
        try {
            cm.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterNetworkCallback", e)
        }
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        nm.cancel(NOTIFICATION_ID)
        if (loopStarted) EventLog.add(this, "Мониторинг остановлен")
        History.endSession(this)
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
        if (destroyed) return
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

    private fun scheduleAlarm(delayMs: Long) {
        if (!Prefs.keepAwake(this)) {
            cancelAlarm()
            return
        }
        val at = SystemClock.elapsedRealtime() + delayMs
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

    private fun cancelAlarm() {
        try {
            am.cancel(alarmIntent())
        } catch (e: Exception) {
            Log.w(TAG, "cancel alarm", e)
        }
    }

    private fun runCheck() {
        if (destroyed) return
        // An interrupt from the health monitor must not poison the following check.
        Thread.interrupted()
        checkStartedMono = SystemClock.elapsedRealtime()
        val revision = networkRevision
        acquireWakeLockForCheck()
        try {
            val result = performCheck()
            if (destroyed) return
            if (revision == networkRevision) handleResult(result)
            else handleResult(Probe(Status.UNKNOWN, -1, "Сеть изменилась во время проверки", result.diagnostics))
            lastProgressMono = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            if (!destroyed) handleResult(Probe(Status.UNKNOWN, -1, "Проверка не завершена: ${describeProbeError(e)}"))
            lastProgressMono = SystemClock.elapsedRealtime()
        } finally {
            checkStartedMono = 0L
            releaseWakeLock()
        }
        scheduleCheck(if (revision != networkRevision) 1_000L else Prefs.effectiveIntervalSec(this, state.bad, !pm.isInteractive) * 1000L)
    }

    private fun performCheck(): Probe {
        var network = cm.activeNetwork
        if (network == null) {
            Thread.sleep(NETWORK_GRACE_MS)
            network = cm.activeNetwork
        }
        val selected = network ?: return Probe(Status.NO_NETWORK, -1, "Нет активной сети", "Сеть: отсутствует")
        val caps = cm.getNetworkCapabilities(selected)
            ?: return Probe(Status.UNKNOWN, -1, "Сеть переключается")
        val transport = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("Мобильная")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
        }.joinToString(" + ").ifEmpty { "Другая" }
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
            return Probe(Status.NO_NETWORK, -1, "Требуется вход в сеть Wi-Fi", "Сеть: $transport; captive portal")
        }
        val report = NetworkProbe(probeExecutor,
            { url -> selected.openConnection(url) as HttpURLConnection },
            { selected.socketFactory.createSocket() }).check()
        val details = "Сеть: $transport\nПроверка: ${timeStr(System.currentTimeMillis())}\n" +
            report.results.joinToString("\n") { r ->
                "${if (r.reachable) "✓" else "?"} ${r.endpoint}: ${r.detail}" +
                    if (r.latencyMs >= 0) " · ${r.latencyMs} мс" else ""
            } + "\nMTProto: только приветствие без авторизации. Доставка сообщений не проверяется. " +
            "Прокси, настроенный внутри Telegram, здесь не используется."
        if (selected != cm.activeNetwork) return Probe(Status.UNKNOWN, -1, "Сеть изменилась во время проверки", details)
        return Probe(Status.valueOf(report.status), report.latencyMs, report.reason, details)
    }

    @Synchronized private fun handleResult(p: Probe) {
        if (destroyed) return
        val now = System.currentTimeMillis()
        val prev = state
        val changed = prev.status != p.status
        var lastVibration = prev.lastVibrationAt

        val shouldAlarm = when (p.status) {
            Status.TG_DOWN -> Prefs.vibrateEnabled(this)
            Status.NO_NETWORK -> Prefs.vibrateOffline(this)
            else -> false
        }
        if (shouldAlarm && Prefs.alertsAllowed(this, now)) {
            val mono = SystemClock.elapsedRealtime()
            if (lastVibrationMono < 0L || mono - lastVibrationMono >= VIBRATION_GAP_MS) {
                lastVibrationMono = mono
                lastVibration = now
                try {
                    Vibe.alarm(this)
                } catch (e: Exception) {
                    Log.w(TAG, "vibrate", e)
                }
            }
        }

        val recovered = changed && p.status == Status.OK && prev.bad
        if (recovered && Prefs.vibrateOnRecovery(this) && Prefs.alertsAllowed(this, now)) {
            try {
                Vibe.recovery(this)
            } catch (e: Exception) {
                Log.w(TAG, "vibrate recovery", e)
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
            expectedIntervalSec = Prefs.effectiveIntervalSec(this, p.status == Status.TG_DOWN || p.status == Status.NO_NETWORK, !pm.isInteractive),
        )
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

        if (changed) {
            val downFor = if (prev.bad && prev.checkedAt > 0L) " (не было ${durationStr(now - prev.since)})" else ""
            EventLog.add(
                this,
                when (p.status) {
                    Status.OK -> "MTProto и веб отвечают, ${p.latencyMs} мс$downFor"
                    Status.PARTIAL -> "Telegram частично доступен: ${p.reason}"
                    Status.TG_DOWN -> "Telegram НЕДОСТУПЕН: ${p.reason}"
                    Status.NO_NETWORK -> "Нет интернета: ${p.reason}"
                    Status.UNKNOWN -> "Статус неизвестен"
                }
            )
        }
        postNotification(newState)
        broadcastState()
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
            CHANNEL_OK, "Telegram доступен", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Постоянный значок, когда Telegram отвечает"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val alert = NotificationChannel(
            CHANNEL_ALERT, "Telegram недоступен", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Тревожный значок, когда связи нет"
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
        val notification = buildNotification(state)
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            false
        }
    }

    private fun postNotification(s: State) {
        if (destroyed) return
        try {
            nm.notify(NOTIFICATION_ID, buildNotification(s))
        } catch (e: Exception) {
            Log.w(TAG, "notify", e)
        }
        if (destroyed) nm.cancel(NOTIFICATION_ID)
    }

    private fun buildNotification(s: State): Notification {
        val piFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            piFlags
        )
        val checkNow = servicePending(1, ACTION_CHECK_NOW)
        val stop = servicePending(2, ACTION_STOP)

        val now = System.currentTimeMillis()
        val stale = s.isStale(now, Prefs.intervalSec(this), Prefs.keepAwake(this))
        val uptime = History.uptimePercent(this)
        val uptimeText = if (uptime >= 0) " · Telegram ${formatPercent(uptime)}" else ""

        val (title, text, channel, icon, color, colorized) = when {
            s.status == Status.UNKNOWN -> Notif(
                if (s.checkedAt > 0) "Доступность не определена" else "Проверяю связь с Telegram…",
                s.reason.ifEmpty { CHECK_URL }, CHANNEL_OK,
                R.drawable.ic_stat_wait, COLOR_OK, false
            )
            stale -> Notif(
                "Мониторинг задерживается",
                "Последняя проверка ${agoStr(s.checkedAt, now)}; нужен свежий результат",
                CHANNEL_OK, R.drawable.ic_stat_wait, COLOR_STALE, false
            )
            s.status == Status.PARTIAL -> Notif(
                "Telegram частично доступен", s.reason, CHANNEL_OK,
                R.drawable.ic_stat_wait, COLOR_OFFLINE, false
            )
            s.status == Status.OK -> Notif(
                "Telegram отвечает · ${s.latencyMs} мс",
                "Проверено в ${timeStr(s.checkedAt)}$uptimeText",
                CHANNEL_OK, R.drawable.ic_stat_ok, COLOR_OK, false
            )
            s.status == Status.TG_DOWN -> Notif(
                "Telegram НЕДОСТУПЕН",
                "Нет связи с ${timeStr(s.since)} · ${s.reason}",
                CHANNEL_ALERT, R.drawable.ic_stat_fail_blink, COLOR_FAIL, true
            )
            else -> Notif(
                "Нет подключения к интернету",
                "С ${timeStr(s.since)} · ${s.reason}",
                CHANNEL_ALERT, R.drawable.ic_stat_offline_blink, COLOR_OFFLINE, true
            )
        }

        val builder = Notification.Builder(this, channel)
            .setSmallIcon(icon)
            .setColor(color)
            .setColorized(colorized)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(if (s.bad && !stale) s.since else if (s.checkedAt > 0L) s.checkedAt else System.currentTimeMillis())
            .setUsesChronometer(s.bad && !stale)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp)
            .setDeleteIntent(checkNow)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_ok), "Проверить", checkNow
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_pause), "Остановить", stop
                ).build()
            )
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private data class Notif(
        val title: String,
        val text: String,
        val channel: String,
        val icon: Int,
        val color: Int,
        val colorized: Boolean,
    )

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

/** 99,8 % */
fun formatPercent(p: Double): String =
    if (p >= 99.95 && p < 100.0) "99,9 %"
    else String.format(java.util.Locale("ru"), "%.1f %%", p).replace(",0 %", " %")

