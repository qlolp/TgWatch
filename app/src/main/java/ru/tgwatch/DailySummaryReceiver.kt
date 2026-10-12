package ru.tgwatch

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

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

        // Device-local delivery state; BackupManager exports only its explicit settings list.
        private const val LAST_DELIVERY = "daily_summary_last_at"

        /** Manual preview bypasses opt-in/monitoring/deduplication, while permission/channel blocks still apply. */
        @Synchronized fun post(ctx: Context, force: Boolean): Boolean {
            val now = System.currentTimeMillis()
            val settings = Prefs.sp(ctx)
            // Stopped monitoring stays quiet: no scheduled summary until the user starts it again.
            val scheduled = DailySummary.due(force, Prefs.dailySummary(ctx), Prefs.isEnabled(ctx),
                alreadyDelivered(now, settings.getLong(LAST_DELIVERY, 0L)))
            if (!scheduled) return false
            return try {
                val text = DailySummary.text(History.stats(ctx, 1, now))
                if (text == null) false else {
                    val posted = EventNotifications(ctx, ctx.getSystemService(NotificationManager::class.java))
                        .summary(text.title, text.body)
                    if (posted && !force) settings.edit().putLong(LAST_DELIVERY, now).commit()
                    posted
                }
            } catch (e: Exception) {
                Log.w(TAG, "daily summary", e)
                false
            }
        }

        private fun alreadyDelivered(now: Long, last: Long): Boolean {
            val today = Calendar.getInstance().apply { timeInMillis = now }
            val previous = Calendar.getInstance().apply { timeInMillis = last }
            val sameDay = today.get(Calendar.YEAR) == previous.get(Calendar.YEAR) &&
                today.get(Calendar.DAY_OF_YEAR) == previous.get(Calendar.DAY_OF_YEAR)
            // A different local day remains eligible after a wall-clock correction.
            return last > 0L && sameDay
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
