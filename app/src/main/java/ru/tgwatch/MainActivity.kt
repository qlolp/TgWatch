package ru.tgwatch

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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
    private lateinit var csvExport: TargetCsvExportUi
    private lateinit var btnPeriod: Button
    private lateinit var btnProfile: Button
    private lateinit var tvDiagnostics: TextView
    private var changingProfile = false
    private var advancedExpanded = false
    private lateinit var advancedSettings: View
    private lateinit var btnAdvanced: Button
    private lateinit var backupUi: BackupUi
    private lateinit var targetUi: TargetSelectionUi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val firstRunGuide = savedInstanceState == null && Onboarding.shouldShow(this)
        if (firstRunGuide) Prefs.setEnabled(this, false)
        setContentView(R.layout.activity_main)
        historyUi = MainHistoryUi(this) { text ->
            shareText(this@MainActivity.renderString(R.string.copy_main_tg_monitor_summary), text)
        }
        applySystemBarInsets()
        findViewById<Button>(R.id.btnLatency).setOnClickListener { LatencyActivity.open(this, targetUi.selected) }
        findViewById<Button>(R.id.btnShareStatus).setOnClickListener {
            StatusShareActivity.open(this, targetUi.selected)
        }
        findViewById<Button>(R.id.btnIncidents).setOnClickListener {
            IncidentActivity.open(this, historyDays, targetUi.selected)
        }
        findViewById<Button>(R.id.btnAutomation).setOnClickListener { AutomationSettingsActivity.open(this) }
        findViewById<Button>(R.id.btnExportDiagnostics).setOnClickListener { LocalDiagnostics.export(this) }
        backupUi = BackupUi(this)
        findViewById<Button>(R.id.btnBackup).setOnClickListener { backupUi.export() }
        findViewById<Button>(R.id.btnRestoreBackup).setOnClickListener { backupUi.restore() }
        findViewById<Button>(R.id.btnImportCsv).setOnClickListener { backupUi.importCsv() }
        historyDays = savedInstanceState?.getInt("history_days", 1) ?: 1
        csvExport = TargetCsvExportUi(this, savedInstanceState)
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
            csvExport.export(targetUi.selected, historyDays)
        }
        findViewById<Button>(R.id.btnDiagnostics).setOnClickListener {
            tvDiagnostics.visibility = if (tvDiagnostics.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            render()
        }
        updateProfileLabel()
        btnProfile.setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.battery_title)
                .setItems(PowerProfile.entries.map { this@MainActivity.renderString(
                    R.string.copy_main_s_sleep_s_outage,
                    AndroidText.resolve(this, it.title),
                    it.awake,
                    it.asleep,
                    it.failure) }.toTypedArray()) { _, index ->
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
            this@MainActivity.renderString(R.string.copy_main_version) +
                packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            ""
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
        eventSwitch(R.id.swNotifySlow, "SLOW", false)
        eventSwitch(R.id.swSoundPartial, "PARTIAL", true)
        eventSwitch(R.id.swSoundRecovery, "RECOVERY", true)
        fun channelButton(id: Int, event: String) {
            findViewById<Button>(id).setOnClickListener {
                EventNotifications(this, getSystemService(NotificationManager::class.java))
                try {
                    startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, EventNotifications.channel(event)))
                } catch (_: Exception) { Onboarding.openNotificationSettings(this) }
            }
        }
        channelButton(R.id.btnChannelOutage, "TG_DOWN")
        channelButton(R.id.btnChannelPartial, "PARTIAL")
        channelButton(R.id.btnChannelRecovery, "RECOVERY")
        updateAlarmPatternLabel()
        btnAlarmPattern.setOnClickListener {
            AlertDialog.Builder(this).setTitle(this@MainActivity.renderString(
                R.string.copy_main_outage_vibration_pattern))
                .setSingleChoiceItems(AlarmPattern.entries.map { AndroidText.resolve(this, it.title) }.toTypedArray(),
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
            if (checked) Onboarding.requestNotifications(this)
        }
        updateDailySummaryLabel()
        findViewById<Button>(R.id.btnDailySummaryTime).setOnClickListener { pickDailySummaryTime() }
        findViewById<Button>(R.id.btnDailySummaryNow).setOnClickListener {
            Onboarding.requestNotifications(this)
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

        val openNotif = View.OnClickListener { Onboarding.openNotificationSettings(this) }
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
        findViewById<Button>(R.id.btnOnboarding).setOnClickListener { OnboardingActivity.open(this) }
        findViewById<Button>(R.id.btnVibe).setOnClickListener { Vibe.alarm(this) }
        btnShareLog.setOnClickListener { shareText(this@MainActivity.renderString(
            R.string.copy_main_tg_monitor_log), EventLog.exportText(this)) }
        btnClearLog.setOnClickListener { confirmClearLog() }

        val oem = OemTips.manufacturerHint(this)
        if (oem != null) {
            tvOem.visibility = View.VISIBLE
            tvOem.text = oem
        }

        targetUi = TargetSelectionUi(this, savedInstanceState, historyUi, ::render, ::animateCardColor)
        historyUi.selectTarget(targetUi.selected)

        // First-run setup can finish or be skipped before the user explicitly starts monitoring.
        if (savedInstanceState == null) {
            if (firstRunGuide) OnboardingActivity.open(this) else Onboarding.requestNotifications(this)
        }
    }

    private fun updateAdvancedSettings() {
        advancedSettings.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
        btnAdvanced.text = getString(if (advancedExpanded) R.string.advanced_hide else R.string.advanced_show)
    }
    private fun updateAlarmPatternLabel() { btnAlarmPattern.text = this@MainActivity.renderString(
        R.string.copy_main_vibration_pattern,
        AndroidText.resolve(this, Prefs.alarmPattern(this).title)) }

    override fun onResume() {
        super.onResume()

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
        targetUi.close()
        historyUi.close()
        backupUi.close()
        colorAnimator?.cancel()
        super.onDestroy()
    }

    private fun startMonitor() {
        try {
            MonitorService.start(this)
        } catch (e: Exception) {
            Toast.makeText(this, this@MainActivity.renderString(
                R.string.copy_main_couldn_t_start_monitoring,
                e.message), Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, this@MainActivity.renderString(
                R.string.copy_main_couldn_t_share, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateDailySummaryLabel() {
        swDailySummary.text = this@MainActivity.renderString(
            R.string.copy_main_daily_summary_at,
            DailySummary.timeLabel(Prefs.dailySummaryMinute(this)))
    }

    private fun pickDailySummaryTime() {
        val minute = Prefs.dailySummaryMinute(this)
        TimePickerDialog(this, { _, hour, min ->
            Prefs.setDailySummaryMinute(this, hour * 60 + min)
            Prefs.setDailySummary(this, true)
            swDailySummary.isChecked = true
            updateDailySummaryLabel()
        }, minute / 60, minute % 60, true).apply {
            setTitle(this@MainActivity.renderString(R.string.copy_main_daily_summary_time))
            show()
        }
    }

    private fun updateQuietHoursLabel() {
        val label = ProbeRules.quietHoursMinutesLabel(Prefs.quietStartMinute(this), Prefs.quietEndMinute(this))
        swQuietHours.text = this@MainActivity.renderString(R.string.copy_main_quiet_hours_no_vibration, label)
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
                setTitle(this@MainActivity.renderString(R.string.copy_main_quiet_hours_end))
                show()
            }
        }, Prefs.quietStartMinute(this) / 60, Prefs.quietStartMinute(this) % 60, true).apply {
            setTitle(this@MainActivity.renderString(R.string.copy_main_quiet_hours_start))
            show()
        }
    }

    private fun confirmClearLog() {
        AlertDialog.Builder(this)
            .setTitle(this@MainActivity.renderString(R.string.copy_main_clear_the_log))
            .setMessage(this@MainActivity.renderString(R.string.copy_main_status_change_entries_will))
            .setPositiveButton(this@MainActivity.renderString(R.string.copy_main_clear)) { _, _ ->
                EventLog.clear(this)
                shownLog = null
                render()
            }
            .setNegativeButton(this@MainActivity.renderString(R.string.copy_main_cancel), null)
            .show()
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
            if (!safeStart(intent)) safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(
                Uri.parse("package:$packageName")))
        }
    }

    private fun safeStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }

    private val recoveryUi = RecoveryUi(this)

    private fun renderTelegramStatus(s: MonitorService.State, now: Long, running: Boolean, stale: Boolean) {
        var color = getColor(R.color.status_idle)
        if (!running) {
            ivStatus.setImageResource(R.drawable.ic_stat_pause)
            tvTitle.text = this@MainActivity.renderString(R.string.copy_main_monitoring_is_off)
            tvSince.text = this@MainActivity.renderString(R.string.copy_main_checks_are_stopped)
            tvReason.text = this@MainActivity.renderString(R.string.copy_main_tap_start_and_the)
        } else if (stale) {
            color = getColor(R.color.status_idle)
            renderTelegramStale(s, now)
        } else {
            color = renderTelegramCurrent(s, now)
        }
        val mutedUntil = Prefs.alertsMutedUntil(this, now)
        btnResumeAlerts.visibility = if (mutedUntil > 0L) View.VISIBLE else View.GONE
        if (running && mutedUntil > 0L) tvReason.append(this@MainActivity.renderString(
            R.string.copy_main_alerts_muted_until,
            timeStr(this@MainActivity, mutedUntil)))
        ivStatus.contentDescription = tvTitle.text
        animateCardColor(color)

        tvLatency.text = if (running && !stale && s.status == MonitorService.Status.OK) this@MainActivity.renderString(
            R.string.copy_main_ms,
            s.latencyMs) else "—"
        tvChecked.text = if (running) agoStr(this@MainActivity, s.checkedAt, now) else "—"
    }

    private fun renderTelegramStale(s: MonitorService.State, now: Long) {
        ivStatus.setImageResource(R.drawable.ic_stat_wait)
        tvTitle.text = when (s.status) {
            MonitorService.Status.OK -> this@MainActivity.renderString(R.string.copy_main_last_known_reachable)
            MonitorService.Status.PARTIAL -> this@MainActivity.renderString(
                R.string.copy_main_last_known_partially_reachable)
            MonitorService.Status.TG_DOWN -> this@MainActivity.renderString(
                R.string.copy_main_last_known_unreachable)
            MonitorService.Status.NO_NETWORK -> this@MainActivity.renderString(
                R.string.copy_main_last_known_no_internet)
            MonitorService.Status.UNKNOWN -> this@MainActivity.renderString(R.string.copy_main_checking)
        }
        tvSince.text = this@MainActivity.renderString(
            R.string.copy_main_checked_waiting_for_a,
            agoStr(this@MainActivity, s.checkedAt, now))
        tvReason.text = this@MainActivity.renderString(R.string.copy_main_monitoring_is_delayed_android)
    }

    private fun renderTelegramCurrent(s: MonitorService.State, now: Long): Int {
        var color = getColor(R.color.status_idle)
        when (s.status) {
            MonitorService.Status.OK -> {
                color = getColor(R.color.status_ok)
                ivStatus.setImageResource(R.drawable.ic_stat_ok)
                tvTitle.text = this@MainActivity.renderString(R.string.copy_main_telegram_is_responding)
            }
            MonitorService.Status.PARTIAL -> {
                color = getColor(R.color.status_offline)
                ivStatus.setImageResource(R.drawable.ic_stat_wait)
                tvTitle.text = this@MainActivity.renderString(R.string.copy_main_telegram_is_partially_reachable)
            }
            MonitorService.Status.TG_DOWN -> {
                color = getColor(R.color.status_fail)
                ivStatus.setImageResource(R.drawable.ic_stat_fail)
                tvTitle.text = this@MainActivity.renderString(R.string.copy_main_telegram_is_unreachable)
            }
            MonitorService.Status.NO_NETWORK -> {
                color = getColor(R.color.status_offline)
                ivStatus.setImageResource(R.drawable.ic_stat_offline)
                tvTitle.text = this@MainActivity.renderString(R.string.copy_main_no_internet)
            }
            MonitorService.Status.UNKNOWN -> {
                ivStatus.setImageResource(R.drawable.ic_stat_wait)
                tvTitle.text = if (s.checkedAt > 0) this@MainActivity.renderString(
                    R.string.copy_main_reachability_unknown) else this@MainActivity.renderString(
                        R.string.copy_main_checking)
            }
        }
        tvSince.text = if (s.status == MonitorService.Status.UNKNOWN) {
            if (s.checkedAt > 0) this@MainActivity.renderString(
                R.string.copy_main_checked,
                agoStr(this@MainActivity, s.checkedAt, now)) else this@MainActivity.renderString(
                    R.string.copy_main_first_check_in_progress)
        } else {
            this@MainActivity.renderString(
                R.string.copy_main_for_since,
                durationStr(this@MainActivity, now - s.since),
                timeStr(this@MainActivity, s.since))
        }
        tvReason.text = telegramReason(s)
        return color
    }

    private fun telegramReason(s: MonitorService.State): String {
        return when (s.status) {
            MonitorService.Status.OK -> this@MainActivity.renderString(
                R.string.copy_main_mtproto_and_web_resources)
            MonitorService.Status.PARTIAL -> s.reason
            MonitorService.Status.TG_DOWN -> this@MainActivity.renderString(R.string.copy_main_reason, s.reason)
            MonitorService.Status.NO_NETWORK -> this@MainActivity.renderString(R.string.copy_main_reason, s.reason)
            MonitorService.Status.UNKNOWN -> s.reason.ifEmpty { this@MainActivity.renderString(
                R.string.copy_main_checking_web_resources_and) }
        } + if (s.lastVibrationAt > 0L) this@MainActivity.renderString(
            R.string.copy_main_last_vibration,
            timeStr(this@MainActivity, s.lastVibrationAt)) else ""
    }

    private fun render() {
        if (recoveryUi.preparing()) return
        if (BackupManager.hasPending()) {
            btnToggle.isEnabled = false
            btnCheck.isEnabled = false
            tvTitle.text = this@MainActivity.renderString(R.string.copy_main_restore_not_finished)
            tvSince.text = this@MainActivity.renderString(R.string.copy_main_monitoring_is_off)
            tvReason.text = this@MainActivity.renderString(R.string.copy_main_tap_restore_backup_to)
            ivStatus.contentDescription = tvTitle.text
            return
        }
        btnToggle.isEnabled = true
        val now = System.currentTimeMillis()
        val running = MonitorService.running && TargetPreferences.definition(this, TargetId.TELEGRAM).enabled
        val s = MonitorService.state
        val stale = running && s.isStale(now)
        val notifOk = notificationsAllowed()
        val batteryOk = ignoringBatteryOptimizations()
        val alarmOk = Prefs.exactAlarmsAllowed(this)

        MainSetupUi.render(this, notifOk, batteryOk, alarmOk)
        TargetLegacySettingsUi.refresh(this, intervalButtons) { changingProfile = it }

        if (targetUi.selected == TargetId.TELEGRAM) renderTelegramStatus(s, now, running, stale)
        historyUi.refresh(historyDays, now)
        if (tvDiagnostics.visibility == View.VISIBLE && targetUi.selected == TargetId.TELEGRAM) {
            tvDiagnostics.text = s.diagnostics.ifEmpty { this@MainActivity.renderString(
                R.string.copy_main_diagnostics_will_appear_after) } +
                this@MainActivity.renderString(
                    R.string.copy_main_last_result_interval_s,
                    agoStr(this@MainActivity, s.checkedAt, now),
                    s.expectedIntervalSec)
        }
        val log = EventLog.all(this)
        if (log !== shownLog) {
            shownLog = log
            tvLog.text = if (log.isEmpty()) this@MainActivity.renderString(R.string.copy_main_nothing_yet)
                else log.joinToString("\n")
        }

        if (notifOk) {
            tvNotif.text = this@MainActivity.renderString(R.string.copy_main_notifications_are_on_the)
            btnNotif.visibility = View.GONE
        } else {
            tvNotif.text = this@MainActivity.renderString(R.string.copy_main_notifications_are_off_so)
            btnNotif.visibility = View.VISIBLE
        }

        if (batteryOk) {
            tvBattery.text = this@MainActivity.renderString(R.string.copy_main_unrestricted_background_use_is)
            btnBattery.visibility = View.GONE
        } else {
            tvBattery.text = this@MainActivity.renderString(R.string.copy_main_android_may_put_the)
            btnBattery.visibility = View.VISIBLE
        }

        if (alarmOk) {
            tvAlarm.text = this@MainActivity.renderString(R.string.copy_main_exact_alarms_are_allowed)
            btnAlarm.visibility = View.GONE
        } else {
            tvAlarm.text = this@MainActivity.renderString(R.string.copy_main_without_exact_alarms_android)
            btnAlarm.visibility = View.VISIBLE
        }
        targetUi.render(now)
    }

    private fun updateProfileLabel() {
        btnProfile.text = TargetLegacySettingsUi.profileLabel(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        targetUi.save(outState)
        outState.putInt("history_days", historyDays)
        outState.putBoolean("advanced_expanded", advancedExpanded)
        csvExport.save(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        targetUi.open(intent)
    }

    @Deprecated("Platform activity result API for dependency-free native UI")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (backupUi.onResult(requestCode, resultCode, data)) return
        if (LocalDiagnostics.onActivityResult(this, requestCode, resultCode, data)) return
        csvExport.onResult(requestCode, resultCode, data)
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

