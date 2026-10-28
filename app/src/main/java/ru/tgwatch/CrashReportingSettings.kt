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
    @Volatile private var storageFailed = false

    private fun store(ctx: Context): SharedPreferences {
        val app = ctx.applicationContext
        check(!app.isDeviceProtectedStorage) { "Crash reporting requires credential-protected storage" }
        return app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun unlocked(ctx: Context): Boolean =
        ctx.getSystemService(UserManager::class.java)?.isUserUnlocked == true

    @Synchronized fun endpoint(ctx: Context): String? =
        if (unlocked(ctx) && !storageFailed)
            CrashReportingPolicy.normalizeEndpoint(store(ctx).getString(KEY_ENDPOINT, null)) else null

    @Synchronized fun enabled(ctx: Context): Boolean =
        unlocked(ctx) && !storageFailed && store(ctx).getBoolean(KEY_CONSENT, false) && endpoint(ctx) != null

    /** One synchronized opt-in snapshot prevents mixing an old address with new consent. */
    @Synchronized fun activeEndpoint(ctx: Context): String? =
        if (!unlocked(ctx) || storageFailed) null else store(ctx).let { preferences ->
            CrashReportingPolicy.normalizeEndpoint(preferences.getString(KEY_ENDPOINT, null))
                .takeIf { preferences.getBoolean(KEY_CONSENT, false) }
        }

    /** Saving or replacing an endpoint always requires a fresh, separate consent action. */
    @Synchronized fun setEndpoint(ctx: Context, raw: String): WriteResult {
        val endpoint = CrashReportingPolicy.normalizeEndpoint(raw)
        return when {
            endpoint == null -> WriteResult.INVALID
            !unlocked(ctx) -> WriteResult.STORAGE_FAILED
            else -> commit(store(ctx).edit().putString(KEY_ENDPOINT, endpoint).putBoolean(KEY_CONSENT, false))
        }
    }

    @Synchronized fun setEnabled(ctx: Context, value: Boolean): WriteResult = when {
        !unlocked(ctx) -> WriteResult.STORAGE_FAILED
        value && endpoint(ctx) == null -> WriteResult.INVALID
        else -> commit(store(ctx).edit().putBoolean(KEY_CONSENT, value))
    }

    /** Revokes consent and deletes the private endpoint. */
    @Synchronized fun clear(ctx: Context): Boolean {
        if (!unlocked(ctx)) return false
        return commit(store(ctx).edit().remove(KEY_ENDPOINT).remove(KEY_CONSENT)) == WriteResult.SAVED
    }

    private fun commit(editor: SharedPreferences.Editor): WriteResult {
        storageFailed = !editor.commit()
        return if (storageFailed) WriteResult.STORAGE_FAILED else WriteResult.SAVED
    }
}
