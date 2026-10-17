package ru.tgwatch

import android.content.Context
import android.os.UserManager

/** Legacy informational URL and independent target URLs have different keys and require separate opt-in. */
internal object PrivateEndpoints {
    private const val EXTRA = "extra_custom"
    private const val OLD = "extra_probe_custom_url"

    fun legacy(ctx: Context): String {
        if (!unlocked(ctx)) return ""
        return ExtraProbes.normalizeCustomUrl(preferences(ctx).getString(EXTRA, null)).orEmpty()
    }

    fun setLegacy(ctx: Context, value: String, duringRecovery: Boolean = false): Boolean {
        val normalized = if (value.isBlank()) "" else ExtraProbes.normalizeCustomUrl(value)
        val valid = unlocked(ctx) && normalized != null && (duringRecovery || !BackupManager.hasPending())
        return valid && runCatching { preferences(ctx).edit().putString(EXTRA, normalized).commit() && run {
            val directory = java.io.File(ctx.applicationContext.dataDir, "shared_prefs")
            HistoryStorage.syncDirectory(directory)
            val edit = Prefs.sp(ctx).edit().remove(OLD)
            if (normalized.isNullOrEmpty()) edit.putBoolean("extra_probe_custom", false)
            edit.commit()
        } }.getOrDefault(false)
    }

    /** Called only by recovery workers, after successful CP durability and before deleting the old public field. */
    fun migrateLegacy(ctx: Context) {
        if (!unlocked(ctx) || !Prefs.sp(ctx).contains(OLD)) return
        val value = ExtraProbes.normalizeCustomUrl(Prefs.sp(ctx).getString(OLD, null)).orEmpty()
        check(setLegacy(ctx, value, duringRecovery = true)) { "Cannot move legacy endpoint to credential storage" }
    }

    private fun unlocked(ctx: Context) = ctx.applicationContext.getSystemService(UserManager::class.java).isUserUnlocked
    private fun preferences(ctx: Context) = ctx.applicationContext.getSharedPreferences(
        TargetPreferences.PRIVATE_FILE, Context.MODE_PRIVATE)
}
