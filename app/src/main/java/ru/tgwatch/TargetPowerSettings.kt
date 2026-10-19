package ru.tgwatch

import android.content.Context

/** Device-only opt-in; never part of the portable backup schema. */
internal object TargetPowerSettings {
    const val KEY = "secondary_power_saving"
    fun enabled(ctx: Context): Boolean = Prefs.sp(ctx).getBoolean(KEY, false)
    fun setEnabled(ctx: Context, enabled: Boolean): Boolean = synchronized(History) {
        !BackupManager.hasPending() && Prefs.sp(ctx).edit().putBoolean(KEY, enabled).commit()
    }
}
