package ru.tgwatch

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Ярлыки на значке приложения (долгое нажатие): «Проверить сейчас» и «Заглушить на час»
 * без открытия главного экрана. Не экспортирована: лаунчер запускает ярлыки от имени приложения.
 */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.update(this)
        when (intent?.action) {
            ACTION_CHECK_NOW -> checkNow()
            ACTION_MUTE -> mute()
        }
        finish()
    }

    private fun checkNow() {
        if (!Prefs.isEnabled(this) || BackupManager.hasPending(this)) {
            openApp()
            return
        }
        try {
            MonitorService.send(this, MonitorService.ACTION_CHECK_NOW)
            toast(Lang.t("Проверяю…", "Checking…"))
        } catch (_: Exception) {
            // The foreground service may be refused; the app screen restarts monitoring itself.
            openApp()
        }
    }

    private fun mute() {
        val until = AlertActionReceiver.muteNow(this)
        toast(until?.let { Lang.t("Оповещения заглушены до $it", "Alerts muted until $it") }
            ?: Lang.t("Не удалось заглушить оповещения", "Could not mute alerts"))
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()

    companion object {
        const val ACTION_CHECK_NOW = "ru.tgwatch.shortcut.CHECK_NOW"
        const val ACTION_MUTE = "ru.tgwatch.shortcut.MUTE_ALERTS"
    }
}
