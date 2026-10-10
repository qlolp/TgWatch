package ru.tgwatch

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
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

    private lateinit var setupCard: View
    private lateinit var tvSetup: TextView
    private lateinit var btnSetupNotif: Button
    private lateinit var btnSetupBattery: Button
    private lateinit var btnSetupAlarm: Button
    private lateinit var btnSetupApp: Button
    private lateinit var statusCard: View
    private lateinit var ivStatus: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvSince: TextView
    private lateinit var tvReason: TextView
    private lateinit var tvLatency: TextView
    private lateinit var tvUptime: TextView
    private lateinit var tvChecked: TextView
    private lateinit var tvDayStats: TextView
    private lateinit var chart: ChartView
    private lateinit var btnToggle: Button
    private lateinit var btnCheck: Button
    private lateinit var btnShareStats: Button
    private lateinit var rgInterval: RadioGroup
    private lateinit var swKeepAwake: Switch
    private lateinit var swVibrate: Switch
    private lateinit var swVibrateOffline: Switch
    private lateinit var swVibrateRecovery: Switch
    private lateinit var swQuietHours: Switch
    private lateinit var btnQuietHours: Button
    private lateinit var tvBattery: TextView
    private lateinit var btnBattery: Button
    private lateinit var tvAlarm: TextView
    private lateinit var btnAlarm: Button
    private lateinit var tvNotif: TextView
    private lateinit var btnNotif: Button
    private lateinit var tvOem: TextView
    private lateinit var btnAppDetails: Button
    private lateinit var btnVibe: Button
    private lateinit var tvLog: TextView
    private lateinit var btnShareLog: Button
    private lateinit var btnClearLog: Button

    private val ui = Handler(Looper.getMainLooper())

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
        MonitorService.restorePersistedState(this)

        setupCard = findViewById(R.id.setupCard)
        tvSetup = findViewById(R.id.tvSetup)
        btnSetupNotif = findViewById(R.id.btnSetupNotif)
        btnSetupBattery = findViewById(R.id.btnSetupBattery)
        btnSetupAlarm = findViewById(R.id.btnSetupAlarm)
        btnSetupApp = findViewById(R.id.btnSetupApp)
        statusCard = findViewById(R.id.statusCard)
        ivStatus = findViewById(R.id.ivStatus)
        tvTitle = findViewById(R.id.tvTitle)
        tvSince = findViewById(R.id.tvSince)
        tvReason = findViewById(R.id.tvReason)
        tvLatency = findViewById(R.id.tvLatency)
        tvUptime = findViewById(R.id.tvUptime)
        tvChecked = findViewById(R.id.tvChecked)
        tvDayStats = findViewById(R.id.tvDayStats)
        chart = findViewById(R.id.chart)
        btnToggle = findViewById(R.id.btnToggle)
        btnCheck = findViewById(R.id.btnCheck)
        btnShareStats = findViewById(R.id.btnShareStats)
        rgInterval = findViewById(R.id.rgInterval)
        swKeepAwake = findViewById(R.id.swKeepAwake)
        swVibrate = findViewById(R.id.swVibrate)
        swVibrateOffline = findViewById(R.id.swVibrateOffline)
        swVibrateRecovery = findViewById(R.id.swVibrateRecovery)
        swQuietHours = findViewById(R.id.swQuietHours)
        btnQuietHours = findViewById(R.id.btnQuietHours)
        tvBattery = findViewById(R.id.tvBattery)
        btnBattery = findViewById(R.id.btnBattery)
        tvAlarm = findViewById(R.id.tvAlarm)
        btnAlarm = findViewById(R.id.btnAlarm)
        tvNotif = findViewById(R.id.tvNotif)
        btnNotif = findViewById(R.id.btnNotif)
        tvOem = findViewById(R.id.tvOem)
        btnAppDetails = findViewById(R.id.btnAppDetails)
        btnVibe = findViewById(R.id.btnVibe)
        tvLog = findViewById(R.id.tvLog)
        btnShareLog = findViewById(R.id.btnShareLog)
        btnClearLog = findViewById(R.id.btnClearLog)

        cardColor = getColor(R.color.status_idle)
        statusCard.backgroundTintList = ColorStateList.valueOf(cardColor)

        findViewById<TextView>(R.id.tvVersion).text = try {
            "Версия " + packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
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

        swVibrate.isChecked = Prefs.vibrateEnabled(this)
        swVibrate.setOnCheckedChangeListener { _, checked -> Prefs.setVibrateEnabled(this, checked) }

        swVibrateOffline.isChecked = Prefs.vibrateOffline(this)
        swVibrateOffline.setOnCheckedChangeListener { _, checked -> Prefs.setVibrateOffline(this, checked) }

        swVibrateRecovery.isChecked = Prefs.vibrateOnRecovery(this)
        swVibrateRecovery.setOnCheckedChangeListener { _, checked -> Prefs.setVibrateOnRecovery(this, checked) }

        swQuietHours.isChecked = Prefs.quietHoursEnabled(this)
        swQuietHours.setOnCheckedChangeListener { _, checked -> Prefs.setQuietHoursEnabled(this, checked) }
        updateQuietHoursLabel()
        btnQuietHours.setOnClickListener { pickQuietHours() }

        val openNotif = View.OnClickListener { openNotificationSettings() }
        val openBatt = View.OnClickListener { openBatterySettings() }
        val openAlarm = View.OnClickListener { openExactAlarmSettings() }
        btnBattery.setOnClickListener(openBatt)
        btnNotif.setOnClickListener(openNotif)
        btnAlarm.setOnClickListener(openAlarm)
        btnSetupNotif.setOnClickListener(openNotif)
        btnSetupBattery.setOnClickListener(openBatt)
        btnSetupAlarm.setOnClickListener(openAlarm)
        btnSetupApp.setOnClickListener { OemTips.openAppDetails(this) }
        btnAppDetails.setOnClickListener { OemTips.openAppDetails(this) }
        btnVibe.setOnClickListener { Vibe.alarm(this) }
        btnShareLog.setOnClickListener { shareText("Журнал TG Монитор", EventLog.exportText(this)) }
        btnShareStats.setOnClickListener { shareText("Сводка TG Монитор", History.exportSummary(this)) }
        btnClearLog.setOnClickListener { confirmClearLog() }

        val oem = OemTips.manufacturerHint()
        if (oem != null) {
            tvOem.visibility = View.VISIBLE
            tvOem.text = oem
        }

        if (savedInstanceState == null) requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.isEnabled(this) && !MonitorService.running) startMonitor()
        WatchdogReceiver.schedule(this)
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

    private fun shareText(subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(Intent.createChooser(send, subject))
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось поделиться: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateQuietHoursLabel() {
        val label = ProbeRules.quietHoursLabel(Prefs.quietStartHour(this), Prefs.quietEndHour(this))
        swQuietHours.text = "Тихие часы $label (без вибрации)"
    }

    /** Два шага: сначала час начала тихих часов, затем час окончания. */
    private fun pickQuietHours() {
        TimePickerDialog(this, { _, startHour, _ ->
            TimePickerDialog(this, { _, endHour, _ ->
                Prefs.setQuietHours(this, startHour, endHour)
                Prefs.setQuietHoursEnabled(this, true)
                swQuietHours.isChecked = true
                updateQuietHoursLabel()
            }, Prefs.quietEndHour(this), 0, true).apply {
                setTitle("Конец тихих часов")
                show()
            }
        }, Prefs.quietStartHour(this), 0, true).apply {
            setTitle("Начало тихих часов")
            show()
        }
    }

    private fun confirmClearLog() {
        AlertDialog.Builder(this)
            .setTitle("Очистить журнал?")
            .setMessage("Записи о сменах статуса будут удалены. График и статистика останутся.")
            .setPositiveButton("Очистить") { _, _ ->
                EventLog.clear(this)
                shownLog = null
                render()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

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

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= 31) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:$packageName"))
            if (!safeStart(intent)) safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName")))
        }
    }

    private fun safeStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val running = MonitorService.running
        val s = MonitorService.state
        val stale = running && s.isStale(now, Prefs.intervalSec(this), Prefs.keepAwake(this))
        val notifOk = notificationsAllowed()
        val batteryOk = ignoringBatteryOptimizations()
        val alarmOk = Prefs.exactAlarmsAllowed(this)

        renderSetup(notifOk, batteryOk, alarmOk)

        btnToggle.text = if (running) "Остановить" else "Запустить"
        btnCheck.isEnabled = running

        var color = getColor(R.color.status_idle)
        if (!running) {
            ivStatus.setImageResource(R.drawable.ic_stat_pause)
            tvTitle.text = "Мониторинг выключен"
            tvSince.text = "Значка в строке состояния нет"
            tvReason.text = "Нажми «Запустить», и приложение начнёт следить за Telegram."
        } else if (stale) {
            color = getColor(R.color.status_idle)
            ivStatus.setImageResource(R.drawable.ic_stat_wait)
            tvTitle.text = when (s.status) {
                MonitorService.Status.OK -> "Последний раз: доступен"
                MonitorService.Status.TG_DOWN -> "Последний раз: недоступен"
                MonitorService.Status.NO_NETWORK -> "Последний раз: нет сети"
                MonitorService.Status.UNKNOWN -> "Проверяю…"
            }
            tvSince.text = "Проверено ${agoStr(s.checkedAt, now)} — жду свежую проверку"
            tvReason.text = "Статус мог устареть, пока служба перезапускалась. Новая проверка уже идёт."
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
                MonitorService.Status.OK ->
                    if (Prefs.vibrateEnabled(this)) "Серверы Telegram отвечают. При потере связи телефон завибрирует."
                    else "Серверы Telegram отвечают."
                MonitorService.Status.TG_DOWN -> "Причина: ${s.reason}"
                MonitorService.Status.NO_NETWORK -> "Причина: ${s.reason}"
                MonitorService.Status.UNKNOWN -> "Стучусь на ${MonitorService.CHECK_URL}"
            } + if (s.lastVibrationAt > 0L) "\nПоследняя вибрация: ${timeStr(s.lastVibrationAt)}" else ""
        }
        animateCardColor(color)

        tvLatency.text = if (running && !stale && s.status == MonitorService.Status.OK) "${s.latencyMs} мс" else "—"
        val uptime = History.uptimePercent(this)
        tvUptime.text = if (uptime >= 0) formatPercent(uptime) else "—"
        tvChecked.text = if (running) agoStr(s.checkedAt, now) else "—"

        val minute = now / 60_000L
        if (History.version != shownHistoryVersion || minute != shownChartMinute) {
            shownHistoryVersion = History.version
            shownChartMinute = minute
            chart.setData(History.lastMinutes(this, 60, now), now)
            tvDayStats.text = formatDayStats(History.dayStats(this))
        }
        val log = EventLog.all(this)
        if (log !== shownLog) {
            shownLog = log
            tvLog.text = if (log.isEmpty()) "Пока пусто" else log.joinToString("\n")
        }

        if (notifOk) {
            tvNotif.text = "✓ Уведомления включены, значок виден в строке состояния."
            btnNotif.visibility = View.GONE
        } else {
            tvNotif.text = "Уведомления выключены, поэтому значка в строке состояния не будет."
            btnNotif.visibility = View.VISIBLE
        }

        if (batteryOk) {
            tvBattery.text = "✓ Работа в фоне без ограничений разрешена."
            btnBattery.visibility = View.GONE
        } else {
            tvBattery.text = "Android может «усыплять» приложение при выключенном экране, " +
                "и тогда проверки будут останавливаться."
            btnBattery.visibility = View.VISIBLE
        }

        if (alarmOk) {
            tvAlarm.text = "✓ Точные будильники разрешены — проверки идут и при выключенном экране."
            btnAlarm.visibility = View.GONE
        } else {
            tvAlarm.text = "Без точных будильников Android откладывает проверки во сне, и статус устаревает."
            btnAlarm.visibility = View.VISIBLE
        }
    }

    private fun renderSetup(notifOk: Boolean, batteryOk: Boolean, alarmOk: Boolean) {
        if (notifOk && batteryOk && alarmOk) {
            setupCard.visibility = View.GONE
            return
        }
        setupCard.visibility = View.VISIBLE
        val lines = mutableListOf("Без этих разрешений значок в строке состояния может пропасть или врать.")
        if (!notifOk) lines += "• Включи уведомления — иначе постоянного значка не будет."
        if (!batteryOk) lines += "• Разреши работу без ограничений — иначе Android усыпит проверки."
        if (!alarmOk) lines += "• Разреши точные будильники — иначе проверки замирают при выключенном экране."
        OemTips.manufacturerHint()?.let { lines += "• $it" }
        tvSetup.text = lines.joinToString("\n")
        btnSetupNotif.visibility = if (notifOk) View.GONE else View.VISIBLE
        btnSetupBattery.visibility = if (batteryOk) View.GONE else View.VISIBLE
        btnSetupAlarm.visibility = if (alarmOk) View.GONE else View.VISIBLE
    }

    private fun formatDayStats(stats: History.DayStats?): String {
        if (stats == null) return "Пока нет данных — подожди несколько проверок."
        val parts = mutableListOf<String>()
        if (stats.uptimePercent >= 0) {
            parts += "Telegram ${formatPercent(stats.uptimePercent)} · ${stats.telegramChecks} проверок с интернетом"
        } else {
            parts += "Пока не было проверок с интернетом"
        }
        if (stats.avgLatencyMs >= 0) parts += "Среднее время ответа ${stats.avgLatencyMs} мс"
        if (stats.failMinutes == 0 && stats.offlineMinutes == 0) {
            parts += "Сбоев за сутки не было"
        } else {
            val detail = buildList {
                if (stats.failMinutes > 0) add("Telegram недоступен ${stats.failMinutes} мин")
                if (stats.offlineMinutes > 0) add("без интернета ${stats.offlineMinutes} мин")
            }.joinToString(", ")
            parts += detail
            if (stats.longestOutageMin > 0) {
                parts += "Самый долгий простой Telegram: ${stats.longestOutageMin} мин"
            }
        }
        if (stats.unmonitoredMinutes > 0) {
            parts += "Без проверки ${stats.unmonitoredMinutes} мин (служба спала или была убита)"
        }
        return parts.joinToString("\n")
    }

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
