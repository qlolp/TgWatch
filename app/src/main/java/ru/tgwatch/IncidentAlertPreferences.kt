package ru.tgwatch

import android.content.Context

/** Device-local presentation preference; deliberately absent from portable backup settings. */
internal object IncidentAlertPreferences {
    private const val KEY = "group_incident_alerts"
    fun enabled(ctx: Context): Boolean = Prefs.sp(ctx).getBoolean(KEY, false)
    fun setEnabled(ctx: Context, value: Boolean) {
        Prefs.sp(ctx).edit().putBoolean(KEY, value).apply()
        if (!value) IncidentAlertCoordinator.disable(ctx)
    }
}
