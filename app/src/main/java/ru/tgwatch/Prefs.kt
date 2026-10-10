package ru.tgwatch

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.UserManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Настройки приложения, хранятся в SharedPreferences. */
object Prefs {
    private const val FILE = "tgwatch"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PROFILE = "power_profile"
    private const val KEY_INTERVAL = "interval_sec"
    private const val KEY_KEEP_AWAKE = "keep_awake"
    private const val KEY_VIBRATE = "vibrate"
    private const val KEY_VIBRATE_PARTIAL = "vibrate_partial"
    private const val KEY_VIBRATE_OFFLINE = "vibrate_offline"
    private const val KEY_VIBRATE_RECOVERY = "vibrate_recovery"
    private const val KEY_NOTIFY_RECOVERY = "notify_recovery"
    private const val KEY_EVENT_SOUND = "event_sound"
    private const val KEY_ALARM_PATTERN = "vibration_pattern"
    private const val KEY_QUIET_HOURS = "quiet_hours"
    private const val KEY_QUIET_START = "quiet_start_hour"
    private const val KEY_QUIET_END = "quiet_end_hour"
    private const val KEY_LAST_STATUS = "last_status"
    private const val KEY_LAST_CHECKED = "last_checked"
    private const val KEY_LAST_SINCE = "last_since"
    private const val KEY_LAST_LATENCY = "last_latency"
    private const val KEY_LAST_REASON = "last_reason"
    private const val KEY_LAST_VIBE = "last_vibe"

    const val DEFAULT_INTERVAL_SEC = 30
    const val DEFAULT_QUIET_START = 23
    const val DEFAULT_QUIET_END = 8

    /**
     * Настройки в device-protected хранилище: читаются до разблокировки PIN
     * (LOCKED_BOOT_COMPLETED). Старый файл из обычного хранилища переносим
     * при первой разблокировке.
     */
    fun sp(ctx: Context): android.content.SharedPreferences {
        BackupStore.finishPending(ctx)
        return rawSp(ctx)
    }
    internal fun rawSp(ctx: Context) = store(ctx).getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun store(ctx: Context): Context {
        val app = ctx.applicationContext
        if (Build.VERSION.SDK_INT < 24) return app
        val dp = app.createDeviceProtectedStorageContext()
        try {
            val um = app.getSystemService(UserManager::class.java)
            if (um == null || um.isUserUnlocked) {
                dp.moveSharedPreferencesFrom(app, FILE)
            }
        } catch (_: Exception) {
        }
        return dp
    }

    /** Можно ли ставить точный будильник (иначе проверки во сне замирают). */
    fun exactAlarmsAllowed(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return try {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        } catch (_: Exception) {
            true
        }
    }

    /** Не вибрировать в тихие часы и в системном «не беспокоить». */
    fun alertsAllowed(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
        if (inQuietHoursNow(ctx, now)) return false
        return try {
            val filter = (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .currentInterruptionFilter
            filter == NotificationManager.INTERRUPTION_FILTER_ALL ||
                filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        } catch (_: Exception) {
            true
        }
    }

    /** Включён ли мониторинг (пользователь не нажимал «Остановить»). */
    fun isEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_ENABLED, value).apply()
        if (value) WatchdogReceiver.schedule(ctx) else WatchdogReceiver.cancel(ctx)
    }

    /** Как часто проверять Telegram, в секундах. */
    fun intervalSec(ctx: Context): Int = sp(ctx).getInt(KEY_INTERVAL, DEFAULT_INTERVAL_SEC)

    fun setIntervalSec(ctx: Context, value: Int) {
        sp(ctx).edit().putInt(KEY_INTERVAL, value.coerceIn(10, 120)).putString(KEY_PROFILE, "CUSTOM").apply()
    }

    fun profile(ctx: Context): PowerProfile? {
        val settings = sp(ctx)
        val value = settings.getString(KEY_PROFILE, if (settings.contains(KEY_INTERVAL)) "CUSTOM" else "BALANCED")
        return PowerProfile.entries.firstOrNull { it.name == value }
    }
    fun setProfile(ctx: Context, profile: PowerProfile) {
        sp(ctx).edit().putString(KEY_PROFILE, profile.name).putInt(KEY_INTERVAL, profile.awake).apply()
    }
    fun effectiveIntervalSec(ctx: Context, bad: Boolean, screenOff: Boolean): Int =
        profile(ctx)?.interval(bad, screenOff) ?: ProbeRules.nextIntervalSec(intervalSec(ctx).coerceIn(10, 120), bad, screenOff)

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

    /** Separate opt-in: an upgrade must not enable a new category of alerts. */
    fun vibratePartial(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_VIBRATE_PARTIAL, false)

    fun setVibratePartial(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_VIBRATE_PARTIAL, value).apply()
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

    fun notifyRecovery(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_NOTIFY_RECOVERY, true)
    fun setNotifyRecovery(ctx: Context, value: Boolean) { sp(ctx).edit().putBoolean(KEY_NOTIFY_RECOVERY, value).apply() }
    fun eventSound(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_EVENT_SOUND, false)
    fun setEventSound(ctx: Context, value: Boolean) { sp(ctx).edit().putBoolean(KEY_EVENT_SOUND, value).apply() }
    fun alarmPattern(ctx: Context): AlarmPattern = AlarmPattern.entries.firstOrNull {
        it.name == sp(ctx).getString(KEY_ALARM_PATTERN, AlarmPattern.STANDARD.name)
    } ?: AlarmPattern.STANDARD
    fun setAlarmPattern(ctx: Context, pattern: AlarmPattern) { sp(ctx).edit().putString(KEY_ALARM_PATTERN, pattern.name).apply() }

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
        diagnostics: String = "",
        expectedIntervalSec: Int = 30,
    ) {
        sp(ctx).edit()
            .putString(KEY_LAST_STATUS, status.name)
            .putLong(KEY_LAST_CHECKED, checkedAt)
            .putLong(KEY_LAST_SINCE, since)
            .putLong(KEY_LAST_LATENCY, latencyMs)
            .putString(KEY_LAST_REASON, reason)
            .putLong(KEY_LAST_VIBE, lastVibrationAt)
            .putString("last_diagnostics", diagnostics)
            .putInt("last_expected_sec", expectedIntervalSec)
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
            diagnostics = sp(ctx).getString("last_diagnostics", "") ?: "",
            expectedIntervalSec = sp(ctx).getInt("last_expected_sec", 30),
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
            .remove("last_diagnostics")
            .remove("last_expected_sec")
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

