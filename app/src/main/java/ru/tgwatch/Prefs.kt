package ru.tgwatch

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Настройки приложения, хранятся в SharedPreferences. */
object Prefs {
    private const val FILE = "tgwatch"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_INTERVAL = "interval_sec"
    private const val KEY_KEEP_AWAKE = "keep_awake"

    const val DEFAULT_INTERVAL_SEC = 30

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

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

    /** Держать процессор в рабочем состоянии, чтобы проверки шли и при выключенном экране. */
    fun keepAwake(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_KEEP_AWAKE, true)

    fun setKeepAwake(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_KEEP_AWAKE, value).apply()
    }
}

/** Время в виде 15:42:10, либо прочерк, если времени нет. */
fun timeStr(millis: Long): String =
    if (millis <= 0L) "—" else SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))
