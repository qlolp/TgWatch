package ru.tgwatch

import android.view.WindowManager
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Seeds only an in-memory fake coordinator; this fixture never opens or posts to any endpoint. */
@RunWith(AndroidJUnit4::class)
class WebhookHealthUiIntegrationTest {
    @Test fun settingsShowsPrivateTimeoutAndStillRunningWithoutSavingHealthOrSecrets() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val previousEnabled = Prefs.isEnabled(ctx)
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        assertFalse(MonitorService.running)
        val field = WebhookNotifier::class.java.getDeclaredField("coordinator").apply { isAccessible = true }
        val previousCoordinator = field.get(WebhookNotifier)
        var now = 0L
        var deadline: (() -> Unit)? = null
        var finish: ((Int) -> Unit)? = null
        val secret = "native-private-topic-never-posted"
        val coordinator = WebhookCoordinator(Any(), {
            WebhookConfiguration("https://example.org/$secret", false, 0)
        }, { now }, { _, _, done -> finish = done; true }, { _, action -> deadline = action; {} })
        try {
            field.set(WebhookNotifier, coordinator)
            coordinator.sendTest {}
            now = WebhookPolicy.RESULT_DEADLINE_MS
            deadline!!.invoke()
            ActivityScenario.launch(AutomationSettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val health = activity.findViewById<TextView>(AutomationSettingsActivity.ID_WEBHOOK_HEALTH)
                    assertTrue(health.text.contains(Lang.t("время ожидания истекло", "timed out")))
                    assertTrue(health.text.contains(Lang.t("Запрос ещё выполняется: да", "Request still running: yes")))
                    assertTrue(health.text.contains(Lang.t("Доставка заблокирована", "Delivery blocked")))
                    assertFalse(health.text.contains(secret))
                    assertFalse(health.isSaveEnabled)
                    assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    assertFalse(BackupManager.snapshot(ctx).settings.values.any { it.toString().contains(secret) })
                    assertFalse(LocalDiagnostics.snapshot(ctx).toJson().contains(secret))
                }
            }
        } finally {
            finish?.invoke(204)
            field.set(WebhookNotifier, previousCoordinator)
            Prefs.setEnabled(ctx, previousEnabled)
        }
    }
}
