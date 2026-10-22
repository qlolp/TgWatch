package ru.tgwatch

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Public reachability and explicit regional responses, separate from authenticated feature readiness. */
class RegionalServicesActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val labels = mutableMapOf<RegionalService, TextView>()
    private val switches = mutableMapOf<RegionalService, Switch>()
    private lateinit var country: TextView
    private lateinit var verification: RegionalVerificationUi
    private var writing = false
    private var binding = false
    private val ticker = object : Runnable {
        override fun run() { render(); ui.postDelayed(this, REDRAW_MS) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.regional_title)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (PADDING_DP * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        ScreenInsets.apply(this, scroll)
        content.addView(TextView(this).apply { setText(R.string.regional_explanation) })
        country = TextView(this)
        content.addView(country)
        button(content, R.string.country_refresh) { EgressCountryUi.refreshActive(this, force = true) }
        button(content, R.string.regional_history_title) {
            startActivity(Intent(this, RegionalHistoryActivity::class.java))
        }
        button(content, R.string.regional_compare_title) {
            startActivity(Intent(this, RegionalComparisonActivity::class.java))
        }
        verification = RegionalVerificationUi(this)
        RegionalService.entries.forEach { service -> addProfile(content, service) }
        button(content, R.string.regional_check) {
            if (!MonitorService.requestRegionalCheck())
                Toast.makeText(this, R.string.regional_enable_first, Toast.LENGTH_SHORT).show()
        }
        button(content, R.string.regional_channel_settings) {
            RegionalNotifications(this)
            startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, RegionalNotifications.CHANNEL))
        }
        content.addView(TextView(this).apply { setText(R.string.regional_maintenance) })
        MaintenanceSettingsUi.add(this, content,
            { MaintenanceWindows.load(this, RegionalPreferences.MAINTENANCE) },
            { MaintenanceWindows.save(this, RegionalPreferences.MAINTENANCE, it) })
        button(content, R.string.regional_mute) { Prefs.muteAlerts(this) }
    }

    private fun button(content: LinearLayout, text: Int, action: () -> Unit) {
        content.addView(Button(this).apply {
            setText(text)
            minimumHeight = (TOUCH_DP * resources.displayMetrics.density).toInt()
            setOnClickListener { action() }
        })
    }

    private fun addProfile(content: LinearLayout, service: RegionalService) {
        val toggle = Switch(this).apply {
            text = service.title
            minimumHeight = (TOUCH_DP * resources.displayMetrics.density).toInt()
            isChecked = RegionalPreferences.enabled(this@RegionalServicesActivity, service)
            isSaveEnabled = false
        }
        switches[service] = toggle
        content.addView(toggle)
        val label = TextView(this).apply { setText(R.string.regional_source_public) }
        labels[service] = label
        content.addView(label)
        content.addView(Button(this).apply {
            text = getString(R.string.regional_open, service.title)
            minimumHeight = (TOUCH_DP * resources.displayMetrics.density).toInt()
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(service.address))) }
                    .onFailure { Toast.makeText(this@RegionalServicesActivity, R.string.regional_open_failed,
                        Toast.LENGTH_SHORT).show() }
            }
        })
        button(content, R.string.regional_why) { RegionalExplanationUi.show(this, service) }
        verification.add(content, service)
        toggle.setOnCheckedChangeListener { _, checked -> configure(service, checked) }
    }

    private fun configure(service: RegionalService, enabled: Boolean) {
        if (writing || binding) return
        writing = true
        switches.values.forEach { it.isEnabled = false }
        val revision = RegionalPreferences.revision(this)
        val changed = java.util.concurrent.atomic.AtomicBoolean()
        HistorySessions.queue(this).exclusive { synchronized(History) {
            changed.set(RegionalPreferences.setEnabled(this, service, enabled, revision))
            if (changed.get() && enabled) Prefs.setEnabled(this, true)
        } }.whenComplete { _, failure -> ui.post {
            writing = false
            if (changed.get() && failure == null) TargetActions.reconcile(applicationContext)
            if (isDestroyed || isFinishing) return@post
            if (!changed.get() || failure != null)
                Toast.makeText(this, R.string.target_settings_busy, Toast.LENGTH_SHORT).show()
            recreate()
        } }
    }

    private fun render() {
        binding = true
        if (!writing) switches.forEach { (service, toggle) ->
            toggle.isChecked = RegionalPreferences.enabled(this, service)
        }
        binding = false
        EgressCountryUi.render(country, this)
        labels.forEach { (service, label) ->
            val result = RegionalCurrent.result(this, service)
            val detail = when {
                !RegionalPreferences.enabled(this, service) -> getString(R.string.regional_off)
                result == null -> getString(R.string.regional_unverified)
                else -> getString(R.string.regional_result, resultText(result),
                    agoStr(this, result.checkedAt, System.currentTimeMillis()))
            }
            label.text = getString(R.string.regional_verification_row,
                getString(R.string.regional_source_public), detail)
        }
        verification.render()
    }

    private fun resultText(result: RegionalResult): String {
        val status = if (result.regionConfirmed) R.string.regional_confirmed else statusString(result.status)
        val text = getString(status)
        return result.httpCode?.let { getString(R.string.regional_http, text, it) } ?: text
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.isEnabled(this) && RegionalPreferences.anyEnabled(this) && !MonitorService.running)
            runCatching { MonitorService.start(this) }.onFailure {
                UiErrors.show(this, R.string.copy_main_couldn_t_start_monitoring, it)
            }
        ui.post(ticker)
        EgressCountryUi.refreshActive(this)
    }
    override fun onPause() { ui.removeCallbacks(ticker); super.onPause() }

    private fun statusString(status: RegionalStatus): Int = when (status) {
        RegionalStatus.WEB_REACHABLE -> R.string.regional_web_reachable
        RegionalStatus.REGION_RESTRICTED -> R.string.regional_restricted
        RegionalStatus.AUTH_REQUIRED -> R.string.regional_auth
        RegionalStatus.CHALLENGE -> R.string.regional_challenge
        RegionalStatus.RATE_LIMITED -> R.string.regional_rate
        RegionalStatus.NETWORK_ERROR -> R.string.regional_network
        RegionalStatus.UNKNOWN -> R.string.regional_unverified
    }

    private companion object {
        const val REDRAW_MS = 1_000L
        const val TOUCH_DP = 48
        const val PADDING_DP = 16
    }
}
