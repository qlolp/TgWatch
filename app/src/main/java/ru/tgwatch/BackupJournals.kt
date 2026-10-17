package ru.tgwatch

import android.content.Context
import java.io.File

/** The disk scan is explicit and worker-only; component guards read the cached pending flag. */
internal object BackupJournals {
    private const val PUBLISHED_MAX_BYTES = 4 * 1024 * 1024
    @Volatile var pending = false
        private set

    fun refresh(ctx: Context) {
        pending = marker(ctx).exists() || legacyJson(ctx).exists() || legacyPublished(ctx).exists()
    }

    fun transaction(ctx: Context) = PrivateRestoreTransaction(marker(ctx), File(ctx.applicationContext.filesDir,
        "restore-private-v1"))

    fun migrateLegacy(ctx: Context) {
        val json = legacyJson(ctx)
        val published = legacyPublished(ctx)
        if (!marker(ctx).exists()) {
            val imported = when {
                json.exists() -> readLegacy(json, BackupCodec.MAX_PLAINTEXT_BYTES, BackupCodec::decode)
                published.exists() -> readLegacy(published, PUBLISHED_MAX_BYTES,
                    PortableBackupCodec::decodePublishedPayload)
                else -> return
            }
            val bytes = TargetBackupCodec.encode(imported.snapshot)
            try { transaction(ctx).stage(bytes, imported.legacyEndpoint) } finally { bytes.fill(0) }
        }
        // The CP transaction is validated by recover before application; keep legacy sources until then.
    }

    private fun readLegacy(file: File, limit: Int, decode: (ByteArray) -> BackupSnapshot): TargetBackupImport {
        val bytes = file.inputStream().use { BoundedInput.read(it, limit) }
        return try { TargetPortableBackup.legacy(decode(bytes)) } finally { bytes.fill(0) }
    }

    fun removeLegacy(ctx: Context) {
        for (file in listOf(legacyJson(ctx), legacyPublished(ctx))) {
            if (file.exists()) check(file.delete()) { "Cannot finish legacy restore migration" }
        }
        HistoryStorage.syncDirectory(marker(ctx).parentFile!!)
    }

    private fun marker(ctx: Context) = File(dp(ctx), "restore-private-id")
    private fun legacyJson(ctx: Context) = File(dp(ctx), "restore-pending-v1.json")
    private fun legacyPublished(ctx: Context) = File(dp(ctx), "restore.pending")
    private fun dp(ctx: Context) = ctx.applicationContext.createDeviceProtectedStorageContext().filesDir
}
