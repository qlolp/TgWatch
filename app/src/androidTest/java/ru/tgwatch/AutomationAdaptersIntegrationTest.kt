package ru.tgwatch

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomationAdaptersIntegrationTest {
    @Test fun webhookMqttAndCrashControlsCoexistWithDistinctIds() {
        ActivityScenario.launch(AutomationSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controls = mapOf(
                    "webhook receiver" to AutomationSettingsActivity.ID_WEBHOOK_RECEIVER,
                    "ntfy preset" to AutomationSettingsActivity.ID_WEBHOOK_NTFY,
                    "MQTT enable" to AutomationSettingsActivity.ID_MQTT_ENABLE,
                    "MQTT status" to AutomationSettingsActivity.ID_MQTT_STATUS,
                    "MQTT settings" to AutomationSettingsActivity.ID_MQTT_SET,
                    "MQTT test" to AutomationSettingsActivity.ID_MQTT_TEST,
                    "crash reporting enable" to AutomationSettingsActivity.ID_CRASH_REPORTING_ENABLE,
                    "crash reporting endpoint" to AutomationSettingsActivity.ID_CRASH_REPORTING_ENDPOINT,
                    "crash reporting settings" to AutomationSettingsActivity.ID_CRASH_REPORTING_SET,
                )
                assertEquals("Integrated adapters must not reuse view IDs",
                    controls.size, controls.values.toSet().size)
                controls.forEach { (label, id) ->
                    assertNotNull("Missing $label control after adapter integration",
                        activity.findViewById<View>(id))
                }
            }
        }
    }
}
