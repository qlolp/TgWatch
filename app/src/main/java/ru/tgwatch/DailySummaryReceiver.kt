package ru.tgwatch

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Posts the daily summary at the user's chosen time and re-arms itself for the next day. */
class DailySummaryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION) post(context, force = false)
        // Time and timezone changes move the wall-clock target, so every delivery re-arms.
        schedule(context)
    }

    companion object {
        private const val TAG = "TgWatch"
        private const val ACTION = "ru.tgwatch.action.DAILY_SUMMARY"

        /** Returns false when there is nothing to summarise (no checks in the last 24 h). */
        fun post(ctx: Context, force: Boolean): Boolean {
            if (!force && !Prefs.dailySummary(ctx)) return false
            val text = DailySummary.text(History.stats(ctx, 1)) ?: return false
            return try {
                EventNotifications(ctx, ctx.getSystemService(NotificationManager::class.java))
                    .summary(text.title, text.body)
                true
            } catch (e: Exception) {
                Log.w(TAG, "daily summary", e)
                false
            }
        }

        fun schedule(ctx: Context) {
            if (!Prefs.dailySummary(ctx)) {
                cancel(ctx)
                return
            }
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val at = DailySummary.nextAt(System.currentTimeMillis(), Prefs.dailySummaryMinute(ctx))
            try {
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx))
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx))
                }
            } catch (e: Exception) {
                Log.w(TAG, "daily summary schedule", e)
            }
        }

        fun cancel(ctx: Context) {
            try {
                (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(ctx))
            } catch (e: Exception) {
                Log.w(TAG, "daily summary cancel", e)
            }
        }

        private fun pending(ctx: Context): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, 41,
                Intent(ctx, DailySummaryReceiver::class.java).setAction(ACTION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }
}
