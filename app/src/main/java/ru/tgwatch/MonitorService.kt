package ru.tgwatch

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
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException

/**
 * Фоновая служба: раз в N секунд стучится на https://api.telegram.org/,
 * показывает результат значком в строке состояния и вибрирует,
 * если связи нет (не чаще одного раза в 5 минут).
 */
class MonitorService : Service() {

    enum class Status { UNKNOWN, OK, TG_DOWN, NO_NETWORK }

    data class State(
        val status: Status = Status.UNKNOWN,
        val checkedAt: Long = 0L,
        val since: Long = System.currentTimeMillis(),
        val latencyMs: Long = -1L,
        val reason: String = "",
        val lastVibrationAt: Long = 0L,
    ) {
        val bad get() = status == Status.TG_DOWN || status == Status.NO_NETWORK

        fun isStale(now: Long, intervalSec: Int): Boolean =
            status != Status.UNKNOWN && ProbeRules.isStale(checkedAt, now, intervalSec)
    }

    private data class Probe(val status: Status, val latencyMs: Long, val reason: String)

    companion object {
        private const val TAG = "TgWatch"

        const val ACTION_START = "ru.tgwatch.action.START"
        const val ACTION_STOP = "ru.tgwatch.action.STOP"
        const val ACTION_CHECK_NOW = "ru.tgwatch.action.CHECK_NOW"
        const val ACTION_SETTINGS = "ru.tgwatch.action.SETTINGS"
        const val ACTION_STATE_CHANGED = "ru.tgwatch.action.STATE_CHANGED"

        const val CHECK_URL = "https://api.telegram.org/"

        private val TG_URLS = listOf(
            CHECK_URL,
            "https://web.telegram.org/",
            "https://core.telegram.org/",
        )

        private val CONTROL_URLS = listOf(
            "https://ya.ru/",
            "https://mail.ru/",
            "https://vk.com/favicon.ico",
            "https://www.gstatic.com/generate_204",
            "https://connectivitycheck.gstatic.com/generate_204",
        )

        private const val CHANNEL_OK = "status_ok_v2"
        private const val CHANNEL_ALERT = "status_alert_v2"
        private const val NOTIFICATION_ID = 1
        private const val VIBRATION_GAP_MS = 5 * 60 * 1000L
        private const val TIMEOUT_MS = 5_000
        private const val RETRY_DELAY_MS = 1_500L
        private const val BAD_INTERVAL_SEC = 10
        private const val NETWORK_GRACE_MS = 4_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 60_000L

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
            if (state.status != Status.UNKNOWN) return
            Prefs.loadLastState(ctx)?.let { state = it }
        }
    }

    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var nm: NotificationManager
    private lateinit var cm: ConnectivityManager
    private lateinit var pm: PowerManager

    private val probeExecutor: ExecutorService = Executors.newFixedThreadPool(4)

    private var wakeLock: PowerManager.WakeLock? = null
    private var loopStarted = false
    private var lastVibrationMono = -1L

    @Volatile
    private var destroyed = false

    private val checkRunnable = Runnable { runCheck() }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleCheck(1_500L)
        override fun onLost(network: Network) = scheduleCheck(500L)
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
        createChannels()
        restorePersistedState(this)
        seedVibrationCooldown()

        workerThread = HandlerThread("tg-check")
        workerThread.start()
        worker = Handler(workerThread.looper)

        running = true
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

        val first = !loopStarted
        loopStarted = true
        if (first) EventLog.add(this, "Мониторинг запущен")
        if (first || action == ACTION_CHECK_NOW || action == ACTION_SETTINGS) scheduleCheck(0L)

        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        worker.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
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
        History.flush(this)
        if (!Prefs.isEnabled(this)) Prefs.clearLastState(this)
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
    }

    private fun runCheck() {
        if (destroyed) return
        acquireWakeLockForCheck()
        try {
            val result = performCheck()
            if (destroyed) return
            handleResult(result)
        } finally {
            releaseWakeLock()
        }
        val interval = Prefs.intervalSec(this)
        val next = if (state.bad) minOf(interval, BAD_INTERVAL_SEC) else interval
        scheduleCheck(next * 1000L)
    }

    private fun performCheck(): Probe {
        if (!waitForNetwork()) return noNetwork()

        val first = probeTelegram()
        if (first.status == Status.OK) return first

        val control: Future<String?>? = try {
            probeExecutor.submit(Callable { firstWorkingControl() })
        } catch (_: Exception) {
            null
        }
        try {
            Thread.sleep(RETRY_DELAY_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (!waitForNetwork()) {
            control?.cancel(true)
            return noNetwork()
        }
        val second = probeTelegram()
        if (second.status == Status.OK) {
            control?.cancel(true)
            return second
        }

        val controlHost = try {
            control?.get(TIMEOUT_MS * 2L, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            null
        }
        return if (controlHost != null) {
            Probe(Status.TG_DOWN, -1L, "${second.reason}; интернет есть ($controlHost)")
        } else {
            Probe(Status.NO_NETWORK, -1L, "сеть подключена, но интернет не отвечает")
        }
    }

    private fun noNetwork() = Probe(Status.NO_NETWORK, -1L, "нет активного подключения к сети")

    private fun firstWorkingControl(): String? {
        for (url in CONTROL_URLS) {
            if (probe(url).status == Status.OK) {
                return try {
                    URL(url).host
                } catch (_: Exception) {
                    url
                }
            }
        }
        return null
    }

    /** Параллельно стучимся во все адреса Telegram; берём первый успешный. */
    private fun probeTelegram(): Probe {
        val winner = AtomicReference<Probe?>(null)
        val futures = TG_URLS.map { url ->
            probeExecutor.submit(Callable {
                if (winner.get() != null) return@Callable null
                val p = probe(url)
                if (p.status == Status.OK) {
                    val host = try {
                        URL(url).host
                    } catch (_: Exception) {
                        url
                    }
                    val ok = if (url == CHECK_URL) p else Probe(Status.OK, p.latencyMs, "HTTP через $host")
                    winner.compareAndSet(null, ok)
                    // Отменяем остальных через общий флаг winner.
                }
                p
            })
        }
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS * 2L + 500L
        var lastFail: Probe? = null
        for (f in futures) {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0L) break
            val done = winner.get()
            if (done != null) {
                futures.forEach { it.cancel(true) }
                return done
            }
            try {
                val p = f.get(left, TimeUnit.MILLISECONDS) ?: continue
                if (p.status != Status.OK) lastFail = p
            } catch (_: Exception) {
            }
        }
        winner.get()?.let { return it }
        futures.forEach { it.cancel(true) }
        return lastFail ?: Probe(Status.TG_DOWN, -1L, "нет ответа от серверов Telegram")
    }

    private fun waitForNetwork(): Boolean {
        if (hasInternet()) return true
        try {
            Thread.sleep(NETWORK_GRACE_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return hasInternet()
    }

    private fun probe(url: String): Probe {
        val head = probeOnce(url, "HEAD")
        if (head.status == Status.OK) return head
        // 405/501 и обрыв на HEAD — частая история; GET надёжнее.
        if (head.reason.contains("HTTP 405") ||
            head.reason.contains("HTTP 501") ||
            head.reason.contains("соединение оборвано") ||
            head.reason.contains("SocketException")
        ) {
            return probeOnce(url, "GET")
        }
        return head
    }

    private fun probeOnce(url: String, method: String): Probe {
        var conn: HttpURLConnection? = null
        val started = SystemClock.elapsedRealtime()
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.requestMethod = method
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.setRequestProperty("User-Agent", "TgWatch/1.3 (Android)")
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Connection", "keep-alive")
            val code = c.responseCode
            val ms = SystemClock.elapsedRealtime() - started
            try {
                (if (code >= 400) c.errorStream else c.inputStream)?.close()
            } catch (_: Exception) {
            }
            conn = null
            if (ProbeRules.isReachableHttpCode(code)) {
                Probe(Status.OK, ms, "HTTP $code")
            } else {
                Probe(Status.TG_DOWN, -1L, ProbeRules.describeHttpFailure(code))
            }
        } catch (e: Exception) {
            Probe(Status.TG_DOWN, -1L, describe(e))
        } finally {
            conn?.disconnect()
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is SocketTimeoutException -> "сервер не ответил за ${TIMEOUT_MS / 1000} с"
        is UnknownHostException -> "не удалось найти адрес (DNS)"
        is ConnectException -> "в соединении отказано"
        is SSLException -> "ошибка защищённого соединения (TLS)"
        is SocketException -> "соединение оборвано" + (e.message?.let { " ($it)" } ?: "")
        else -> e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
    }

    private fun hasInternet(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) return false
        return true
    }

    private fun handleResult(p: Probe) {
        val now = System.currentTimeMillis()
        val prev = state
        val changed = prev.status != p.status
        var lastVibration = prev.lastVibrationAt

        val shouldAlarm = when (p.status) {
            Status.TG_DOWN -> Prefs.vibrateEnabled(this)
            Status.NO_NETWORK -> Prefs.vibrateOffline(this)
            else -> false
        }
        if (shouldAlarm && !Prefs.inQuietHoursNow(this, now)) {
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
        if (recovered && Prefs.vibrateOnRecovery(this) && !Prefs.inQuietHoursNow(this, now)) {
            try {
                Vibe.recovery(this)
            } catch (e: Exception) {
                Log.w(TAG, "vibrate recovery", e)
            }
        }

        val newState = State(
            status = p.status,
            checkedAt = now,
            since = if (changed) now else prev.since,
            latencyMs = p.latencyMs,
            reason = p.reason,
            lastVibrationAt = lastVibration,
        )
        state = newState
        Prefs.saveLastState(
            this, newState.status, newState.checkedAt, newState.since,
            newState.latencyMs, newState.reason, newState.lastVibrationAt,
        )

        History.record(
            this, now,
            when (p.status) {
                Status.OK -> History.Kind.OK
                Status.NO_NETWORK -> History.Kind.OFFLINE
                Status.TG_DOWN, Status.UNKNOWN -> History.Kind.FAIL
            },
            p.latencyMs,
        )

        if (changed) {
            val downFor = if (prev.bad && prev.checkedAt > 0L) " (не было ${durationStr(now - prev.since)})" else ""
            EventLog.add(
                this,
                when (p.status) {
                    Status.OK -> "Telegram доступен, ответ за ${p.latencyMs} мс$downFor"
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
        val checkNow = PendingIntent.getService(
            this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_CHECK_NOW), piFlags
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, MonitorService::class.java).setAction(ACTION_STOP), piFlags
        )

        val now = System.currentTimeMillis()
        val stale = s.isStale(now, Prefs.intervalSec(this))
        val uptime = History.uptimePercent(this)
        val uptimeText = if (uptime >= 0) " · Telegram ${formatPercent(uptime)}" else ""

        val (title, text, channel, icon, color, colorized) = when {
            s.status == Status.UNKNOWN -> Notif(
                "Проверяю связь с Telegram…", CHECK_URL, CHANNEL_OK,
                R.drawable.ic_stat_wait, COLOR_OK, false
            )
            stale && s.status == Status.OK -> Notif(
                "Последний раз: доступен · ${s.latencyMs} мс",
                "Проверено ${agoStr(s.checkedAt, now)} — жду свежую проверку$uptimeText",
                CHANNEL_OK, R.drawable.ic_stat_wait, COLOR_STALE, false
            )
            stale && s.status == Status.TG_DOWN -> Notif(
                "Последний раз: НЕДОСТУПЕН",
                "Проверено ${agoStr(s.checkedAt, now)} · ${s.reason}",
                CHANNEL_ALERT, R.drawable.ic_stat_fail_blink, COLOR_FAIL, true
            )
            stale && s.status == Status.NO_NETWORK -> Notif(
                "Последний раз: нет интернета",
                "Проверено ${agoStr(s.checkedAt, now)} · ${s.reason}",
                CHANNEL_ALERT, R.drawable.ic_stat_offline_blink, COLOR_OFFLINE, true
            )
            s.status == Status.OK -> Notif(
                "Telegram доступен · ${s.latencyMs} мс",
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
    private fun acquireWakeLockForCheck() {
        if (!Prefs.keepAwake(this)) return
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
