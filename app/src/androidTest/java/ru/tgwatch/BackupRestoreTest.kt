package ru.tgwatch

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/** Published-main regressions retained against the single reconciled manager. */
@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun stoppedSnapshot(): BackupSnapshot {
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        return BackupManager.snapshot(ctx)
    }

    @Test fun expiredCurrentEpochNeverPromotesArchivedClockForwardRows() {
        val original = stoppedSnapshot()
        val now = System.currentTimeMillis()
        val old = now - 8 * 86_400_000L
        val replacement = BackupSnapshot(old, emptyMap(), listOf(
            Observation(now-30_000, now-20_000, "OK", clockEpoch=0),
            Observation(now-20_000, now-10_000, "TG_DOWN", clockEpoch=0),
            Observation(now-10_000, now-1000, "OK", clockEpoch=0),
            Observation(old-1000, old, "OK", clockEpoch=1)), emptyList())
        try {
            BackupManager.restore(ctx, replacement)
            repeat(2) {
                val stats = History.stats(ctx, 7, now)
                assertEquals(0L, stats.downMs)
                assertEquals(0, stats.completedOutages)
                assertEquals(0, stats.checks)
                val snapshot = History.snapshot(ctx, now)
                assertEquals(1L, Timeline.current(snapshot).single().clockEpoch)
                assertEquals("UNKNOWN", Timeline.current(snapshot).single().kind)
                History.flush(ctx)
                History::class.java.getDeclaredField("loaded").apply { isAccessible=true }.setBoolean(History, false)
            }
            BackupManager.restore(ctx, BackupSnapshot(now+2000, emptyMap(), listOf(
                Observation(now-20_000, now-10_000, "TG_DOWN", clockEpoch=0),
                Observation(now+1000, now+2000, "OK", clockEpoch=1)), emptyList()))
            val clipped = History.snapshot(ctx, now)
            assertEquals(2L, Timeline.current(clipped).single().clockEpoch)
            assertEquals(0, Timeline.stats(clipped, now-86_400_000L, now).checks)
            assertEquals(0L, Timeline.stats(clipped, now-86_400_000L, now).downMs)
        } finally { BackupManager.restore(ctx, original) }
    }

    @Test fun encryptedRestorePreservesIndependentAlertsMinutesAndReplay() {
        val original = stoppedSnapshot()
        val now = System.currentTimeMillis()
        val replacement = BackupSnapshot(now, mapOf("power_profile" to "CUSTOM", "interval_sec" to 60,
            "vibrate_partial" to true, "quiet_start_minute" to 1395, "quiet_end_minute" to 525,
            "notify_outage" to false, "notify_partial" to true, "notify_recovery" to false,
            "sound_outage" to false, "sound_partial" to true, "sound_recovery" to true),
            listOf(Observation(now-1000, now-500, "TG_DOWN")), listOf("Сохранённое событие"))
        try {
            Prefs.rawSp(ctx).edit().putLong("last_checked", now).putString("last_status", "OK").commit()
            val encrypted = PortableBackupCodec.encrypt(replacement, "Пароль для теста!".toCharArray())
            val before = BackupManager.snapshot(ctx)
            try { BackupManager.decrypt(encrypted, "Другой пароль!!".toCharArray()); fail("Wrong password accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals(before.observations, BackupManager.snapshot(ctx).observations)
            assertEquals(before.settings, BackupManager.snapshot(ctx).settings)
            BackupManager.restore(ctx, BackupManager.decrypt(encrypted, "Пароль для теста!".toCharArray()))
            assertFalse(Prefs.isEnabled(ctx))
            assertNull(Prefs.loadLastState(ctx))
            assertEquals(0L, MonitorService.state.checkedAt)
            assertEquals(60, Prefs.intervalSec(ctx))
            assertTrue(Prefs.vibratePartial(ctx))
            assertEquals(1395, Prefs.quietStartMinute(ctx))
            assertEquals(525, Prefs.quietEndMinute(ctx))
            assertFalse(Prefs.notifyFor(ctx, "TG_DOWN"))
            assertTrue(Prefs.notifyFor(ctx, "PARTIAL"))
            assertTrue(Prefs.soundFor(ctx, "PARTIAL"))
            assertTrue(Prefs.soundFor(ctx, "RECOVERY"))
            assertEquals(replacement.log, EventLog.all(ctx))
            assertEquals(replacement.observations, BackupManager.snapshot(ctx).observations)
            assertFalse(BackupManager.snapshot(ctx).settings.containsKey("enabled"))
            File(ctx.createDeviceProtectedStorageContext().filesDir, "restore-pending-v1.json")
                .writeBytes(BackupCodec.encode(replacement))
            Prefs.rawSp(ctx).edit().putInt("interval_sec", 10).commit()
            assertTrue(BackupManager.recoverPending(ctx))
            assertEquals(60, Prefs.intervalSec(ctx))
            assertFalse(BackupManager.hasPending(ctx))
        } finally { BackupManager.restore(ctx, original) }
    }

    @Test fun publishedBinaryJournalMigratesBeforeMonitoringAndKeepsEventChoices() {
        val original = stoppedSnapshot()
        val now = System.currentTimeMillis()
        val rows = listOf(Observation(now-1000, now-500, "TG_DOWN"))
        val settings = mapOf("power_profile" to "CUSTOM", "interval_sec" to "60", "quiet_start_hour" to "22",
            "quiet_end_hour" to "7", "event_sound" to "true", "notify_outage" to "false",
            "notify_partial" to "true", "sound_partial" to "true", "sound_recovery" to "false")
        val payload = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.writeInt(0x54474431); out.writeLong(now); out.writeInt(settings.size)
            settings.forEach { (key, value) -> out.writeUTF(key); out.writeUTF(value) }
            out.writeInt(rows.size); rows.forEach {
                out.writeLong(it.at); out.writeLong(it.until); out.writeUTF(it.kind)
                out.writeLong(it.latencyMs); out.writeLong(it.clockEpoch)
            }
            out.writeInt(1); out.writeUTF("Событие из 1.10.47")
        } }.toByteArray()
        val journal = File(ctx.createDeviceProtectedStorageContext().filesDir, "restore.pending")
        try {
            journal.writeBytes(payload)
            assertTrue(BackupManager.hasPending(ctx))
            assertTrue(BackupManager.recoverPending(ctx))
            assertFalse(journal.exists())
            assertFalse(BackupManager.hasPending(ctx))
            assertFalse(Prefs.isEnabled(ctx))
            assertEquals(60, Prefs.intervalSec(ctx))
            assertEquals(22*60, Prefs.quietStartMinute(ctx))
            assertEquals(7*60, Prefs.quietEndMinute(ctx))
            assertFalse(Prefs.notifyFor(ctx, "TG_DOWN"))
            assertTrue(Prefs.notifyFor(ctx, "PARTIAL"))
            assertTrue(Prefs.soundFor(ctx, "PARTIAL"))
            assertFalse(Prefs.soundFor(ctx, "RECOVERY"))
            assertTrue(Prefs.soundFor(ctx, "TG_DOWN"))
            assertEquals(rows, BackupManager.snapshot(ctx).observations)
            assertEquals(listOf("Событие из 1.10.47"), EventLog.all(ctx))
        } finally { journal.delete(); BackupManager.restore(ctx, original) }
    }
}
