package ru.tgwatch

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File

/** Replays validated, durable data on the single history worker. */
internal class BackupRecovery(private val clearedSettings: Set<String>) {
    fun recover(ctx: Context, pending: File, published: File): Boolean {
        RestoreTransaction(pending).migrateLegacy(published,
            { bytes -> BackupCodec.encode(PortableBackupCodec.decodePublishedPayload(bytes)) },
            { bytes -> BackupCodec.decode(bytes) })
        return RestoreTransaction(pending).recover { payload ->
            val restored = BackupCodec.decode(payload)
            History.replaceSnapshot(ctx, restored.observations)
            val edit = Prefs.sp(ctx).edit().putBoolean("enabled", false)
            clearedSettings.forEach { edit.remove(it) }
            restored.settings.forEach { (key, value) ->
                when (value) {
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is String -> edit.putString(key, value)
                    else -> error("Invalid setting")
                }
            }
            check(edit.commit()) { "Could not restore settings" }
            EventLog.replace(ctx, restored.log)
            LastSuccessStore.clear(ctx)
            MonitorService.clearRestoredState()
            val preferencesDirectory = File(ctx.applicationContext.createDeviceProtectedStorageContext()
                .dataDir, "shared_prefs")
            val descriptor = Os.open(preferencesDirectory.absolutePath, OsConstants.O_RDONLY, 0)
            try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
            StatusWidget.updateAll(ctx)
            DailySummaryReceiver.schedule(ctx)
        }
    }
}
