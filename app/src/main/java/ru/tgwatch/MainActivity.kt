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
    private lateinit var tvChecked: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnCheck: Button
    private lateinit var btnResumeAlerts: Button
    private lateinit var rgInterval: RadioGroup
    private lateinit var swKeepAwake: Switch
    private lateinit var swVibrate: Switch
    private lateinit var swVibratePartial: Switch
    private lateinit var swVibrateOffline: Switch
    private lateinit var swVibrateRecovery: Switch
    private lateinit var swNotifyRecovery: Switch
    private lateinit var swEventSound: Switch
    private lateinit var btnAlarmPattern: Button
    private lateinit var swDailySummary: Switch
    private lateinit var swQuietHours: Switch
    private lateinit var btnQuietHours: Button
    private lateinit var tvBattery: TextView
    private lateinit var btnBattery: Button
    private lateinit var tvAlarm: TextView
    private lateinit var btnAlarm: Button
    private lateinit var tvNotif: TextView
    private lateinit var btnNotif: Button
    private lateinit var tvOem: TextView
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
    private var shownLog: List<String>? = null
    private lateinit var historyUi: MainHistoryUi
    private var historyDays = 1
    private var exportDays = 7
    private lateinit var btnPeriod: Button
    private lateinit var btnProfile: Button
    private lateinit var tvDiagnostics: TextView
    private var changingProfile = false
    private var advancedExpanded = false
    private lateinit var advancedSettings: View
    private lateinit var btnAdvanced: Button
    private lateinit var backupUi: BackupUi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.update(this)
        setContentView(R.layout.activity_main)
        historyUi = MainHistoryUi(this) { text ->
            shareText(Lang.t("Сводка TG Монитор", "TG Monitor summary"), text)
        }
        applySystemBarInsets()
        findViewById<Button>(R.id.btnIncidents).setOnClickListener { IncidentActivity.open(this, historyDays) }
        findViewById<Button>(R.id.btnAutomation).setOnClickListener { AutomationSettingsActivity.open(this) }
        findViewById<Button>(R.id.btnExportDiagnostics).setOnClickListener { LocalDiagnostics.export(this) }
        backupUi = BackupUi(this)
        findViewById<Button>(R.id.btnBackup).setOnClickListener { backupUi.export() }
        findViewById<Button>(R.id.btnRestoreBackup).setOnClickListener { backupUi.restore() }
        findViewById<Button>(R.id.btnImportCsv).setOnClickListener { backupUi.importCsv() }
        historyDays = savedInstanceState?.getInt("history_days", 1) ?: 1
        exportDays = savedInstanceState?.getInt("export_days", 7) ?: 7
        advancedExpanded = savedInstanceState?.getBoolean("advanced_expanded", false) ?: false
        advancedSettings = findViewById(R.id.advancedSettings)
        btnAdvanced = findViewById(R.id.btnAdvanced)
        updateAdvancedSettings()
        btnAdvanced.setOnClickListener {
            advancedExpanded = !advancedExpanded
            updateAdvancedSettings()
        }
        btnPeriod = findViewById(R.id.btnPeriod)
        btnProfile = findViewById(R.id.btnProfile)
        tvDiagnostics = findViewById(R.id.tvDiagnostics)
        btnPeriod.text = getString(if (historyDays == 1) R.string.period_day else R.string.period_week)
        btnPeriod.setOnClickListener {
            historyDays = if (historyDays == 1) 7 else 1
            btnPeriod.text = getString(if (historyDays == 1) R.string.period_day else R.string.period_week)
            historyUi.selectPeriod(historyDays)
            render()
        }
        findViewById<Button>(R.id.btnExportCsv).setOnClickListener {
            exportDays = historyDays
            safeStartExport()
        }
        findViewById<Button>(R.id.btnDiagnostics).setOnClickListener {
            tvDiagnostics.visibility = if (tvDiagnostics.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            render()
        }
        updateProfileLabel()
        btnProfile.setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.battery_title)
                .setItems(PowerProfile.entries.map { Lang.t("${it.title}: ${it.awake} с / сон ${it.asleep} с / сбой ${it.failure} с", "${it.title}: ${it.awake} s / sleep ${it.asleep} s / outage ${it.failure} s") }.toTypedArray()) { _, index ->
                    val profile = PowerProfile.entries[index]
                    Prefs.setProfile(this, profile)
                    changingProfile = true
                    rgInterval.check(intervalButtons[profile.awake] ?: R.id.rb30)
                    changingProfile = false
                    updateProfileLabel()
                    notifyServiceSettingsChanged()
                }.show()
        }
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
        tvChecked = findViewById(R.id.tvChecked)
        btnToggle = findViewById(R.id.btnToggle)
        btnCheck = findViewById(R.id.btnCheck)
        btnResumeAlerts = findViewById(R.id.btnResumeAlerts)
        btnResumeAlerts.setOnClickListener {
            val saved = Prefs.clearAlertMute(this)
            Toast.makeText(this, if (saved) R.string.alerts_resumed else R.string.alert_mute_save_failed,
                Toast.LENGTH_SHORT).show()
            render()
        }
        rgInterval = findViewById(R.id.rgInterval)
        swKeepAwake = findViewById(R.id.swKeepAwake)
        swVibrate = findViewById(R.id.swVibrate)
        swVibratePartial = findViewById(R.id.swVibratePartial)
        swVibrateOffline = findViewById(R.id.swVibrateOffline)
        swVibrateRecovery = findViewById(R.id.swVibrateRecovery)
        swNotifyRecovery = findViewById(R.id.swNotifyRecovery)
        swEventSound = findViewById(R.id.swEventSound)
        btnAlarmPattern = findViewById(R.id.btnAlarmPattern)
        swQuietHours = findViewById(R.id.swQuietHours)
        btnQuietHours = findViewById(R.id.btnQuietHours)
        tvBattery = findViewById(R.id.tvBattery)
        btnBattery = findViewById(R.id.btnBattery)
        tvAlarm = findViewById(R.id.tvAlarm)
        btnAlarm = findViewById(R.id.btnAlarm)
        tvNotif = findViewById(R.id.tvNotif)
        btnNotif = findViewById(R.id.btnNotif)
        tvOem = findViewById(R.id.tvOem)
        tvLog = findViewById(R.id.tvLog)
        btnShareLog = findViewById(R.id.btnShareLog)
        btnClearLog = findViewById(R.id.btnClearLog)

        cardColor = getColor(R.color.status_idle)
        statusCard.backgroundTintList = ColorStateList.valueOf(cardColor)

        findViewById<TextView>(R.id.tvVersion).text = try {
            Lang.t("Версия ", "Version ") + packageManager.getPackageInfo(packageName, 0).versionName
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
                Toast.makeText(this, Lang.t("Проверяю…", "Checking…"), Toast.LENGTH_SHORT).show()
            }
        }

        rgInterval.check(intervalButtons[Prefs.intervalSec(this)] ?: R.id.rb15)
        rgInterval.setOnCheckedChangeListener { _, checkedId ->
            val sec = intervalButtons.entries.firstOrNull { it.value == checkedId }?.key
                ?: Prefs.DEFAULT_INTERVAL_SEC
            if (!changingProfile) {
                Prefs.setIntervalSec(this, sec)
                updateProfileLabel()
                notifyServiceSettingsChanged()
            }
        }

        swKeepAwake.isChecked = Prefs.keepAwake(this)
        swKeepAwake.setOnCheckedChangeListener { _, checked ->
            Prefs.setKeepAwake(this, checked)
            notifyServiceSettingsChanged()
        }

        swVibrate.isChecked = Prefs.vibrateEnabled(this)
        swVibrate.setOnCheckedChangeListener { _, checked -> Prefs.setVibrateEnabled(this, checked) }

        swVibratePartial.isChecked = Prefs.vibratePartial(this)
        swVibratePartial.setOnCheckedChangeListener { _, checked -> Prefs.setVibratePartial(this, checked) }

        swVibrateOffline.isChecked = Prefs.vibrateOffline(this)
        swVibrateOffline.setOnCheckedChangeListener { _, checked -> Prefs.setVibrateOffline(this, checked) }

        swVibrateRecovery.isChecked = Prefs.vibrateOnRecovery(this)
        swVibrateRecovery.setOnCheckedChangeListener { _, checked -> Prefs.setVibrateOnRecovery(this, checked) }

        swNotifyRecovery.isChecked = Prefs.notifyRecovery(this)
        swNotifyRecovery.setOnCheckedChangeListener { _, checked -> Prefs.setNotifyRecovery(this, checked) }
        swEventSound.isChecked = Prefs.soundFor(this, "TG_DOWN")
        swEventSound.setOnCheckedChangeListener { _, checked -> Prefs.setSoundFor(this, "TG_DOWN", checked) }
        fun eventSwitch(id: Int, event: String, sound: Boolean) {
            val toggle = findViewById<Switch>(id)
            toggle.isChecked = if (sound) Prefs.soundFor(this, event) else Prefs.notifyFor(this, event)
            toggle.setOnCheckedChangeListener { _, checked ->
                if (sound) Prefs.setSoundFor(this, event, checked) else Prefs.setNotifyFor(this, event, checked)
            }
        }
        eventSwitch(R.id.swNotifyOutage, "TG_DOWN", false)
        eventSwitch(R.id.swNotifyPartial, "PARTIAL", false)
        eventSwitch(R.id.swSoundPartial, "PARTIAL", true)
        eventSwitch(R.id.swSoundRecovery, "RECOVERY", true)
        fun channelButton(id: Int, event: String) {
            findViewById<Button>(id).setOnClickListener {
                EventNotifications(this, getSystemService(NotificationManager::class.java))
                try {
                    startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, EventNotifications.channel(event)))
                } catch (_: Exception) { openNotificationSettings() }
            }
        }
        channelButton(R.id.btnChannelOutage, "TG_DOWN")
        channelButton(R.id.btnChannelPartial, "PARTIAL")
        channelButton(R.id.btnChannelRecovery, "RECOVERY")
        updateAlarmPatternLabel()
        btnAlarmPattern.setOnClickListener {
            AlertDialog.Builder(this).setTitle(Lang.t("Сигнал вибрации при сбое", "Outage vibration pattern"))
                .setSingleChoiceItems(AlarmPattern.entries.map { it.title }.toTypedArray(),
                    Prefs.alarmPattern(this).ordinal) { dialog, index ->
                    Prefs.setAlarmPattern(this, AlarmPattern.entries[index])
                    updateAlarmPatternLabel()
                    dialog.dismiss()
                }.show()
        }

        swDailySummary = findViewById(R.id.swDailySummary)
        swDailySummary.isChecked = Prefs.dailySummary(this)
        swDailySummary.setOnCheckedChangeListener { _, checked ->
            Prefs.setDailySummary(this, checked)
            if (checked) requestNotificationPermissionIfNeeded()
        }
        updateDailySummaryLabel()
        findViewById<Button>(R.id.btnDailySummaryTime).setOnClickListener { pickDailySummaryTime() }
        findViewById<Button>(R.id.btnDailySummaryNow).setOnClickListener {
            requestNotificationPermissionIfNeeded()
            DailySummaryReceiver.postAsync(this, force = true) { posted ->
                runOnUiThread {
                    if (!posted && !isFinishing && !isDestroyed)
                        Toast.makeText(this, R.string.summary_unavailable, Toast.LENGTH_LONG).show()
                }
            }
        }

        ExtraProbesUi(this, ::notifyServiceSettingsChanged)

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
        findViewById<Button>(R.id.btnAppDetails).setOnClickListener { OemTips.openAppDetails(this) }
        findViewById<Button>(R.id.btnVibe).setOnClickListener { Vibe.alarm(this) }
        btnShareLog.setOnClickListener { shareText(Lang.t("Журнал TG Монитор", "TG Monitor log"), EventLog.exportText(this)) }
        btnClearLog.setOnClickListener { confirmClearLog() }

        val oem = OemTips.manufacturerHint()
        if (oem != null) {
            tvOem.visibility = View.VISIBLE
            tvOem.text = oem
        }

        if (savedInstanceState == null) requestNotificationPermissionIfNeeded()
    }

    private fun updateAdvancedSettings() {
        advancedSettings.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
        btnAdvanced.text = getString(if (advancedExpanded) R.string.advanced_hide else R.string.advanced_show)
    }
    private fun updateAlarmPatternLabel() { btnAlarmPattern.text = Lang.t("Сигнал вибрации: ${Prefs.alarmPattern(this).title}", "Vibration pattern: ${Prefs.alarmPattern(this).title}") }

    override fun onResume() {
        super.onResume()
        Lang.update(this)
        historyUi.resume()
        if (Prefs.isEnabled(this) && !MonitorService.running) startMonitor()
        WatchdogReceiver.schedule(this)
        ui.removeCallbacks(ticker)
        ui.post(ticker)
    }

    override fun onPause() {
        historyUi.pause()
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        historyUi.close()
        backupUi.close()
        colorAnimator?.cancel()
        super.onDestroy()
    }

    private fun startMonitor() {
        try {
            MonitorService.start(this)
        } catch (e: Exception) {
            Toast.makeText(this, Lang.t("Не удалось запустить: ${e.message}", "Couldn’t start monitoring: ${e.message}"), Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, Lang.t("Не удалось поделиться: ${e.message}", "Couldn’t share: ${e.message}"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateDailySummaryLabel() {
        swDailySummary.text = Lang.t("Сводка за день в ${DailySummary.timeLabel(Prefs.dailySummaryMinute(this))}", "Daily summary at ${DailySummary.timeLabel(Prefs.dailySummaryMinute(this))}")
    }

    private fun pickDailySummaryTime() {
        val minute = Prefs.dailySummaryMinute(this)
        TimePickerDialog(this, { _, hour, min ->
            Prefs.setDailySummaryMinute(this, hour * 60 + min)
            Prefs.setDailySummary(this, true)
            swDailySummary.isChecked = true
            updateDailySummaryLabel()
        }, minute / 60, minute % 60, true).apply {
            setTitle(Lang.t("Время сводки за день", "Daily summary time"))
            show()
        }
    }

    private fun updateQuietHoursLabel() {
        val label = ProbeRules.quietHoursMinutesLabel(Prefs.quietStartMinute(this), Prefs.quietEndMinute(this))
        swQuietHours.text = Lang.t("Тихие часы $label (без вибрации)", "Quiet hours $label (no vibration)")
    }

    /** Сначала время начала тихих часов, затем время окончания. */
    private fun pickQuietHours() {
        TimePickerDialog(this, { _, startHour, startMinute ->
            TimePickerDialog(this, { _, endHour, endMinute ->
                Prefs.setQuietHoursMinutes(this, startHour * 60 + startMinute, endHour * 60 + endMinute)
                Prefs.setQuietHoursEnabled(this, true)
                swQuietHours.isChecked = true
                updateQuietHoursLabel()
            }, Prefs.quietEndMinute(this) / 60, Prefs.quietEndMinute(this) % 60, true).apply {
                setTitle(Lang.t("Конец тихих часов", "Quiet hours end"))
                show()
            }
        }, Prefs.quietStartMinute(this) / 60, Prefs.quietStartMinute(this) % 60, true).apply {
            setTitle(Lang.t("Начало тихих часов", "Quiet hours start"))
            show()
        }
    }

    private fun confirmClearLog() {
        AlertDialog.Builder(this)
            .setTitle(Lang.t("Очистить журнал?", "Clear the log?"))
            .setMessage(Lang.t("Записи о сменах статуса будут удалены. График и статистика останутся.", "Status change entries will be deleted. The chart and statistics will stay."))
            .setPositiveButton(Lang.t("Очистить", "Clear")) { _, _ ->
                EventLog.clear(this)
                shownLog = null
                render()
            }
            .setNegativeButton(Lang.t("Отмена", "Cancel"), null)
            .show()
    }

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
        if (BackupManager.hasPending(this)) {
            btnToggle.isEnabled = false
            btnCheck.isEnabled = false
            tvTitle.text = Lang.t("Незавершённое восстановление", "Restore not finished")
            tvSince.text = Lang.t("Мониторинг выключен", "Monitoring is off")
            tvReason.text = Lang.t("Нажмите «Восстановить резервную копию», чтобы завершить замену данных.", "Tap “Restore backup” to finish replacing the data.")
            ivStatus.contentDescription = tvTitle.text
            return
        }
        btnToggle.isEnabled = true
        val now = System.currentTimeMillis()
        val running = MonitorService.running
        val s = MonitorService.state
        val stale = running && s.isStale(now)
        val notifOk = notificationsAllowed()
        val batteryOk = ignoringBatteryOptimizations()
        val alarmOk = Prefs.exactAlarmsAllowed(this)

        renderSetup(notifOk, batteryOk, alarmOk)

        btnToggle.text = if (running) Lang.t("Остановить", "Stop") else Lang.t("Запустить", "Start")
        btnCheck.isEnabled = running

        var color = getColor(R.color.status_idle)
        if (!running) {
            ivStatus.setImageResource(R.drawable.ic_stat_pause)
            tvTitle.text = Lang.t("Мониторинг выключен", "Monitoring is off")
            tvSince.text = Lang.t("Проверки остановлены", "Checks are stopped")
            tvReason.text = Lang.t("Нажми «Запустить», и приложение начнёт следить за Telegram.", "Tap “Start” and the app will begin monitoring Telegram.")
        } else if (stale) {
            color = getColor(R.color.status_idle)
            ivStatus.setImageResource(R.drawable.ic_stat_wait)
            tvTitle.text = when (s.status) {
                MonitorService.Status.OK -> Lang.t("Последний раз: доступен", "Last known: reachable")
                MonitorService.Status.PARTIAL -> Lang.t("Последний раз: частично доступен", "Last known: partially reachable")
                MonitorService.Status.TG_DOWN -> Lang.t("Последний раз: недоступен", "Last known: unreachable")
                MonitorService.Status.NO_NETWORK -> Lang.t("Последний раз: нет сети", "Last known: no internet")
                MonitorService.Status.UNKNOWN -> Lang.t("Проверяю…", "Checking…")
            }
            tvSince.text = Lang.t("Проверено ${agoStr(s.checkedAt, now)} — жду свежую проверку", "Checked ${agoStr(s.checkedAt, now)} — waiting for a fresh check")
            tvReason.text = Lang.t("Мониторинг задерживается. Android мог отложить проверку; нажми «Проверить».", "Monitoring is delayed. Android may have postponed the check; tap “Check now”.")
        } else {
            when (s.status) {
                MonitorService.Status.OK -> {
                    color = getColor(R.color.status_ok)
                    ivStatus.setImageResource(R.drawable.ic_stat_ok)
                    tvTitle.text = Lang.t("Telegram отвечает", "Telegram is responding")
                }
                MonitorService.Status.PARTIAL -> {
                    color = getColor(R.color.status_offline)
                    ivStatus.setImageResource(R.drawable.ic_stat_wait)
                    tvTitle.text = Lang.t("Telegram частично доступен", "Telegram is partially reachable")
                }
                MonitorService.Status.TG_DOWN -> {
                    color = getColor(R.color.status_fail)
                    ivStatus.setImageResource(R.drawable.ic_stat_fail)
                    tvTitle.text = Lang.t("Telegram недоступен", "Telegram is unreachable")
                }
                MonitorService.Status.NO_NETWORK -> {
                    color = getColor(R.color.status_offline)
                    ivStatus.setImageResource(R.drawable.ic_stat_offline)
                    tvTitle.text = Lang.t("Нет интернета", "No internet")
                }
                MonitorService.Status.UNKNOWN -> {
                    ivStatus.setImageResource(R.drawable.ic_stat_wait)
                    tvTitle.text = if (s.checkedAt > 0) Lang.t("Доступность не определена", "Reachability unknown") else Lang.t("Проверяю…", "Checking…")
                }
            }
            tvSince.text = if (s.status == MonitorService.Status.UNKNOWN) {
                if (s.checkedAt > 0) Lang.t("Проверено ${agoStr(s.checkedAt, now)}", "Checked ${agoStr(s.checkedAt, now)}") else Lang.t("Первая проверка идёт прямо сейчас", "First check in progress")
            } else {
                Lang.t("Уже ${durationStr(now - s.since)} · с ${timeStr(s.since)}", "For ${durationStr(now - s.since)} · since ${timeStr(s.since)}")
            }
            tvReason.text = when (s.status) {
                MonitorService.Status.OK -> Lang.t("MTProto и веб-ресурсы отвечают. Доставка сообщений не проверяется.", "MTProto and web resources are responding. Message delivery is not checked.")
                MonitorService.Status.PARTIAL -> s.reason
                MonitorService.Status.TG_DOWN -> Lang.t("Причина: ${s.reason}", "Reason: ${s.reason}")
                MonitorService.Status.NO_NETWORK -> Lang.t("Причина: ${s.reason}", "Reason: ${s.reason}")
                MonitorService.Status.UNKNOWN -> s.reason.ifEmpty { Lang.t("Проверяю веб-ресурсы и MTProto", "Checking web resources and MTProto") }
            } + if (s.lastVibrationAt > 0L) Lang.t("\nПоследняя вибрация: ${timeStr(s.lastVibrationAt)}", "\nLast vibration: ${timeStr(s.lastVibrationAt)}") else ""
        }
        val mutedUntil = Prefs.alertsMutedUntil(this, now)
        btnResumeAlerts.visibility = if (mutedUntil > 0L) View.VISIBLE else View.GONE
        if (running && mutedUntil > 0L) tvReason.append(Lang.t("\nОповещения заглушены до ${timeStr(mutedUntil)}",
            "\nAlerts muted until ${timeStr(mutedUntil)}"))
        ivStatus.contentDescription = tvTitle.text
        animateCardColor(color)

        tvLatency.text = if (running && !stale && s.status == MonitorService.Status.OK) Lang.t("${s.latencyMs} мс", "${s.latencyMs} ms") else "—"
        tvChecked.text = if (running) agoStr(s.checkedAt, now) else "—"
        historyUi.refresh(historyDays, now)
        if (tvDiagnostics.visibility == View.VISIBLE) {
            tvDiagnostics.text = s.diagnostics.ifEmpty { Lang.t("Диагностика появится после первой проверки.", "Diagnostics will appear after the first check.") } +
                Lang.t("\nВозраст результата: ${agoStr(s.checkedAt, now)}. Интервал: ${s.expectedIntervalSec} с.", "\nLast result: ${agoStr(s.checkedAt, now)}. Interval: ${s.expectedIntervalSec} s.")
        }
        val log = EventLog.all(this)
        if (log !== shownLog) {
            shownLog = log
            tvLog.text = if (log.isEmpty()) Lang.t("Пока пусто", "Nothing yet") else log.joinToString("\n")
        }

        if (notifOk) {
            tvNotif.text = Lang.t("✓ Уведомления включены, значок виден в строке состояния.", "✓ Notifications are on; the icon is visible in the status bar.")
            btnNotif.visibility = View.GONE
        } else {
            tvNotif.text = Lang.t("Уведомления выключены, поэтому значка в строке состояния не будет.", "Notifications are off, so there will be no status bar icon.")
            btnNotif.visibility = View.VISIBLE
        }

        if (batteryOk) {
            tvBattery.text = Lang.t("✓ Работа в фоне без ограничений разрешена.", "✓ Unrestricted background use is allowed.")
            btnBattery.visibility = View.GONE
        } else {
            tvBattery.text = Lang.t("Android может «усыплять» приложение при выключенном экране, " +
                "и тогда проверки будут останавливаться.",
                "Android may put the app to sleep while the screen is off, and then checks will stop.")
            btnBattery.visibility = View.VISIBLE
        }

        if (alarmOk) {
            tvAlarm.text = Lang.t("✓ Точные будильники разрешены. Android всё равно может откладывать проверки в глубоком сне.", "✓ Exact alarms are allowed. Android may still postpone checks in deep sleep.")
            btnAlarm.visibility = View.GONE
        } else {
            tvAlarm.text = Lang.t("Без точных будильников Android откладывает проверки во сне, и статус устаревает.", "Without exact alarms, Android postpones checks during sleep and the status goes stale.")
            btnAlarm.visibility = View.VISIBLE
        }
    }

    private fun renderSetup(notifOk: Boolean, batteryOk: Boolean, alarmOk: Boolean) {
        if (notifOk && batteryOk && alarmOk) {
            setupCard.visibility = View.GONE
            return
        }
        setupCard.visibility = View.VISIBLE
        val lines = mutableListOf(Lang.t("Без этих разрешений значок в строке состояния может пропасть или врать.", "Without these permissions, the status bar icon may disappear or show the wrong status."))
        if (!notifOk) lines += Lang.t("• Включи уведомления — иначе постоянного значка не будет.", "• Turn on notifications, or there will be no persistent icon.")
        if (!batteryOk) lines += Lang.t("• Разреши работу без ограничений — иначе Android усыпит проверки.", "• Allow unrestricted background use, or Android will put checks to sleep.")
        if (!alarmOk) lines += Lang.t("• Разреши точные будильники — иначе проверки замирают при выключенном экране.", "• Allow exact alarms, or checks will stall while the screen is off.")
        OemTips.manufacturerHint()?.let { lines += "• $it" }
        tvSetup.text = lines.joinToString("\n")
        btnSetupNotif.visibility = if (notifOk) View.GONE else View.VISIBLE
        btnSetupBattery.visibility = if (batteryOk) View.GONE else View.VISIBLE
        btnSetupAlarm.visibility = if (alarmOk) View.GONE else View.VISIBLE
    }

    private fun updateProfileLabel() {
        val profile = Prefs.profile(this)
        btnProfile.text = Lang.t("Профиль: ", "Profile: ") + (profile?.title ?: Lang.t("Свой интервал", "Custom interval"))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("history_days", historyDays)
        outState.putBoolean("advanced_expanded", advancedExpanded)
        outState.putInt("export_days", exportDays)
        super.onSaveInstanceState(outState)
    }

    private fun safeStartExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "tgwatch-${if (exportDays == 1) "day" else "week"}.csv")
        }
        try { startActivityForResult(intent, 20) }
        catch (e: Exception) { Toast.makeText(this, Lang.t("Не удалось открыть сохранение файла", "Couldn’t open the file save dialog"), Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Platform activity result API for dependency-free native UI")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (backupUi.onResult(requestCode, resultCode, data)) return
        if (LocalDiagnostics.onActivityResult(this, requestCode, resultCode, data)) return
        if (requestCode != 20 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val days = exportDays
        val app = applicationContext
        Thread({
            val message = try {
                val csv = History.exportCsv(app, days)
                val output = app.contentResolver.openOutputStream(uri, "wt") ?: error(Lang.t("Файл недоступен", "File unavailable"))
                output.bufferedWriter(Charsets.UTF_8).use { it.write(csv) }
                Lang.t("CSV сохранён", "CSV saved")
            } catch (e: Exception) { Lang.t("Не удалось сохранить CSV: ${e.message}", "Couldn’t save the CSV: ${e.message}") }
            runOnUiThread { if (!isFinishing && !isDestroyed) Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }, "tg-export").start()
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

private fun Activity.applySystemBarInsets() {
    ScreenInsets.apply(this, findViewById(R.id.mainScroll))
}

private fun Activity.notificationsAllowed(): Boolean =
    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()


private fun Activity.ignoringBatteryOptimizations(): Boolean =
    (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

