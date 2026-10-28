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
        var now = 0L
        var deadline: (() -> Unit)? = null
        var finish: ((Int) -> Unit)? = null
        val secret = "native-private-topic-never-posted"
        val coordinator = WebhookCoordinator(Any(), {
            WebhookConfiguration("https://example.org/$secret", false, 0)
        }, { now }, { _, _, done -> finish = done; true }, { _, action -> deadline = action; {} })
        val previousCoordinator = WebhookNotifier.replaceCoordinatorForTest(0, coordinator)
        try {
            coordinator.sendTest {}
            now = WebhookPolicy.RESULT_DEADLINE_MS
            deadline!!.invoke()
            ActivityScenario.launch(AutomationSettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val health = activity.findViewById<TextView>(AutomationSettingsActivity.ID_WEBHOOK_HEALTH)
                    assertTrue(health.text.contains((if (ctx.getString(R.string.lang) == "ru") "время ожидания истекло" else "timed out")))
                    assertTrue(health.text.contains((if (ctx.getString(R.string.lang) == "ru") "Запрос ещё выполняется: да" else "Request still running: yes")))
                    assertTrue(health.text.contains((if (ctx.getString(R.string.lang) == "ru") "Доставка заблокирована" else "Delivery blocked")))
                    assertFalse(health.text.contains(secret))
                    assertFalse(health.isSaveEnabled)
                    assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    assertFalse(BackupManager.snapshot(ctx).settings.values.any { it.toString().contains(secret) })
                    assertFalse(LocalDiagnostics.snapshot(ctx).toJson().contains(secret))
                }
            }
        } finally {
            finish?.invoke(204)
            WebhookNotifier.replaceCoordinatorForTest(0, previousCoordinator)
            Prefs.setEnabled(ctx, previousEnabled)
        }
    }
}
