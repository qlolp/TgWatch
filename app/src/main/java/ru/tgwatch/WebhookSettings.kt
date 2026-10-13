package ru.tgwatch

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager

/**
 * The webhook address is a credential (an ntfy topic URL grants posting). Like the automation
 * token it never enters Prefs, device-protected storage, portable backups, diagnostics or logs.
 */
object WebhookSettings {
    internal const val FILE = "webhook-private-v1"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_URL = "url"
    private var generation = 0L

    private fun store(ctx: Context): SharedPreferences {
        val app = ctx.applicationContext
        check(!app.isDeviceProtectedStorage) { "Webhook requires credential-protected storage" }
        return app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun unlocked(ctx: Context): Boolean =
        ctx.getSystemService(UserManager::class.java)?.isUserUnlocked == true

    /** Validated address, or null before unlock / when unset. */
    @Synchronized fun url(ctx: Context): String? =
        if (unlocked(ctx)) WebhookPolicy.normalizeUrl(store(ctx).getString(KEY_URL, null)) else null

    @Synchronized fun enabled(ctx: Context): Boolean =
        unlocked(ctx) && store(ctx).getBoolean(KEY_ENABLED, false) && url(ctx) != null

    /** The address to post to only while the switch is on; null otherwise. */
    @Synchronized fun activeUrl(ctx: Context): String? = if (enabled(ctx)) url(ctx) else null

    /** Readers and coordinator admission share this object's monitor with every settings write. */
    @Synchronized internal fun configuration(ctx: Context): WebhookConfiguration =
        WebhookConfiguration(url(ctx), enabled(ctx), generation)

    /** Why a write did or did not happen, so the screen can name the real cause. */
    enum class SaveResult { OK, INVALID_URL, LOCKED, DISK }

    /** Enabling requires a saved valid address. */
    @Synchronized fun enable(ctx: Context, value: Boolean): SaveResult = when {
        !unlocked(ctx) -> SaveResult.LOCKED
        value && url(ctx) == null -> SaveResult.INVALID_URL
        committed(store(ctx).edit().putBoolean(KEY_ENABLED, value)) -> SaveResult.OK
        else -> SaveResult.DISK
    }

    /** Invalid input changes nothing. */
    @Synchronized fun save(ctx: Context, raw: String): SaveResult {
        val url = WebhookPolicy.normalizeUrl(raw)
        return when {
            url == null -> SaveResult.INVALID_URL
            !unlocked(ctx) -> SaveResult.LOCKED
            committed(store(ctx).edit().putString(KEY_URL, url)) -> SaveResult.OK
            else -> SaveResult.DISK
        }
    }


    /** Removes the address and turns the webhook off. */
    @Synchronized fun clear(ctx: Context): Boolean =
        unlocked(ctx) && committed(store(ctx).edit().remove(KEY_URL).remove(KEY_ENABLED))

    /** Even a failed disk commit can replace SharedPreferences' in-memory configuration. */
    private fun committed(editor: SharedPreferences.Editor): Boolean {
        generation++
        return editor.commit().also { WebhookNotifier.reset() }
    }
}
