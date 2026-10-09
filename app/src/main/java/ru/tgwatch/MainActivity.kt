package ru.tgwatch

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Экран приложения: статус, график, кнопки и настройки. */
class MainActivity : Activity() {

    private lateinit var statusCard: View
    private lateinit var ivStatus: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvSince: TextView
    private lateinit var tvReason: TextView
    private lateinit var tvLatency: TextView
    private lateinit var tvUptime: TextView
    private lateinit var tvChecked: TextView
    private lateinit var chart: ChartView
    private lateinit var btnToggle: Button
    private lateinit var btnCheck: Button
    private lateinit var rgInterval: RadioGroup
    private lateinit var swKeepAwake: Switch
    private lateinit var tvBattery: TextView
    private lateinit var btnBattery: Button
    private lateinit var tvNotif: TextView
    private lateinit var btnNotif: Button
    private lateinit var btnVibe: Button
    private lateinit var tvLog: TextView

    private val ui = Handler(Looper.getMainLooper())

    /** Обновляем экран раз в секунду, пока он открыт. */
    private val ticker = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000L)
        }
    }

    private val intervalButtons by lazy {
        mapOf(10 to R.id.rb10, 15 to R.id.rb15, 30 to R.id.rb30, 60 to R.id.rb60, 120 to R.id.rb120)
    }

    private var cardColor = 0
    private var colorAnimator: ValueAnimator? = null
    private var shownHistoryVersion = -1L
    private var shownLog: List<String>? = null
    private var shownChartMinute = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusCard = findViewById(R.id.statusCard)
        ivStatus = findViewById(R.id.ivStatus)
        tvTitle = findViewById(R.id.tvTitle)
        tvSince = findViewById(R.id.tvSince)
        tvReason = findViewById(R.id.tvReason)
        tvLatency = findViewById(R.id.tvLatency)
        tvUptime = findViewById(R.id.tvUptime)
        tvChecked = findViewById(R.id.tvChecked)
        chart = findViewById(R.id.chart)
        btnToggle = findViewById(R.id.btnToggle)
        btnCheck = findViewById(R.id.btnCheck)
        rgInterval = findViewById(R.id.rgInterval)
        swKeepAwake = findViewById(R.id.swKeepAwake)
        tvBattery = findViewById(R.id.tvBattery)
        btnBattery = findViewById(R.id.btnBattery)
        tvNotif = findViewById(R.id.tvNotif)
        btnNotif = findViewById(R.id.btnNotif)
        btnVibe = findViewById(R.id.btnVibe)
        tvLog = findViewById(R.id.tvLog)

        cardColor = getColor(R.color.status_idle)
        statusCard.backgroundTintList = ColorStateList.valueOf(cardColor)

        findViewById<TextView>(R.id.tvVersion).text = try {
            "Версия " + packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            ""
        }

        btnToggle.setOnClickListener {
            if (MonitorService.running) {
                Prefs.setEnabled(this, false)
                MonitorService.stop(this)
            } else {
                Prefs.setEnabled(this, true)
                startMonitor()
            }
            ui.postDelayed({ render() }, 300L)
        }

        btnCheck.setOnClickListener {
            if (MonitorService.running) {
                MonitorService.send(this, MonitorService.ACTION_CHECK_NOW)
                Toast.makeText(this, "Проверяю…", Toast.LENGTH_SHORT).show()
            }
        }

        rgInterval.check(intervalButtons[Prefs.intervalSec(this)] ?: R.id.rb15)
        rgInterval.setOnCheckedChangeListener { _, checkedId ->
            val sec = intervalButtons.entries.firstOrNull { it.value == checkedId }?.key
                ?: Prefs.DEFAULT_INTERVAL_SEC
            Prefs.setIntervalSec(this, sec)
            notifyServiceSettingsChanged()
        }

        swKeepAwake.isChecked = Prefs.keepAwake(this)
        swKeepAwake.setOnCheckedChangeListener { _, checked ->
            Prefs.setKeepAwake(this, checked)
            notifyServiceSettingsChanged()
        }

        btnBattery.setOnClickListener { openBatterySettings() }
        btnNotif.setOnClickListener { openNotificationSettings() }
        btnVibe.setOnClickListener { Vibe.alarm(this) }

        // При первом запуске на Android 13+ спросим разрешение на уведомления,
        // без него значок в строке состояния не появится.
        if (savedInstanceState == null) requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.isEnabled(this) && !MonitorService.running) startMonitor()
        ui.removeCallbacks(ticker)
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    private fun startMonitor() {
        try {
            MonitorService.start(this)
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось запустить: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun notifyServiceSettingsChanged() {
        if (MonitorService.running) MonitorService.send(this, MonitorService.ACTION_SETTINGS)
    }

    // ------------------------------------------------------------- разрешения

    private fun notificationsAllowed(): Boolean =
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        safeStart(intent)
    }

    private fun ignoringBatteryOptimizations(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun openBatterySettings() {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        if (!safeStart(direct)) safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun safeStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------------ экран

    private fun render() {
        val now = System.currentTimeMillis()
        val running = MonitorService.running
        val s = MonitorService.state

        btnToggle.text = if (running) "Остановить" else "Запустить"
        btnCheck.isEnabled = running

        var color = getColor(R.color.status_idle)
        if (!running) {
            ivStatus.setImageResource(R.drawable.ic_stat_pause)
            tvTitle.text = "Мониторинг выключен"
            tvSince.text = "Значка в строке состояния нет"
            tvReason.text = "Нажми «Запустить», и приложение начнёт следить за Telegram."
        } else {
            when (s.status) {
                MonitorService.Status.OK -> {
                    color = getColor(R.color.status_ok)
                    ivStatus.setImageResource(R.drawable.ic_stat_ok)
                    tvTitle.text = "Telegram доступен"
                }
                MonitorService.Status.TG_DOWN -> {
                    color = getColor(R.color.status_fail)
                    ivStatus.setImageResource(R.drawable.ic_stat_fail)
                    tvTitle.text = "Telegram недоступен"
                }
                MonitorService.Status.NO_NETWORK -> {
                    color = getColor(R.color.status_offline)
                    ivStatus.setImageResource(R.drawable.ic_stat_offline)
                    tvTitle.text = "Нет интернета"
                }
                MonitorService.Status.UNKNOWN -> {
                    ivStatus.setImageResource(R.drawable.ic_stat_wait)
                    tvTitle.text = "Проверяю…"
                }
            }
            tvSince.text = if (s.status == MonitorService.Status.UNKNOWN) {
                "Первая проверка идёт прямо сейчас"
            } else {
                "Уже ${durationStr(now - s.since)} · с ${timeStr(s.since)}"
            }
            tvReason.text = when (s.status) {
                MonitorService.Status.OK -> "Сервер api.telegram.org отвечает. Если связь пропадёт, телефон завибрирует."
                MonitorService.Status.TG_DOWN -> "Причина: ${s.reason}. Остальной интернет при этом работает."
                MonitorService.Status.NO_NETWORK -> "Причина: ${s.reason}."
                MonitorService.Status.UNKNOWN -> "Стучусь на ${MonitorService.CHECK_URL}"
            } + if (s.lastVibrationAt > 0L) "\nПоследняя вибрация: ${timeStr(s.lastVibrationAt)}" else ""
        }
        animateCardColor(color)

        tvLatency.text = if (running && s.status == MonitorService.Status.OK) "${s.latencyMs} мс" else "—"
        val uptime = History.uptimePercent(this)
        tvUptime.text = if (uptime >= 0) formatPercent(uptime) else "—"
        tvChecked.text = if (running) agoStr(s.checkedAt, now) else "—"

        // График и журнал перерисовываем только когда что-то изменилось.
        val minute = now / 60_000L
        if (History.version != shownHistoryVersion || minute != shownChartMinute) {
            shownHistoryVersion = History.version
            shownChartMinute = minute
            chart.setData(History.lastMinutes(this, 60, now), now)
        }
        val log = EventLog.all(this)
        if (log !== shownLog) {
            shownLog = log
            tvLog.text = if (log.isEmpty()) "Пока пусто" else log.joinToString("\n")
        }

        if (notificationsAllowed()) {
            tvNotif.text = "✓ Уведомления включены, значок виден в строке состояния."
            btnNotif.visibility = View.GONE
        } else {
            tvNotif.text = "Уведомления выключены, поэтому значка в строке состояния не будет."
            btnNotif.visibility = View.VISIBLE
        }

        if (ignoringBatteryOptimizations()) {
            tvBattery.text = "✓ Работа в фоне без ограничений разрешена."
            btnBattery.visibility = View.GONE
        } else {
            tvBattery.text = "Android может «усыплять» приложение при выключенном экране, " +
                "и тогда проверки будут останавливаться."
            btnBattery.visibility = View.VISIBLE
        }
    }

    /** Плавно перекрашиваем карточку статуса, когда статус меняется. */
    private fun animateCardColor(target: Int) {
        if (target == cardColor) return
        colorAnimator?.cancel()
        val from = cardColor
        cardColor = target
        colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), from, target).apply {
            duration = 350L
            addUpdateListener {
                statusCard.backgroundTintList = ColorStateList.valueOf(it.animatedValue as Int)
            }
            start()
        }
    }
}
