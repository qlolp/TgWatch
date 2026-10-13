package ru.tgwatch

import android.content.Context
import android.util.Log

/** Device-local diagnostic state; never included in portable settings backups. */
object LastSuccessStore {
    private const val KEY_LAST_SUCCESS = "last_success_at"

    @Suppress("TooGenericExceptionCaught") // Any write failure is diagnostic, not fatal.
    @Synchronized
    fun record(ctx: Context, statusName: String, checkedAt: Long) {
        val settings = Prefs.sp(ctx)
        val previous = lastSuccessAt(ctx)
        val next = LastSuccessPolicy.updated(previous, statusName, checkedAt)
        if (next != previous) {
            // The widget reads the in-memory value; the flush rides on a worker thread.
            // A failed write is logged, never thrown: it cannot invalidate a probe result.
            try {
                settings.edit().putLong(KEY_LAST_SUCCESS, next).apply()
            } catch (e: Exception) {
                Log.w("TgWatch", "last success", e)
            }
        }
    }

    @Synchronized
    fun lastSuccessAt(ctx: Context): Long =
        (Prefs.sp(ctx).all[KEY_LAST_SUCCESS] as? Long)?.coerceAtLeast(0L) ?: 0L

    @Synchronized
    fun clear(ctx: Context) {
        check(Prefs.sp(ctx).edit().remove(KEY_LAST_SUCCESS).commit()) {
            "Could not clear last successful check"
        }
    }
}
