package ru.tgwatch

import android.content.Context
import android.util.Log

/** Device-local diagnostic state; never included in portable settings backups. */
object LastSuccessStore {
    private const val KEY_LAST_SUCCESS = "last_success_at"

    @Synchronized
    @Suppress("TooGenericExceptionCaught") // Diagnostic storage failures must never reject a measured result.
    fun record(ctx: Context, statusName: String, checkedAt: Long, id: TargetId = TargetId.TELEGRAM) {
        try {
            val settings = Prefs.sp(ctx)
            val previous = lastSuccessAt(ctx, id)
            val next = LastSuccessPolicy.updated(previous, statusName, checkedAt)
            if (next != previous) {
                // This diagnostic is best-effort; disk failure cannot replace a measured result.
                check(settings.edit().putLong(key(id), next).commit()) {
                    "Could not persist last successful check"
                }
            }
        } catch (e: Exception) {
            Log.w("TgWatch", "last success record failed: ${e.javaClass.simpleName}")
        }
    }

    @Synchronized
    fun lastSuccessAt(ctx: Context, id: TargetId = TargetId.TELEGRAM): Long =
        (Prefs.sp(ctx).all[key(id)] as? Long)?.coerceAtLeast(0L) ?: 0L

    @Synchronized
    fun clear(ctx: Context) {
        val edit = Prefs.sp(ctx).edit()
        TargetId.entries.forEach { edit.remove(key(it)) }
        check(edit.commit()) {
            "Could not clear last successful check"
        }
    }
    private fun key(id: TargetId) = if (id == TargetId.TELEGRAM) KEY_LAST_SUCCESS
        else TargetPreferences.key(id, KEY_LAST_SUCCESS)
}
