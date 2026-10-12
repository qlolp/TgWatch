package ru.tgwatch

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.os.Build

/**
 * Redraws the screen as soon as the service publishes a result, so the 1 s ticker only has
 * to keep the "checked N s ago" labels fresh. The broadcast stays inside the application.
 */
class StateRefresh(private val onState: () -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) = onState()

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // Older API branch registers without a flag.
    fun register(activity: Activity) {
        try {
            val filter = IntentFilter(MonitorService.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.registerReceiver(this, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                activity.registerReceiver(this, filter)
            }
        } catch (_: Exception) {
        }
    }

    fun unregister(activity: Activity) {
        try { activity.unregisterReceiver(this) } catch (_: Exception) { }
    }
}
