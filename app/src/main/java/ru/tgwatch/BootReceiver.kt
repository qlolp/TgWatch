package ru.tgwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Запускает мониторинг после перезагрузки (в т.ч. до разблокировки) и после обновления. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val boot = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        if (!boot) return
        WatchdogReceiver.schedule(context)
        StatusWidget.updateAll(context)
        if (!Prefs.isEnabled(context)) return
        try {
            MonitorService.start(context)
        } catch (e: Exception) {
            Log.w("TgWatch", "Не удалось запустить мониторинг после загрузки", e)
        }
    }
}
