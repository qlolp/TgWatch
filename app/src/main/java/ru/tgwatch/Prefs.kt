package ru.tgwatch

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
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
    private const val KEY_QUIET_START_MINUTE = "quiet_start_minute"
    private const val KEY_QUIET_END_MINUTE = "quiet_end_minute"
    private const val KEY_DAILY_SUMMARY = "daily_summary"
    private const val KEY_DAILY_SUMMARY_MINUTE = "daily_summary_minute"
    private const val KEY_EXTRA_MATRIX = "extra_probe_matrix"
    private const val KEY_EXTRA_WHATSAPP = "extra_probe_whatsapp"
    private const val KEY_EXTRA_CUSTOM = "extra_probe_custom"
    private const val KEY_LAST_STATUS = "last_status"
    private const val KEY_LAST_CHECKED = "last_checked"
    private const val KEY_LAST_SINCE = "last_since"
    private const val KEY_LAST_LATENCY = "last_latency"
    private const val KEY_LAST_REASON = "last_reason"
    private const val KEY_LAST_VIBE = "last_vibe"
    // Device-local; BackupManager exports only its explicit settings list.
    private const val KEY_ALERTS_MUTED_UNTIL = "alerts_muted_until"
    private const val KEY_ALERT_MUTE_BOOT = "alert_mute_boot"
    private const val KEY_ALERT_MUTE_STARTED = "alert_mute_started_elapsed"
    private const val KEY_ALERT_MUTE_UNTIL = "alert_mute_until_elapsed"

    const val DEFAULT_INTERVAL_SEC = 30
    const val DEFAULT_QUIET_START = 23
    const val DEFAULT_QUIET_END = 8

    /**
     * Настройки в device-protected хранилище: читаются до разблокировки PIN
     * (LOCKED_BOOT_COMPLETED). Старый файл из обычного хранилища переносим
     * при первой разблокировке.
     */
    /** Startup recovery belongs to Application; settings reads never replay a transaction. */
    fun sp(ctx: Context): android.content.SharedPreferences = rawSp(ctx)
    internal fun rawSp(ctx: Context) = store(ctx).getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val legacyMove = OneTimeMigration()

    private fun store(ctx: Context): Context {
        val app = ctx.applicationContext
        if (Build.VERSION.SDK_INT < 24) return app
        val dp = app.createDeviceProtectedStorageContext()
        // sp() runs on every render and check; the move scans the old directory, so stop after success.
        legacyMove.run(
            ready = { app.getSystemService(UserManager::class.java)?.isUserUnlocked != false },
            move = { dp.moveSharedPreferencesFrom(app, FILE) },
        )
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

    /** Не вибрировать в тихие часы, пока оповещения заглушены, и в системном «не беспокоить». */
    fun alertsAllowed(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
        if (inQuietHoursNow(ctx, now) || alertMuteRemaining(ctx, now) > 0L) return false
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
        if (value && BackupManager.hasPending()) return
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
    fun soundFor(ctx: Context, event: String): Boolean = EventPreferences.sound(event,
        sp(ctx).all.mapValues { it.value.toString() })
    fun setSoundFor(ctx: Context, event: String, value: Boolean) {
        sp(ctx).edit().putBoolean(EventPreferences.soundKey(event),value).apply()
    }
    fun notifyFor(ctx: Context, event: String): Boolean =
        sp(ctx).getBoolean(EventPreferences.notifyKey(event),event != "PARTIAL")
    fun setNotifyFor(ctx: Context, event: String, value: Boolean) {
        sp(ctx).edit().putBoolean(EventPreferences.notifyKey(event),value).apply()
    }
    fun alarmPattern(ctx: Context): AlarmPattern = AlarmPattern.entries.firstOrNull {
        it.name == sp(ctx).getString(KEY_ALARM_PATTERN, AlarmPattern.STANDARD.name)
    } ?: AlarmPattern.STANDARD
    fun setAlarmPattern(ctx: Context, pattern: AlarmPattern) { sp(ctx).edit().putString(KEY_ALARM_PATTERN, pattern.name).apply() }

    /** Opt-in: an upgrade must not start posting a new daily notification. */
    fun dailySummary(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_DAILY_SUMMARY, false)
    fun setDailySummary(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_DAILY_SUMMARY, value).apply()
        DailySummaryReceiver.schedule(ctx)
    }
    fun dailySummaryMinute(ctx: Context): Int =
        sp(ctx).getInt(KEY_DAILY_SUMMARY_MINUTE, DailySummary.DEFAULT_MINUTE).coerceIn(0, 1439)
    fun setDailySummaryMinute(ctx: Context, minute: Int) {
        sp(ctx).edit().putInt(KEY_DAILY_SUMMARY_MINUTE, minute.coerceIn(0, 1439)).apply()
        DailySummaryReceiver.schedule(ctx)
    }

    /** Informational probes of other services; off by default, never part of Telegram status. */
    fun extraMatrix(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_EXTRA_MATRIX, false)
    fun setExtraMatrix(ctx: Context, value: Boolean) { sp(ctx).edit().putBoolean(KEY_EXTRA_MATRIX, value).apply() }
    fun extraWhatsapp(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_EXTRA_WHATSAPP, false)
    fun setExtraWhatsapp(ctx: Context, value: Boolean) { sp(ctx).edit().putBoolean(KEY_EXTRA_WHATSAPP, value).apply() }
    fun extraCustom(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_EXTRA_CUSTOM, false)
    fun setExtraCustom(ctx: Context, value: Boolean) { sp(ctx).edit().putBoolean(KEY_EXTRA_CUSTOM, value).apply() }

    /** Endpoint addresses are credential protected and unavailable before unlock. */
    fun extraCustomUrl(ctx: Context): String = PrivateEndpoints.legacy(ctx)

    fun setExtraCustomUrl(ctx: Context, value: String): Boolean = PrivateEndpoints.setLegacy(ctx, value)

    fun extraProbeEndpoints(ctx: Context): List<ProbeEndpoint> =
        ExtraProbes.endpoints(extraMatrix(ctx), extraWhatsapp(ctx), extraCustom(ctx), extraCustomUrl(ctx))

    /** Не вибрировать ночью. */
    fun quietHoursEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_QUIET_HOURS, false)

    fun setQuietHoursEnabled(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_QUIET_HOURS, value).apply()
    }

    /** Legacy callers still see hours; minute settings take precedence. */
    fun quietStartHour(ctx: Context): Int = quietStartMinute(ctx) / 60

    fun quietEndHour(ctx: Context): Int = quietEndMinute(ctx) / 60

    fun quietStartMinute(ctx: Context): Int {
        val values = sp(ctx).all
        return QuietHours.resolveMinute(values[KEY_QUIET_START_MINUTE] as? Int,
            values[KEY_QUIET_START] as? Int ?: DEFAULT_QUIET_START)
    }

    fun quietEndMinute(ctx: Context): Int {
        val values = sp(ctx).all
        return QuietHours.resolveMinute(values[KEY_QUIET_END_MINUTE] as? Int,
            values[KEY_QUIET_END] as? Int ?: DEFAULT_QUIET_END)
    }

    fun setQuietHours(ctx: Context, startHour: Int, endHour: Int) {
        setQuietHoursMinutes(ctx, startHour.coerceIn(0, 23) * 60, endHour.coerceIn(0, 23) * 60)
    }

    fun setQuietHoursMinutes(ctx: Context, start: Int, end: Int) {
        val startMinute = start.coerceIn(0, 1439)
        val endMinute = end.coerceIn(0, 1439)
        sp(ctx).edit()
            .putInt(KEY_QUIET_START_MINUTE, startMinute)
            .putInt(KEY_QUIET_END_MINUTE, endMinute)
            .putInt(KEY_QUIET_START, startMinute / 60)
            .putInt(KEY_QUIET_END, endMinute / 60)
            .apply()
    }

    /** Display-only wall-clock projection; suppression and expiry use elapsedRealtime. */
    @Synchronized
    fun alertsMutedUntil(ctx: Context, now: Long = System.currentTimeMillis()): Long {
        val remaining = alertMuteRemaining(ctx, now)
        return if (remaining > 0) now.coerceAtMost(Long.MAX_VALUE - remaining) + remaining else 0L
    }

    @Synchronized
    private fun alertMuteRemaining(ctx: Context, now: Long): Long =
        AlertMute.remaining(alertMuteStore(ctx), now, SystemClock.elapsedRealtime(), bootCount(ctx))

    /** The wall-clock argument is retained for existing callers; the new timer ignores clock corrections. */
    @Suppress("UNUSED_PARAMETER")
    @Synchronized
    fun muteAlerts(ctx: Context, now: Long = System.currentTimeMillis()): Boolean =
        AlertMute.mute(alertMuteStore(ctx), SystemClock.elapsedRealtime(), bootCount(ctx))

    @Synchronized
    fun clearAlertMute(ctx: Context): Boolean = AlertMute.clear(alertMuteStore(ctx))

    private fun bootCount(ctx: Context): Int? = try {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT).takeIf { it >= 0 }
    } catch (_: Settings.SettingNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun alertMuteStore(ctx: Context): AlertMute.Store = object : AlertMute.Store {
        private val settings = sp(ctx)
        override fun read(): AlertMute.State {
            val values = settings.all
            return AlertMute.State(values[KEY_ALERT_MUTE_BOOT] as? Int,
                values[KEY_ALERT_MUTE_STARTED] as? Long ?: 0L,
                values[KEY_ALERT_MUTE_UNTIL] as? Long ?: 0L,
                values[KEY_ALERTS_MUTED_UNTIL] as? Long ?: 0L)
        }

        override fun write(state: AlertMute.State): Boolean {
            val edit = settings.edit().remove(KEY_ALERTS_MUTED_UNTIL)
            if (state.bootCount == null) {
                edit.remove(KEY_ALERT_MUTE_BOOT).remove(KEY_ALERT_MUTE_STARTED).remove(KEY_ALERT_MUTE_UNTIL)
            } else {
                edit.putInt(KEY_ALERT_MUTE_BOOT, state.bootCount).putLong(KEY_ALERT_MUTE_STARTED, state.startedElapsed)
                    .putLong(KEY_ALERT_MUTE_UNTIL, state.untilElapsed)
            }
            return edit.commit()
        }
    }

    fun inQuietHoursNow(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
        if (!quietHoursEnabled(ctx)) return false
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        return ProbeRules.inQuietHoursMinutes(
            cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE),
            quietStartMinute(ctx),
            quietEndMinute(ctx),
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

/** Retries a one-time move until it reports success; failures and a locked user try again later. */
internal class OneTimeMigration {
    @Volatile private var done = false

    fun run(ready: () -> Boolean, move: () -> Boolean) {
        if (done) return
        try {
            if (ready() && move()) done = true
        } catch (_: Exception) {
        }
    }
}

/** Время в виде 15:42:10, либо прочерк, если времени нет. */
fun timeStr(millis: Long): String =
    if (millis <= 0L) "—" else SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

/** Длительность словами: «12 с», «5 мин», «2 ч 15 мин», «3 д 4 ч» (English: 12 s, 5 min, 2 h 15 min, 3 d 4 h). */
fun durationStr(ms: Long): String {
    val sec = (ms / 1000L).coerceAtLeast(0L)
    val min = sec / 60
    val hours = min / 60
    val days = hours / 24
    return when {
        sec < 60 -> Lang.t("$sec с", "$sec s")
        min < 60 -> Lang.t("$min мин", "$min min")
        hours < 24 -> if (min % 60 == 0L) Lang.t("$hours ч", "$hours h") else Lang.t("$hours ч ${min % 60} мин", "$hours h ${min % 60} min")
        else -> if (hours % 24 == 0L) Lang.t("$days д", "$days d") else Lang.t("$days д ${hours % 24} ч", "$days d ${hours % 24} h")
    }
}

/** «5 с назад» или прочерк, если события ещё не было. */
fun agoStr(millis: Long, now: Long = System.currentTimeMillis()): String =
    if (millis <= 0L) "—" else if (now - millis < 2000L) Lang.t("только что", "just now") else durationStr(now - millis) + Lang.t(" назад", " ago")

