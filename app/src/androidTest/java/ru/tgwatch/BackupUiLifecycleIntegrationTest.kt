package ru.tgwatch

import android.app.Dialog
import android.widget.TextView
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupUiLifecycleIntegrationTest {
    @Test fun preparedImportReappearsAfterActivityRecreation() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val wasEnabled = Prefs.isEnabled(ctx)
        val wasSeen = Prefs.sp(ctx).all[Onboarding.KEY_SEEN] as? Boolean
        MonitorService.stopAndAwait(ctx)
        Prefs.setEnabled(ctx, false)
        Onboarding.markSeen(ctx)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertTrue(BackupJobs.coordinator.start(BackupJobKind.READ_BACKUP) { _, _ ->
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    BackupJobResult.Import(TargetPortableBackup.legacy(
                        BackupSnapshot(0, emptyMap(), emptyList(), emptyList())))
                })
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                scenario.recreate()
                release.countDown()
                await { BackupJobs.coordinator.snapshot()?.phase == BackupJobPhase.SUCCEEDED }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { assertTrue("The new screen must display the prepared import", hasPreview(it)) }
                scenario.recreate()
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { assertTrue("The preview must survive another recreation", hasPreview(it)) }
                val id = requireNotNull(BackupJobs.coordinator.snapshot()).id
                assertTrue(BackupJobs.coordinator.dismiss(id))
            }
        } finally {
            release.countDown()
            BackupJobs.coordinator.snapshot()?.let { BackupJobs.coordinator.cancel(it.id) }
            await { BackupJobs.coordinator.snapshot()?.physicalRunning != true }
            BackupJobs.coordinator.snapshot()?.let { BackupJobs.coordinator.dismiss(it.id) }
            Prefs.setEnabled(ctx, wasEnabled)
            Prefs.sp(ctx).edit().apply {
                if (wasSeen == null) remove(Onboarding.KEY_SEEN) else putBoolean(Onboarding.KEY_SEEN, wasSeen)
            }.commit()
        }
    }

    private fun hasPreview(activity: MainActivity): Boolean {
        val field = MainActivity::class.java.getDeclaredField("backupUi").apply { isAccessible = true }
        val ui = field.get(activity)
        val dialogs = BackupUi::class.java.getDeclaredField("dialogs").apply { isAccessible = true }
        val expected = activity.renderString(R.string.copy_backup_ui_targets_history_records_data, 4, 0)
        return (dialogs.get(ui) as List<*>).filterIsInstance<Dialog>().any {
            it.isShowing && it.findViewById<TextView>(android.R.id.message)?.text?.toString() == expected
        }
    }
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 5000
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(10)
        assertTrue(condition())
    }
}
