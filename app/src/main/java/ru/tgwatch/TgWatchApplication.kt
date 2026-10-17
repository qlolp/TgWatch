package ru.tgwatch

import android.app.Application
import android.content.res.Configuration

class TgWatchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Text built in code (notifications, widget, tile) follows the resource locale.
        Lang.update(this)
        RecoveryCoordinator.start(this)
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
