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
        if (!TargetPreferences.definition(this, TargetId.TELEGRAM).enabled || BackupManager.hasPending()) {
            openApp()
            return
        }
        try {
            MonitorService.send(this, MonitorService.ACTION_CHECK_NOW)
            toast(this.renderString(R.string.copy_shortcut_checking))
        } catch (_: Exception) {
            // The foreground service may be refused; the app screen restarts monitoring itself.
            openApp()
        }
    }

    private fun mute() {
        val app = applicationContext
        TargetSettingsExecutor.background(TargetId.TELEGRAM,
            { AlertActionReceiver.muteNow(app) != null }, {})
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(TargetAutomation.EXTRA_TARGET_ID, TargetId.TELEGRAM.wire))
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()

    companion object {
        const val ACTION_CHECK_NOW = "ru.tgwatch.shortcut.CHECK_NOW"
        const val ACTION_MUTE = "ru.tgwatch.shortcut.MUTE_ALERTS"
    }
}
