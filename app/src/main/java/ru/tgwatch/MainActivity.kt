package ru.tgwatch

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Экран приложения: статус, кнопки и настройки. */
class MainActivity : Activity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvDetails: TextView
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
        mapOf(15 to R.id.rb15, 30 to R.id.rb30, 60 to R.id.rb60, 120 to R.id.rb120)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvTitle = findViewById(R.id.tvTitle)
        tvDetails = findViewById(R.id.tvDetails)
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

        rgInterval.check(intervalButtons[Prefs.intervalSec(this)] ?: R.id.rb30)
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
        val running = MonitorService.running
        val s = MonitorService.state

        btnToggle.text = if (running) "Остановить мониторинг" else "Запустить мониторинг"
        btnCheck.isEnabled = running

        if (!running) {
            tvTitle.text = "Мониторинг выключен"
            tvTitle.setTextColor(COLOR_GRAY)
            tvDetails.text = "Нажми «Запустить мониторинг», и в строке состояния появится значок."
        } else {
            when (s.status) {
                MonitorService.Status.OK -> {
                    tvTitle.text = "✅ Telegram доступен"
                    tvTitle.setTextColor(COLOR_OK)
                }
                MonitorService.Status.TG_DOWN -> {
                    tvTitle.text = "❌ Telegram недоступен"
                    tvTitle.setTextColor(COLOR_FAIL)
                }
                MonitorService.Status.NO_NETWORK -> {
                    tvTitle.text = "📵 Нет интернета"
                    tvTitle.setTextColor(COLOR_FAIL)
                }
                MonitorService.Status.UNKNOWN -> {
                    tvTitle.text = "⏳ Проверяю…"
                    tvTitle.setTextColor(COLOR_GRAY)
                }
            }
            val sb = StringBuilder()
            sb.append("Проверяю адрес: ").append(MonitorService.CHECK_URL).append('\n')
            sb.append("Последняя проверка: ").append(timeStr(s.checkedAt)).append('\n')
            if (s.status == MonitorService.Status.OK) {
                sb.append("Время ответа: ").append(s.latencyMs).append(" мс\n")
            } else if (s.reason.isNotEmpty()) {
                sb.append("Причина: ").append(s.reason).append('\n')
            }
            sb.append("В этом состоянии с: ").append(timeStr(s.since)).append('\n')
            sb.append("Последняя вибрация: ").append(timeStr(s.lastVibrationAt))
            tvDetails.text = sb.toString()
        }

        if (ignoringBatteryOptimizations()) {
            tvBattery.text = "✅ Работа в фоне без ограничений разрешена"
            btnBattery.visibility = View.GONE
        } else {
            tvBattery.text = "⚠️ Android может «усыплять» приложение при выключенном экране — " +
                "тогда проверки будут останавливаться. Разреши работу без ограничений:"
            btnBattery.visibility = View.VISIBLE
        }

        if (notificationsAllowed()) {
            tvNotif.text = "✅ Уведомления включены — значок виден в строке состояния"
            btnNotif.visibility = View.GONE
        } else {
            tvNotif.text = "⚠️ Уведомления выключены — значка в строке состояния не будет:"
            btnNotif.visibility = View.VISIBLE
        }

        val log = MonitorService.log
        tvLog.text = if (log.isEmpty()) "Пока пусто" else log.joinToString("\n")
    }

    private companion object {
        val COLOR_OK = Color.rgb(0x2E, 0x7D, 0x32)
        val COLOR_FAIL = Color.rgb(0xC6, 0x28, 0x28)
        val COLOR_GRAY = Color.rgb(0x75, 0x75, 0x75)
    }
}
