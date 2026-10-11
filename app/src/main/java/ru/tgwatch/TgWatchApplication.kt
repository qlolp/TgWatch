package ru.tgwatch

import android.app.Application
import android.util.Log

class TgWatchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try { BackupManager.recoverPending(this) }
        catch (_: Exception) {
            // Preserve the durable journal for another replay; no monitoring until completion.
            Log.e("TgWatch", "Pending restore could not be completed")
        }
    }
}
