package ru.tgwatch

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidSmokeTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun historyUsesDeviceProtectedStorageAndExports() {
        History.record(ctx, System.currentTimeMillis(), History.Kind.PARTIAL, 30, 30)
        val file = File(ctx.createDeviceProtectedStorageContext().filesDir, "history-v2.csv")
        assertTrue(file.exists())
        assertTrue(History.exportCsv(ctx).startsWith("from_utc,"))
        assertTrue(History.exportCsv(ctx).contains("PARTIAL"))
    }
    @Test fun profilesAndDiagnosticStateSurvivePreferencesReload() {
        Prefs.setProfile(ctx, PowerProfile.ECONOMY)
        assertEquals(300, Prefs.effectiveIntervalSec(ctx, false, true))
        val now = System.currentTimeMillis()
        Prefs.saveLastState(ctx, MonitorService.Status.PARTIAL, now, now, 10, "partial", 0, "Wi-Fi test", 60)
        val restored = Prefs.loadLastState(ctx)!!
        assertEquals(MonitorService.Status.PARTIAL, restored.status)
        assertEquals("Wi-Fi test", restored.diagnostics)
        assertEquals(60, restored.expectedIntervalSec)
        Prefs.setProfile(ctx, PowerProfile.BALANCED)
    }
}
