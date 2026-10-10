package ru.tgwatch

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Настройки приложения, хранятся в SharedPreferences. */
object Prefs {
    private const val FILE = "tgwatch"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_INTERVAL = "interval_sec"
    private const val KEY_KEEP_AWAKE = "keep_awake"
    private const val KEY_VIBRATE = "vibrate"
    private const val KEY_VIBRATE_OFFLINE = "vibrate_offline"
    private const val KEY_VIBRATE_RECOVERY = "vibrate_recovery"
    private const val KEY_QUIET_HOURS = "quiet_hours"
    private const val KEY_QUIET_START = "quiet_start_hour"
    private const val KEY_QUIET_END = "quiet_end_hour"
    private const val KEY_LAST_STATUS = "last_status"
    private const val KEY_LAST_CHECKED = "last_checked"
    private const val KEY_LAST_SINCE = "last_since"
    private const val KEY_LAST_LATENCY = "last_latency"
    private const val KEY_LAST_REASON = "last_reason"
    private const val KEY_LAST_VIBE = "last_vibe"

    const val DEFAULT_INTERVAL_SEC = 15
    const val DEFAULT_QUIET_START = 23
    const val DEFAULT_QUIET_END = 8

    fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Включён ли мониторинг (пользователь не нажимал «Остановить»). */
    fun isEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** Как часто проверять Telegram, в секундах. */
    fun intervalSec(ctx: Context): Int = sp(ctx).getInt(KEY_INTERVAL, DEFAULT_INTERVAL_SEC)

    fun setIntervalSec(ctx: Context, value: Int) {
        sp(ctx).edit().putInt(KEY_INTERVAL, value).apply()
    }

    /** Будить процессор на время каждой проверки при выключенном экране. */
    fun keepAwake(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_KEEP_AWAKE, true)

    fun setKeepAwake(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_KEEP_AWAKE, value).apply()
    }

    /** Вибрировать, когда недоступен именно Telegram. */
    fun vibrateEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_VIBRATE, true)

    fun setVibrateEnabled(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_VIBRATE, value).apply()
    }

    /** Вибрировать также при полной потере интернета (по умолчанию выкл.). */
    fun vibrateOffline(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_VIBRATE_OFFLINE, false)

    fun setVibrateOffline(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_VIBRATE_OFFLINE, value).apply()
    }

    /** Короткая вибрация, когда связь с Telegram вернулась. */
    fun vibrateOnRecovery(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_VIBRATE_RECOVERY, true)

    fun setVibrateOnRecovery(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_VIBRATE_RECOVERY, value).apply()
    }

    /** Не вибрировать ночью. */
    fun quietHoursEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_QUIET_HOURS, false)

    fun setQuietHoursEnabled(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_QUIET_HOURS, value).apply()
    }

    fun quietStartHour(ctx: Context): Int = sp(ctx).getInt(KEY_QUIET_START, DEFAULT_QUIET_START)

    fun quietEndHour(ctx: Context): Int = sp(ctx).getInt(KEY_QUIET_END, DEFAULT_QUIET_END)

    fun setQuietHours(ctx: Context, startHour: Int, endHour: Int) {
        sp(ctx).edit()
            .putInt(KEY_QUIET_START, startHour.coerceIn(0, 23))
            .putInt(KEY_QUIET_END, endHour.coerceIn(0, 23))
            .apply()
    }

    fun inQuietHoursNow(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
        if (!quietHoursEnabled(ctx)) return false
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        return ProbeRules.inQuietHours(
            cal.get(Calendar.HOUR_OF_DAY),
            quietStartHour(ctx),
            quietEndHour(ctx),
        )
    }

    /** Сохраняем последний статус, чтобы экран и значок не «мигали» после перезапуска процесса. */
    fun saveLastState(
        ctx: Context,
        status: MonitorService.Status,
        checkedAt: Long,
        since: Long,
        latencyMs: Long,
        reason: String,
        lastVibrationAt: Long,
    ) {
        sp(ctx).edit()
            .putString(KEY_LAST_STATUS, status.name)
            .putLong(KEY_LAST_CHECKED, checkedAt)
            .putLong(KEY_LAST_SINCE, since)
            .putLong(KEY_LAST_LATENCY, latencyMs)
            .putString(KEY_LAST_REASON, reason)
            .putLong(KEY_LAST_VIBE, lastVibrationAt)
            .apply()
    }

    fun loadLastState(ctx: Context): MonitorService.State? {
        val name = sp(ctx).getString(KEY_LAST_STATUS, null) ?: return null
        val status = try {
            MonitorService.Status.valueOf(name)
        } catch (_: Exception) {
            return null
        }
        if (status == MonitorService.Status.UNKNOWN) return null
        return MonitorService.State(
            status = status,
            checkedAt = sp(ctx).getLong(KEY_LAST_CHECKED, 0L),
            since = sp(ctx).getLong(KEY_LAST_SINCE, System.currentTimeMillis()),
            latencyMs = sp(ctx).getLong(KEY_LAST_LATENCY, -1L),
            reason = sp(ctx).getString(KEY_LAST_REASON, "") ?: "",
            lastVibrationAt = sp(ctx).getLong(KEY_LAST_VIBE, 0L),
        )
    }

    fun clearLastState(ctx: Context) {
        sp(ctx).edit()
            .remove(KEY_LAST_STATUS)
            .remove(KEY_LAST_CHECKED)
            .remove(KEY_LAST_SINCE)
            .remove(KEY_LAST_LATENCY)
            .remove(KEY_LAST_REASON)
            .remove(KEY_LAST_VIBE)
            .apply()
    }
}

/** Время в виде 15:42:10, либо прочерк, если времени нет. */
fun timeStr(millis: Long): String =
    if (millis <= 0L) "—" else SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

/** Длительность словами: «12 с», «5 мин», «2 ч 15 мин», «3 д 4 ч». */
fun durationStr(ms: Long): String {
    val sec = (ms / 1000L).coerceAtLeast(0L)
    val min = sec / 60
    val hours = min / 60
    val days = hours / 24
    return when {
        sec < 60 -> "$sec с"
        min < 60 -> "$min мин"
        hours < 24 -> if (min % 60 == 0L) "$hours ч" else "$hours ч ${min % 60} мин"
        else -> if (hours % 24 == 0L) "$days д" else "$days д ${hours % 24} ч"
    }
}

/** «5 с назад» или прочерк, если события ещё не было. */
fun agoStr(millis: Long, now: Long = System.currentTimeMillis()): String =
    if (millis <= 0L) "—" else if (now - millis < 2000L) "только что" else durationStr(now - millis) + " назад"
