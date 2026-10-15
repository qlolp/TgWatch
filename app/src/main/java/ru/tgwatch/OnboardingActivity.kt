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

/**
 * Показ мастера первого запуска: только если он ещё не показывался и приложение не настраивали.
 * Проверка уже шла (`last_checked`) — значит, это обновление, а не новая установка.
 */
object Onboarding {
    internal const val KEY_SEEN = "onboarding_seen"

    internal fun shouldShow(seen: Boolean, alreadyConfigured: Boolean): Boolean = !seen && !alreadyConfigured

    fun shouldShow(ctx: Context): Boolean {
        val sp = Prefs.sp(ctx)
        val configured = CONFIGURED_KEYS.any { sp.contains(it) }
        return shouldShow(sp.getBoolean(KEY_SEEN, false), configured)
    }

    fun markSeen(ctx: Context) { Prefs.sp(ctx).edit().putBoolean(KEY_SEEN, true).apply() }

    fun needsNotificationPermission(ctx: Context) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    /** Спрашивает разрешение на уведомления (Android 13+), если его ещё нет; `true` — запрос отправлен. */
    fun requestNotifications(activity: Activity, requestCode: Int = 1): Boolean =
        needsNotificationPermission(activity).also {
            if (it) activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestCode)
        }

    private val CONFIGURED_KEYS = listOf("enabled", "power_profile", "interval_sec", "last_checked")
}

/** Короткий мастер: что проверяется, уведомления, работа в фоне. Можно пропустить. */
class OnboardingActivity : Activity() {
    private data class Page(val title: String, val body: String, val action: String?, val run: (() -> Unit)?)

    private lateinit var pages: List<Page>
    private var index = 0
    private var askedNotifications = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.update(this)
        setContentView(R.layout.activity_onboarding)
        pages = buildPages()
        index = savedInstanceState?.getInt(STATE_INDEX, 0)?.coerceIn(0, pages.size - 1) ?: 0
        askedNotifications = savedInstanceState?.getBoolean(STATE_ASKED, false) ?: false
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
        outState.putBoolean(STATE_ASKED, askedNotifications)
    }

    private fun buildPages() = listOf(
        Page(
            Lang.t("Что проверяется", "What is checked"),
            Lang.t(
                "TG Монитор сам, без аккаунта и серверов автора, раз в несколько секунд проверяет " +
                    "доступность серверов Telegram с вашего телефона и ведёт историю только на устройстве.",
                "TG Monitor checks Telegram server reachability from your phone every few seconds, " +
                    "with no account and no author-run servers. History stays on the device."
            ), null, null
        ),
        Page(
            Lang.t("Уведомления", "Notifications"),
            Lang.t(
                "Уведомления нужны, чтобы вы узнали о сбое и восстановлении связи. " +
                    "Без них Android 13+ скрывает и значок службы.",
                "Notifications tell you about outages and recoveries. " +
                    "Without them Android 13+ also hides the service icon."
            ),
            Lang.t("Разрешить уведомления", "Allow notifications")
        ) { requestNotifications() },
        Page(
            Lang.t("Работа в фоне", "Background use"),
            Lang.t(
                "Чтобы Android не «усыплял» проверки при выключенном экране, " +
                    "снимите ограничения батареи.",
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
        if (Onboarding.requestNotifications(this, REQUEST_PAGE)) askedNotifications = true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_FINISH) finish()
    }

    private fun openBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(packageName) == true) return
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        try { startActivity(direct) } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                OemTips.openAppDetails(this)
            }
        }
    }

    /** Пропуск мастера не должен оставлять без уведомлений: если разрешение ещё не спрашивали, спросим сейчас. */
    private fun finishOnboarding() {
        Onboarding.markSeen(this)
        if (askedNotifications || !Onboarding.requestNotifications(this, REQUEST_FINISH)) finish()
    }

    companion object {
        private const val STATE_INDEX = "page"
        private const val STATE_ASKED = "asked_notifications"
        private const val REQUEST_PAGE = 1
        private const val REQUEST_FINISH = 2
        fun open(ctx: Context) { ctx.startActivity(Intent(ctx, OnboardingActivity::class.java)) }

        /** Открывает мастер на чистой установке; возвращает `true`, если открыл. */
        fun openIfFirstRun(ctx: Context): Boolean = Onboarding.shouldShow(ctx).also { if (it) open(ctx) }
    }
}
