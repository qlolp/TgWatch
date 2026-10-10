package ru.tgwatch

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {
    @Test fun encryptedRestoreReplacesOnlyPortableDataAndSurvivesJournalReplay() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        Prefs.setEnabled(ctx,false)
        MonitorService.stop(ctx)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val original = BackupStore.snapshot(ctx)
        val now = System.currentTimeMillis()
        val replacement = BackupData(now,listOf(Observation(now-1000,now-500,"TG_DOWN")),
            mapOf("power_profile" to "ECONOMY", "interval_sec" to "60", "vibrate_partial" to "true",
                "quiet_start_hour" to "22", "quiet_end_hour" to "7"),listOf("Сохранённое событие"))
        try {
            Prefs.sp(ctx).edit().putLong("last_checked",now).putString("last_status","OK").commit()
            val encrypted = BackupCodec.encrypt(replacement,"Пароль для теста!".toCharArray())
            val beforeBadPassword = BackupStore.snapshot(ctx)
            try { BackupCodec.decrypt(encrypted,"Другой пароль!!".toCharArray()); fail("Wrong password accepted") }
            catch (_: java.security.GeneralSecurityException) { }
            assertEquals(beforeBadPassword.samples,BackupStore.snapshot(ctx,beforeBadPassword.createdAt).samples)
            assertEquals(beforeBadPassword.settings,BackupStore.snapshot(ctx).settings)
            BackupStore.restore(ctx,BackupCodec.decrypt(encrypted,"Пароль для теста!".toCharArray()))
            assertFalse(Prefs.isEnabled(ctx))
            assertNull(Prefs.loadLastState(ctx))
            assertEquals(0L,MonitorService.state.checkedAt)
            assertEquals(60,Prefs.intervalSec(ctx))
            assertTrue(Prefs.vibratePartial(ctx))
            assertEquals(listOf("Сохранённое событие"),EventLog.all(ctx))
            assertEquals(replacement.samples,BackupStore.snapshot(ctx).samples)
            assertFalse(BackupStore.snapshot(ctx).settings.containsKey("enabled"))
            val journal = java.io.File(ctx.createDeviceProtectedStorageContext().filesDir,"restore.pending")
            journal.writeBytes(BackupCodec.encode(replacement))
            Prefs.sp(ctx).edit().putInt("interval_sec",10).commit() // sp replays before returning.
            journal.writeBytes(BackupCodec.encode(replacement))
            assertEquals(60,Prefs.intervalSec(ctx))
            assertFalse(journal.exists())
        } finally { BackupStore.restore(ctx,original) }
    }
}
