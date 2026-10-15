package ru.tgwatch

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView

/** Показ мастера первого запуска: только если он ещё не показывался и приложение не настраивали. */
object Onboarding {
    internal const val KEY_SEEN = "onboarding_seen"

    internal fun shouldShow(seen: Boolean, alreadyConfigured: Boolean): Boolean = !seen && !alreadyConfigured

    fun shouldShow(ctx: Context): Boolean {
        val sp = Prefs.sp(ctx)
        val configured = sp.contains("enabled") || sp.contains("power_profile") || sp.contains("interval_sec")
        return shouldShow(sp.getBoolean(KEY_SEEN, false), configured)
    }

    fun markSeen(ctx: Context) { Prefs.sp(ctx).edit().putBoolean(KEY_SEEN, true).apply() }
}

/** Короткий мастер: что проверяется, уведомления, работа в фоне. Можно пропустить. */
class OnboardingActivity : Activity() {
    private data class Page(val title: String, val body: String, val action: String?, val run: (() -> Unit)?)

    private lateinit var pages: List<Page>
    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.update(this)
        setContentView(R.layout.activity_onboarding)
        pages = buildPages()
        index = savedInstanceState?.getInt(STATE_INDEX, 0)?.coerceIn(0, pages.size - 1) ?: 0
        findViewById<Button>(R.id.btnOnboardingSkip).setOnClickListener { finishOnboarding() }
        findViewById<Button>(R.id.btnOnboardingNext).setOnClickListener {
            if (index == pages.lastIndex) finishOnboarding() else { index++; render() }
        }
        findViewById<Button>(R.id.btnOnboardingAction).setOnClickListener { pages[index].run?.invoke() }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_INDEX, index)
    }

    private fun buildPages() = listOf(
        Page(
            Lang.t("Что проверяется", "What is checked"),
            Lang.t(
                "TG Монитор сам, без аккаунта и серверов автора, раз в несколько секунд проверяет доступность серверов Telegram с вашего телефона и ведёт историю только на устройстве.",
                "TG Monitor checks Telegram server reachability from your phone every few seconds, with no account and no author-run servers. History stays on the device."
            ), null, null
        ),
        Page(
            Lang.t("Уведомления", "Notifications"),
            Lang.t(
                "Уведомления нужны, чтобы вы узнали о сбое и восстановлении связи. Без них Android 13+ скрывает и значок службы.",
                "Notifications tell you about outages and recoveries. Without them Android 13+ also hides the service icon."
            ),
            Lang.t("Разрешить уведомления", "Allow notifications")
        ) { requestNotifications() },
        Page(
            Lang.t("Работа в фоне", "Background use"),
            Lang.t(
                "Чтобы Android не «усыплял» проверки при выключенном экране, снимите ограничения батареи.",
                "So Android does not put checks to sleep while the screen is off, remove battery restrictions."
            ) + (OemTips.manufacturerHint()?.let { "\n\n$it" } ?: ""),
            Lang.t("Снять ограничения батареи", "Remove battery restrictions")
        ) { openBattery() }
    )

    private fun render() {
        val page = pages[index]
        findViewById<TextView>(R.id.tvOnboardingTitle).text = page.title
        findViewById<TextView>(R.id.tvOnboardingBody).text = page.body
        findViewById<TextView>(R.id.tvOnboardingStep).text = "${index + 1} / ${pages.size}"
        val action = findViewById<Button>(R.id.btnOnboardingAction)
        action.visibility = if (page.action == null) View.GONE else View.VISIBLE
        action.text = page.action.orEmpty()
        findViewById<Button>(R.id.btnOnboardingNext).text =
            if (index == pages.lastIndex) Lang.t("Готово", "Done") else Lang.t("Далее", "Next")
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun openBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(packageName) == true) return
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        try { startActivity(direct) } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { OemTips.openAppDetails(this) }
        }
    }

    private fun finishOnboarding() {
        Onboarding.markSeen(this)
        finish()
    }

    companion object {
        private const val STATE_INDEX = "page"
        fun open(ctx: Context) { ctx.startActivity(Intent(ctx, OnboardingActivity::class.java)) }
    }
}
