package ru.tgwatch

import android.app.Application
import android.content.res.Configuration
import android.util.Log

class TgWatchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Text built in code (notifications, widget, tile) follows the resource locale.
        Lang.update(this)
        try { BackupManager.recoverPending(this) }
        catch (_: Exception) {
            // Preserve the durable journal for another replay; no monitoring until completion.
            Log.e("TgWatch", "Pending restore could not be completed")
        }
        // Alarms do not survive reboot, update or restore; re-arming on each process start is cheap.
        DailySummaryReceiver.schedule(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Lang.update(this)
        // The widget text is built in code; redraw it in the new language right away.
        StatusWidget.updateAll(this)
    }
}
