package ru.tgwatch

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Плитка в быстрых настройках шторки: показывает статус Telegram.
 * Нажатие — проверить сейчас; если мониторинг выключен — открыть приложение и запустить его.
 */
class StatusTileService : TileService() {

    private val ui = Handler(Looper.getMainLooper())
    private var listening = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (listening) refresh()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!listening) return
            refresh()
            ui.postDelayed(this, 2_000L)
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        listening = true
        MonitorService.restorePersistedState(this)
        try {
            val filter = IntentFilter(MonitorService.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(stateReceiver, filter)
            }
        } catch (_: Exception) {
        }
        refresh()
        ui.removeCallbacks(ticker)
        ui.postDelayed(ticker, 2_000L)
    }

    override fun onStopListening() {
        listening = false
        ui.removeCallbacks(ticker)
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: Exception) {
        }
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (MonitorService.running) {
            try {
                MonitorService.send(this, MonitorService.ACTION_CHECK_NOW)
                ui.postDelayed({ refresh() }, 400L)
            } catch (_: Exception) {
                openApp()
            }
        } else {
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
        val now = System.currentTimeMillis()
        val stale = s.isStale(now, Prefs.intervalSec(this))
        val (tileState, icon, subtitle) = when {
            !MonitorService.running -> Triple(Tile.STATE_INACTIVE, R.drawable.ic_stat_pause, "Выключен")
            stale && s.status == MonitorService.Status.OK ->
                Triple(Tile.STATE_INACTIVE, R.drawable.ic_stat_wait, "Устарело · ${agoStr(s.checkedAt, now)}")
            stale && s.status == MonitorService.Status.TG_DOWN ->
                Triple(Tile.STATE_ACTIVE, R.drawable.ic_stat_fail, "Недоступен?")
            stale && s.status == MonitorService.Status.NO_NETWORK ->
                Triple(Tile.STATE_ACTIVE, R.drawable.ic_stat_offline, "Без сети?")
            s.status == MonitorService.Status.OK ->
                Triple(Tile.STATE_ACTIVE, R.drawable.ic_stat_ok, "Доступен · ${s.latencyMs} мс")
            s.status == MonitorService.Status.TG_DOWN ->
                Triple(Tile.STATE_ACTIVE, R.drawable.ic_stat_fail, "Недоступен")
            s.status == MonitorService.Status.NO_NETWORK ->
                Triple(Tile.STATE_INACTIVE, R.drawable.ic_stat_offline, "Нет интернета")
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
