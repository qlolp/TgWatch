package ru.tgwatch

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.widget.Button
import android.widget.TextView

@RunWith(AndroidJUnit4::class)
class IncidentCenterUiTest {
    @Test fun incidentCenterEntryRemainsVisibleWithoutChangeSummary() {
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = Prefs.sp(ctx)
        val previous = prefs.all["onboarding_seen"] as? Boolean
        assertTrue(prefs.edit().putBoolean("onboarding_seen", true).commit())
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<android.view.View>(R.id.changeSummaryCard).visibility = android.view.View.GONE
                    val button = activity.findViewById<Button>(R.id.btnIncidentCenter)
                    assertTrue(button.isShown)
                    assertTrue(button.hasOnClickListeners())
                }
            }
        } finally {
            val edit = prefs.edit()
            if (previous == null) edit.remove("onboarding_seen") else edit.putBoolean("onboarding_seen", previous)
            assertTrue(edit.commit())
        }
    }

    @Test fun centerProvidesCoverageTimelinePeriodAndSafeComparisonAction() {
        ActivityScenario.launch(IncidentCenterActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.incident_center_title), activity.title)
                assertTrue(activity.findViewById<TextView>(R.id.incidentCenterCoverage).text.isNotBlank())
                assertEquals(activity.getString(R.string.regional_compare_title),
                    activity.findViewById<Button>(R.id.incidentCenterCompare).text.toString())
                val period = activity.findViewById<Button>(R.id.incidentCenterPeriod)
                assertEquals(activity.getString(R.string.period_day), period.text.toString())
                period.performClick()
                assertEquals(activity.getString(R.string.period_week), period.text.toString())
            }
        }
    }
}
