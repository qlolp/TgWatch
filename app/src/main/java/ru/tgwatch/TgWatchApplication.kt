package ru.tgwatch

import android.app.Application
import android.content.res.Configuration

class TgWatchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Debug builds surface accidental main-thread disk or network access in logcat only.
        if (BuildConfig.DEBUG) DebugStrictMode.enable()
        // The crash journal must exist before any other component can throw on this process.
        CrashJournal.install(this)
        // Text built in code (notifications, widget, tile) follows the resource locale.

        RecoveryCoordinator.start(this)
        // A reboot or a clock change while the process was dead must not stretch a public-response watch.
        RegionalWatchClock.schedule(this)
        StatusShareJobs.cleanup(this)
        // Alarms do not survive reboot, update or restore; re-arming on each process start is cheap.
        DailySummaryReceiver.schedule(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // The widget text is built in code; redraw it in the new language right away.
        StatusWidget.updateAll(this)
    }
}
