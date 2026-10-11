package ru.tgwatch

import android.content.Context
import android.os.SystemClock

/** Counts checks that were overdue while the screen was off (Doze / OEM deferral cost). */
object DozeStats {
    private const val KEY_MISSED = "doze_missed_checks"
    private const val KEY_LAST_EXPECTED = "doze_last_expected_mono"

    fun missedChecks(ctx: Context): Long = Prefs.sp(ctx).getLong(KEY_MISSED, 0L)

    fun noteScheduled(ctx: Context, expectedMono: Long) {
        Prefs.sp(ctx).edit().putLong(KEY_LAST_EXPECTED, expectedMono).apply()
    }

    /** Call when a check starts or when ensureProgress detects an overdue gap. */
    fun noteOverdue(ctx: Context, screenOff: Boolean) {
        if (!screenOff) return
        val expected = Prefs.sp(ctx).getLong(KEY_LAST_EXPECTED, 0L)
        val now = SystemClock.elapsedRealtime()
        if (expected > 0L && now > expected + 5_000L) {
            Prefs.sp(ctx).edit().putLong(KEY_MISSED, missedChecks(ctx) + 1L).apply()
        }
    }

    fun reset(ctx: Context) {
        Prefs.sp(ctx).edit().remove(KEY_MISSED).remove(KEY_LAST_EXPECTED).apply()
    }

    fun describe(ctx: Context): String {
        val n = missedChecks(ctx)
        return if (Lang.ru) {
            "Пропущено во сне (оценка): $n"
        } else {
            "Missed while asleep (estimate): $n"
        }
    }
}
