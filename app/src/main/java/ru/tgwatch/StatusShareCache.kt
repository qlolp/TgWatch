package ru.tgwatch

import android.content.Context
import android.content.Intent
import android.os.UserManager
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Only generated PNGs in this one private cache directory are exposed. No incoming path is accepted. */
internal object StatusShareCache {
    private const val TTL_MS = 86_400_000L
    private const val KEEP_FILES = 32
    fun directory(ctx: Context) = File(ctx.cacheDir, "status-share")
    fun write(ctx: Context, document: StatusShareDocument): File {
        cleanup(ctx)
        val root = directory(ctx)
        check(root.isDirectory || root.mkdirs())
        val file = File(root, UUID.randomUUID().toString() + ".png")
        var complete = false
        try {
            FileOutputStream(file).use { StatusCardRenderer.png(document, it); it.fd.sync() }
            complete = true
            return file
        } finally { if (!complete) file.delete() }
    }
    fun uri(ctx: Context, file: File) = FileProvider.getUriForFile(ctx, ctx.packageName + ".status-share", file)
    fun cleanup(ctx: Context, now: Long = System.currentTimeMillis()) {
        if (!ctx.getSystemService(UserManager::class.java).isUserUnlocked) return
        val files = directory(ctx).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
        files.forEachIndexed { index, file ->
            if (file.lastModified() < now - TTL_MS || index >= KEEP_FILES) {
                runCatching { ctx.revokeUriPermission(uri(ctx, file), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                file.delete()
            }
        }
    }
}
