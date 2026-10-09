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
    ) {
        val bad get() = status == Status.TG_DOWN || status == Status.NO_NETWORK
    }

    private data class Probe(val status: Status, val latencyMs: Long, val reason: String)

    companion object {
        private const val TAG = "TgWatch"

        const val ACTION_START = "ru.tgwatch.action.START"
        const val ACTION_STOP = "ru.tgwatch.action.STOP"
        const val ACTION_CHECK_NOW = "ru.tgwatch.action.CHECK_NOW"
        const val ACTION_SETTINGS = "ru.tgwatch.action.SETTINGS"

        /** Плитка быстрых настроек слушает эту рассылку и обновляется сразу. */
        const val ACTION_STATE_CHANGED = "ru.tgwatch.action.STATE_CHANGED"

        /** Что проверяем. Любой HTTP-ответ от сервера = Telegram доступен. */
        const val CHECK_URL = "https://api.telegram.org/"

        /**
         * Запасные адреса Telegram: если api.telegram.org молчит, а web/core отвечают,
         * считаем, что Telegram в целом доступен (локальный сбой одного хоста).
         */
        private val TG_URLS = listOf(
            CHECK_URL,
            "https://web.telegram.org/",
            "https://core.telegram.org/",
        )

        /**
         * Контрольные адреса: если Telegram молчит, а они отвечают, значит,
         * интернет работает и недоступен именно Telegram.
         */
        private val CONTROL_URLS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://ya.ru/",
            "https://connectivitycheck.gstatic.com/generate_204",
        )

        private const val CHANNEL_ID = "status_v1"
        private const val NOTIFICATION_ID = 1

        /** Вибрировать не чаще, чем раз в 5 минут. */
        private const val VIBRATION_GAP_MS = 5 * 60 * 1000L

        /** Сколько ждём ответа. Живой сервер отвечает за доли секунды. */
        private const val TIMEOUT_MS = 5_000

        /** Через сколько перепроверяем после первой неудачи. */
        private const val RETRY_DELAY_MS = 1_500L

        /** Пока связи нет, проверяем не реже этого, чтобы быстро заметить, что она вернулась. */
        private const val BAD_INTERVAL_SEC = 10

        /**
         * При переключении Wi‑Fi ↔ мобильная сеть интернет пропадает на пару секунд.
         * Столько ждём, прежде чем решить, что сети действительно нет.
         */
        private const val NETWORK_GRACE_MS = 4_000L

        private val COLOR_OK = Color.rgb(0x22, 0x9E, 0xD9)
        private val COLOR_FAIL = Color.rgb(0xE5, 0x39, 0x35)
        private val COLOR_OFFLINE = Color.rgb(0xF5, 0x7C, 0x00)

        /** Работает ли служба сейчас (для экрана приложения). */
        @Volatile
        var running = false
            private set

        /** Последний известный статус (для экрана приложения). */
        @Volatile
        var state = State()
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

        /** Восстановить последний статус из настроек (после смерти процесса). */
        fun restorePersistedState(ctx: Context) {
            if (state.status != Status.UNKNOWN) return
            Prefs.loadLastState(ctx)?.let { state = it }
        }
    }

    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var nm: NotificationManager
    private lateinit var cm: ConnectivityManager

    /** Пул для параллельных контрольных и запасных проверок. */
    private val probeExecutor: ExecutorService = Executors.newFixedThreadPool(3)

    private var wakeLock: PowerManager.WakeLock? = null
    private var loopStarted = false
    private var lastVibrationMono = -1L

    @Volatile
    private var destroyed = false

    private val checkRunnable = Runnable { runCheck() }

    /** Как только сеть пропала или появилась — проверяем сразу, не дожидаясь интервала. */
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
        createChannel()
        restorePersistedState(this)

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
        Prefs.clearLastState(this)
        state = State()
        broadcastState()
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
        val interval = Prefs.intervalSec(this)
        val next = if (state.bad) minOf(interval, BAD_INTERVAL_SEC) else interval
        scheduleCheck(next * 1000L)
    }

    private fun performCheck(): Probe {
        if (!waitForNetwork()) return noNetwork()

        val first = probeTelegram()
        if (first.status == Status.OK) return first

        // Одна неудача может быть случайной — перепроверяем, а заодно (параллельно)
        // смотрим, работает ли остальной интернет.
        val control: Future<Boolean>? = try {
            probeExecutor.submit(Callable { CONTROL_URLS.any { probe(it).status == Status.OK } })
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

        val internetOk = try {
            control?.get(TIMEOUT_MS * 3L, TimeUnit.MILLISECONDS) ?: true
        } catch (_: Exception) {
            false
        }
        return if (internetOk) {
            Probe(Status.TG_DOWN, -1L, second.reason)
        } else {
            Probe(Status.NO_NETWORK, -1L, "сеть подключена, но интернет не отвечает")
        }
    }

    private fun noNetwork() = Probe(Status.NO_NETWORK, -1L, "нет активного подключения к сети")

    /** Проверяем все адреса Telegram; достаточно одного ответа. */
    private fun probeTelegram(): Probe {
        var lastFail: Probe? = null
        for (url in TG_URLS) {
            val p = probe(url)
            if (p.status == Status.OK) {
                val host = try {
                    URL(url).host
                } catch (_: Exception) {
                    url
                }
                return if (url == CHECK_URL) p else Probe(Status.OK, p.latencyMs, "HTTP через $host")
            }
            lastFail = p
        }
        return lastFail ?: Probe(Status.TG_DOWN, -1L, "нет ответа от серверов Telegram")
    }

    /** Есть ли сеть; если нет — даём пару секунд на переключение между Wi‑Fi и мобильной сетью. */
    private fun waitForNetwork(): Boolean {
        if (hasInternet()) return true
        try {
            Thread.sleep(NETWORK_GRACE_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return hasInternet()
    }

    /**
     * Один лёгкий HTTPS-запрос. Сначала HEAD (без тела), если сервер не умеет —
     * повторяем GET и сразу закрываем поток.
     */
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
            c.setRequestProperty("User-Agent", "TgWatch/1.2 (Android)")
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Connection", "keep-alive")
            val code = c.responseCode
            val ms = SystemClock.elapsedRealtime() - started
            // Закрываем ответ, но не рвём соединение: оно вернётся в пул, и следующая
            // проверка обойдётся без нового TLS-рукопожатия — быстрее и экономнее.
            try {
                (if (code >= 400) c.errorStream else c.inputStream)?.close()
            } catch (_: Exception) {
            }
            conn = null
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
        // Captive portal (гостиничный Wi‑Fi без логина) — это не настоящий интернет.
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) return false
        // VALIDATED — Android уже убедился, что сеть ходит в интернет.
        // Если флага ещё нет (первые секунды после подключения), всё равно пробуем.
        return true
    }

    private fun handleResult(p: Probe) {
        val now = System.currentTimeMillis()
        val prev = state
        val changed = prev.status != p.status
        var lastVibration = prev.lastVibrationAt

        if (p.status != Status.OK && Prefs.vibrateEnabled(this)) {
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

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Статус Telegram", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Постоянный значок с состоянием связи с Telegram"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
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

        val uptime = History.uptimePercent(this)
        val uptimeText = if (uptime >= 0) " · за сутки ${formatPercent(uptime)}" else ""
        val (title, text) = when (s.status) {
            Status.OK -> "Telegram доступен · ${s.latencyMs} мс" to
                "Проверено в ${timeStr(s.checkedAt)}$uptimeText"
            Status.TG_DOWN -> "Telegram НЕДОСТУПЕН" to
                "Нет связи с ${timeStr(s.since)} · ${s.reason}. Остальной интернет работает."
            Status.NO_NETWORK -> "Нет подключения к интернету" to
                "С ${timeStr(s.since)} · ${s.reason}"
            Status.UNKNOWN -> "Проверяю связь с Telegram…" to CHECK_URL
        }

        val builder = Notification.Builder(this, CHANNEL_ID)
            // Когда связи нет — значок в строке состояния мигает,
            // а само уведомление в шторке целиком закрашивается цветом тревоги.
            .setSmallIcon(
                when (s.status) {
                    Status.TG_DOWN -> R.drawable.ic_stat_fail_blink
                    Status.NO_NETWORK -> R.drawable.ic_stat_offline_blink
                    else -> R.drawable.ic_stat_ok
                }
            )
            .setColor(
                when (s.status) {
                    Status.TG_DOWN -> COLOR_FAIL
                    Status.NO_NETWORK -> COLOR_OFFLINE
                    else -> COLOR_OK
                }
            )
            .setColorized(s.bad)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            // Пока связи нет, в уведомлении тикает секундомер с момента потери связи.
            .setWhen(if (s.bad) s.since else if (s.checkedAt > 0L) s.checkedAt else System.currentTimeMillis())
            .setUsesChronometer(s.bad)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
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
                    Icon.createWithResource(this, R.drawable.ic_stat_pause), "Остановить", stop
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

/** 99,8 % */
fun formatPercent(p: Double): String =
    if (p >= 99.95 && p < 100.0) "99,9 %"
    else String.format(java.util.Locale("ru"), "%.1f %%", p).replace(",0 %", " %")
