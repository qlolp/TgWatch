package ru.tgwatch

import android.content.Context

/** Device-local diagnostic state; never included in portable settings backups. */
object LastSuccessStore {
    private const val KEY_LAST_SUCCESS = "last_success_at"

    @Synchronized
    fun record(ctx: Context, statusName: String, checkedAt: Long) {
        val settings = Prefs.sp(ctx)
        val previous = lastSuccessAt(ctx)
        val next = LastSuccessPolicy.updated(previous, statusName, checkedAt)
        if (next != previous) {
            // Publication happens off the UI thread. Complete disk persistence before widget update.
            check(settings.edit().putLong(KEY_LAST_SUCCESS, next).commit()) {
                "Could not persist last successful check"
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
