package ru.tgwatch

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Плитка в быстрых настройках шторки: показывает статус Telegram.
 * Нажатие — проверить сейчас; если мониторинг выключен — открыть приложение и запустить его.
 */
class StatusTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (MonitorService.running) {
            try {
                MonitorService.send(this, MonitorService.ACTION_CHECK_NOW)
            } catch (e: Exception) {
                openApp()
            }
        } else {
            // Из плитки Android не всегда разрешает запускать фоновую службу,
            // поэтому включаем мониторинг и открываем приложение: оно запустит службу само.
            Prefs.setEnabled(this, true)
            openApp()
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val s = MonitorService.state
        val (tileState, icon, subtitle) = when {
            !MonitorService.running -> Triple(Tile.STATE_INACTIVE, R.drawable.ic_stat_pause, "Выключен")
            s.status == MonitorService.Status.OK ->
                Triple(Tile.STATE_ACTIVE, R.drawable.ic_stat_ok, "Доступен · ${s.latencyMs} мс")
            s.status == MonitorService.Status.TG_DOWN ->
                Triple(Tile.STATE_ACTIVE, R.drawable.ic_stat_fail, "Недоступен")
            s.status == MonitorService.Status.NO_NETWORK ->
                Triple(Tile.STATE_ACTIVE, R.drawable.ic_stat_offline, "Нет интернета")
            else -> Triple(Tile.STATE_ACTIVE, R.drawable.ic_stat_wait, "Проверяю…")
        }
        tile.state = tileState
        tile.icon = Icon.createWithResource(this, icon)
        tile.label = "Telegram"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = subtitle
        } else {
            tile.label = "Telegram: $subtitle"
        }
        tile.updateTile()
    }
}
