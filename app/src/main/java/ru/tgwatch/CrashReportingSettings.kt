package ru.tgwatch

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager

/** The collector endpoint is a credential and never enters backups, diagnostics or logs. */
object CrashReportingSettings {
    enum class WriteResult { SAVED, INVALID, STORAGE_FAILED }

    internal const val FILE = "crash-reporting-private-v1"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_CONSENT = "consent"

    private fun store(ctx: Context): SharedPreferences {
        val app = ctx.applicationContext
        check(!app.isDeviceProtectedStorage) { "Crash reporting requires credential-protected storage" }
        return app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun unlocked(ctx: Context): Boolean =
        ctx.getSystemService(UserManager::class.java)?.isUserUnlocked == true

    @Synchronized fun endpoint(ctx: Context): String? =
        if (unlocked(ctx)) CrashReportingPolicy.normalizeEndpoint(store(ctx).getString(KEY_ENDPOINT, null)) else null

    @Synchronized fun enabled(ctx: Context): Boolean =
        unlocked(ctx) && store(ctx).getBoolean(KEY_CONSENT, false) && endpoint(ctx) != null

    /** Saving or replacing an endpoint always requires a fresh, separate consent action. */
    @Synchronized fun setEndpoint(ctx: Context, raw: String): WriteResult {
        val endpoint = CrashReportingPolicy.normalizeEndpoint(raw) ?: return WriteResult.INVALID
        if (!unlocked(ctx)) return WriteResult.STORAGE_FAILED
        return if (store(ctx).edit().putString(KEY_ENDPOINT, endpoint).putBoolean(KEY_CONSENT, false).commit())
            WriteResult.SAVED else WriteResult.STORAGE_FAILED
    }

    @Synchronized fun setEnabled(ctx: Context, value: Boolean): WriteResult {
        if (!unlocked(ctx)) return WriteResult.STORAGE_FAILED
        if (value && endpoint(ctx) == null) return WriteResult.INVALID
        return if (store(ctx).edit().putBoolean(KEY_CONSENT, value).commit())
            WriteResult.SAVED else WriteResult.STORAGE_FAILED
    }

    /** Revokes consent and deletes the private endpoint. */
    @Synchronized fun clear(ctx: Context): Boolean =
        unlocked(ctx) && store(ctx).edit().remove(KEY_ENDPOINT).remove(KEY_CONSENT).commit()
}
