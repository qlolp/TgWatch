package ru.tgwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.Closeable

/** System events update a cached input; no periodic battery polling or new alarm. */
internal class TargetPowerMonitor(ctx: Context, private val changed: (TargetPowerState) -> Unit,
    readState: (() -> TargetPowerState)? = null) : Closeable {
    private val app = ctx.applicationContext
    private val sample = readState ?: { Companion.readState(app) }
    @Volatile var state: TargetPowerState = sample()
        private set
    private var closed = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }
    init {
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
    }

    @Synchronized fun refresh() {
        if (closed) return
        val next = sample()
        if (state != next) { state = next; changed(next) }
    }
    @Synchronized override fun close() {
        if (!closed) { closed = true; app.unregisterReceiver(receiver) }
    }

    companion object {
        private const val PERCENT = 100
        fun batteryPercent(intent: Intent?): Int? {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: return null
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            return if (scale > 0 && level in 0..scale) (level.toLong() * PERCENT / scale).toInt() else null
        }
        fun readState(ctx: Context): TargetPowerState {
            val power = ctx.getSystemService(PowerManager::class.java)
            val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            return TargetPowerState(power.isInteractive, power.isPowerSaveMode, batteryPercent(battery), charging)
        }
    }
}
