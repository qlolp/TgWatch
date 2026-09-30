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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        val checkedAt: Long = 0L,                       // когда была последняя проверка
        val since: Long = System.currentTimeMillis(),   // с какого момента держится текущий статус
        val latencyMs: Long = -1L,                      // время ответа сервера
        val reason: String = "",                        // почему недоступен
        val lastVibrationAt: Long = 0L,                 // когда последний раз вибрировали
    )

    private data class Probe(val status: Status, val latencyMs: Long, val reason: String)

    companion object {
        private const val TAG = "TgWatch"

        const val ACTION_START = "ru.tgwatch.action.START"
        const val ACTION_STOP = "ru.tgwatch.action.STOP"
        const val ACTION_CHECK_NOW = "ru.tgwatch.action.CHECK_NOW"
        const val ACTION_SETTINGS = "ru.tgwatch.action.SETTINGS"

        /** Что проверяем. Любой HTTP-ответ от сервера = Telegram доступен. */
        const val CHECK_URL = "https://api.telegram.org/"

        private const val CHANNEL_ID = "status_v1"
        private const val NOTIFICATION_ID = 1

        /** Вибрировать не чаще, чем раз в 5 минут. */
        private const val VIBRATION_GAP_MS = 5 * 60 * 1000L

        private const val TIMEOUT_MS = 10_000
        private const val RETRY_DELAY_MS = 3_000L
        private const val MAX_LOG = 50

        private val COLOR_OK = Color.rgb(0x2A, 0xAB, 0xEE)
        private val COLOR_FAIL = Color.rgb(0xE5, 0x39, 0x35)

        /** Работает ли служба сейчас (для экрана приложения). */
        @Volatile
        var running = false
            private set

        /** Последний известный статус (для экрана приложения). */
        @Volatile
        var state = State()
            private set

        /** Журнал смен статуса, новые записи сверху. */
        @Volatile
        var log: List<String> = emptyList()
            private set

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, MonitorService::class.java).setAction(ACTION_START))
        }

        fun send(ctx: Context, action: String) {
            ctx.startService(Intent(ctx, MonitorService::class.java).setAction(action))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }

        @Synchronized
        private fun addLog(message: String) {
            val time = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date())
            log = (listOf("$time  $message") + log).take(MAX_LOG)
        }
    }

    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var nm: NotificationManager
    private lateinit var cm: ConnectivityManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var loopStarted = false
    private var lastVibrationMono = -1L

    @Volatile
    private var destroyed = false

    private val checkRunnable = Runnable { runCheck() }

    /** Как только сеть пропала или появилась — проверяем сразу, не дожидаясь интервала. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleCheck(2_000L)
        override fun onLost(network: Network) = scheduleCheck(1_000L)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        createChannel()

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
        // Android требует показать уведомление сразу после запуска службы.
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

        applyWakeLock()

        val first = !loopStarted
        loopStarted = true
        if (first) addLog("Мониторинг запущен")
        if (first || action == ACTION_CHECK_NOW || action == ACTION_SETTINGS) scheduleCheck(0L)

        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        worker.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
        try {
            cm.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterNetworkCallback", e)
        }
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        nm.cancel(NOTIFICATION_ID)
        if (loopStarted) addLog("Мониторинг остановлен")
        state = State()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- проверка

    private fun scheduleCheck(delayMs: Long) {
        if (destroyed) return
        worker.removeCallbacks(checkRunnable)
        worker.postDelayed(checkRunnable, delayMs)
    }

    private fun runCheck() {
        if (destroyed) return
        val result = performCheck()
        if (destroyed) return
        handleResult(result)
        scheduleCheck(Prefs.intervalSec(this) * 1000L)
    }

    private fun performCheck(): Probe {
        if (!hasInternet()) return Probe(Status.NO_NETWORK, -1L, "нет активного подключения к сети")

        val first = probe()
        if (first.status == Status.OK) return first

        // Одна неудача может быть случайной — перепроверяем через 3 секунды.
        try {
            Thread.sleep(RETRY_DELAY_MS)
        } catch (ignored: InterruptedException) {
        }
        if (!hasInternet()) return Probe(Status.NO_NETWORK, -1L, "нет активного подключения к сети")
        return probe()
    }

    /** Один HTTPS-запрос к api.telegram.org. */
    private fun probe(): Probe {
        var conn: HttpURLConnection? = null
        val started = SystemClock.elapsedRealtime()
        return try {
            val c = URL(CHECK_URL).openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.setRequestProperty("Connection", "close")
            c.setRequestProperty("User-Agent", "TgWatch/1.0 (Android)")
            val code = c.responseCode
            val ms = SystemClock.elapsedRealtime() - started
            if (code in 100..599) {
                Probe(Status.OK, ms, "HTTP $code")
            } else {
                Probe(Status.TG_DOWN, -1L, "некорректный ответ сервера")
            }
        } catch (e: Exception) {
            Probe(Status.TG_DOWN, -1L, describe(e))
        } finally {
            conn?.disconnect()
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is SocketTimeoutException -> "сервер не ответил за ${TIMEOUT_MS / 1000} с"
        is UnknownHostException -> "не удалось найти адрес api.telegram.org (DNS)"
        is ConnectException -> "в соединении отказано"
        is SSLException -> "ошибка защищённого соединения (TLS)"
        is SocketException -> "соединение оборвано" + (e.message?.let { " ($it)" } ?: "")
        else -> e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
    }

    private fun hasInternet(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun handleResult(p: Probe) {
        val now = System.currentTimeMillis()
        val prev = state
        val changed = prev.status != p.status
        var lastVibration = prev.lastVibrationAt

        if (p.status != Status.OK) {
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

        val newState = State(
            status = p.status,
            checkedAt = now,
            since = if (changed) now else prev.since,
            latencyMs = p.latencyMs,
            reason = p.reason,
            lastVibrationAt = lastVibration,
        )
        state = newState

        if (changed) {
            addLog(
                when (p.status) {
                    Status.OK -> "Telegram доступен (${p.latencyMs} мс)"
                    Status.TG_DOWN -> "Telegram НЕДОСТУПЕН: ${p.reason}"
                    Status.NO_NETWORK -> "Нет интернета"
                    Status.UNKNOWN -> "Статус неизвестен"
                }
            )
        }
        postNotification(newState)
    }

    // ------------------------------------------------------------ уведомление

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Статус Telegram", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Постоянный значок с состоянием связи с Telegram"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
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
        // Если службу остановили, пока шла проверка, — убираем значок.
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

        val (title, text) = when (s.status) {
            Status.OK -> "Telegram доступен" to
                "Ответ за ${s.latencyMs} мс · проверено в ${timeStr(s.checkedAt)}"
            Status.TG_DOWN -> "Telegram НЕДОСТУПЕН" to
                "Нет связи с ${timeStr(s.since)} · ${s.reason}"
            Status.NO_NETWORK -> "Нет подключения к интернету" to
                "С ${timeStr(s.since)}"
            Status.UNKNOWN -> "Проверяю связь с Telegram…" to CHECK_URL
        }
        val bad = s.status == Status.TG_DOWN || s.status == Status.NO_NETWORK

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (bad) R.drawable.ic_stat_fail else R.drawable.ic_stat_ok)
            .setColor(if (bad) COLOR_FAIL else COLOR_OK)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(s.since)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openApp)
            // Если уведомление смахнули — служба перепроверит связь и вернёт значок.
            .setDeleteIntent(checkNow)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_ok), "Проверить", checkNow
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_fail), "Остановить", stop
                ).build()
            )
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    // --------------------------------------------------------------- wake lock

    private fun applyWakeLock() {
        if (Prefs.keepAwake(this)) {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TgWatch:monitor").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } else {
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
