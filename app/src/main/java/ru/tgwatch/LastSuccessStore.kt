package ru.tgwatch

import android.content.Context
import android.util.Log

/** Device-local diagnostic state; never included in portable settings backups. */
object LastSuccessStore {
    private const val KEY_LAST_SUCCESS = "last_success_at"

    @Synchronized
    @Suppress("TooGenericExceptionCaught") // Diagnostic storage failures must never reject a measured result.
    fun record(ctx: Context, statusName: String, checkedAt: Long) {
        try {
            val settings = Prefs.sp(ctx)
            val previous = lastSuccessAt(ctx)
            val next = LastSuccessPolicy.updated(previous, statusName, checkedAt)
            if (next != previous) {
                // This diagnostic is best-effort; disk failure cannot replace a measured result.
                check(settings.edit().putLong(KEY_LAST_SUCCESS, next).commit()) {
                    "Could not persist last successful check"
                }
            }
        } catch (e: Exception) {
            Log.w("TgWatch", "last success record failed: ${e.javaClass.simpleName}")
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
