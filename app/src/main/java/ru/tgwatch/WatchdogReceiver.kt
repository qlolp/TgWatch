package ru.tgwatch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Редкий будильник вне службы: если мониторинг включён, а процесс убит —
 * поднимаем службу снова. Не снимаем при смерти FGS, только когда пользователь
 * нажал «Остановить».
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (!Prefs.isEnabled(context)) {
            cancel(context)
            return
        }
        if (!MonitorService.running) {
            try {
                MonitorService.start(context)
            } catch (e: Exception) {
                Log.w(TAG, "watchdog start", e)
            }
        }
        StatusWidget.updateAll(context)
        schedule(context)
    }

    companion object {
        private const val TAG = "TgWatch"
        private const val ACTION = "ru.tgwatch.action.WATCHDOG"
        private const val INTERVAL_MS = 3 * 60 * 1000L

        fun schedule(ctx: Context) {
            if (!Prefs.isEnabled(ctx)) {
                cancel(ctx)
                return
            }
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val at = SystemClock.elapsedRealtime() + INTERVAL_MS
            try {
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(ctx))
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(ctx))
                }
            } catch (e: Exception) {
                Log.w(TAG, "watchdog schedule", e)
            }
        }

        fun cancel(ctx: Context) {
            try {
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.cancel(pending(ctx))
            } catch (e: Exception) {
                Log.w(TAG, "watchdog cancel", e)
            }
        }

        private fun pending(ctx: Context): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, 40,
                Intent(ctx, WatchdogReceiver::class.java).setAction(ACTION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }
}
