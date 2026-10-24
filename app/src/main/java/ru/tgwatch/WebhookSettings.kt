package ru.tgwatch

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager

/**
 * The webhook address is a credential (an ntfy topic URL grants posting). Like the automation
 * token it never enters Prefs, device-protected storage, portable backups, diagnostics or logs.
 */
// Boolean setters preserve existing callers; detailed setters keep all credential writes under one lock.
@Suppress("TooManyFunctions")
object WebhookSettings {
    enum class WriteResult { SAVED, INVALID, STORAGE_FAILED }

    internal const val FILE = "webhook-private-v1"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_URL = "url"
    private const val KEY_FORMAT = "format"
    private const val KEY_EVENTS = "events"
    private const val KEY_SERVICES = "services"
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
    @Synchronized internal fun configuration(ctx: Context): WebhookConfiguration {
        val current = if (unlocked(ctx)) store(ctx) else null
        val format = WebhookFormat.entries.firstOrNull { it.name == current?.getString(KEY_FORMAT, null) }
            ?: WebhookFormat.TEXT
        val events = current?.getStringSet(KEY_EVENTS, null)?.mapNotNull { saved ->
            WebhookEventKind.DELIVERY_EVENTS.firstOrNull { it.name == saved }
        }?.toSet() ?: WebhookEventKind.DELIVERY_EVENTS
        val services = current?.getStringSet(KEY_SERVICES, null)
            ?.filter { it in WebhookStatuses.services }?.toSet() ?: WebhookStatuses.services
        return WebhookConfiguration(url(ctx), enabled(ctx), generation, format, events, services)
    }

    /** Selection only controls delivery; it never enables monitoring or changes a target's address. */
    @Synchronized fun setTargetsResult(ctx: Context, services: Set<String>): WriteResult = when {
        !WebhookStatuses.services.containsAll(services) -> WriteResult.INVALID
        !unlocked(ctx) -> WriteResult.STORAGE_FAILED
        else -> committed(store(ctx).edit().putStringSet(KEY_SERVICES, services.toSet()))
    }

    /** Format and filter form one credential configuration generation; an empty filter is allowed. */
    @Synchronized fun setDeliveryResult(ctx: Context, format: WebhookFormat,
        events: Set<WebhookEventKind>): WriteResult = when {
        !WebhookEventKind.DELIVERY_EVENTS.containsAll(events) -> WriteResult.INVALID
        !unlocked(ctx) -> WriteResult.STORAGE_FAILED
        else -> committed(store(ctx).edit().putString(KEY_FORMAT, format.name)
            .putStringSet(KEY_EVENTS, events.map { it.name }.toSet()))
    }

    /** Enabling requires a saved valid address. */
    @Synchronized fun setEnabled(ctx: Context, value: Boolean): Boolean =
        setEnabledResult(ctx, value) == WriteResult.SAVED

    @Synchronized fun setEnabledResult(ctx: Context, value: Boolean): WriteResult = when {
        !unlocked(ctx) -> WriteResult.STORAGE_FAILED
        value && url(ctx) == null -> WriteResult.INVALID
        else -> committed(store(ctx).edit().putBoolean(KEY_ENABLED, value))
    }

    /** Invalid input changes nothing. */
    @Synchronized fun setUrl(ctx: Context, raw: String): Boolean =
        setUrlResult(ctx, raw) == WriteResult.SAVED

    @Synchronized fun setUrlResult(ctx: Context, raw: String): WriteResult {
        val url = WebhookPolicy.normalizeUrl(raw) ?: return WriteResult.INVALID
        return if (unlocked(ctx)) committed(store(ctx).edit().putString(KEY_URL, url))
            else WriteResult.STORAGE_FAILED
    }

    /** Removes the address and turns the webhook off. */
    @Synchronized fun clear(ctx: Context): Boolean =
        unlocked(ctx) && committed(store(ctx).edit().remove(KEY_URL).remove(KEY_ENABLED)
            .remove(KEY_FORMAT).remove(KEY_EVENTS).remove(KEY_SERVICES)) == WriteResult.SAVED

    /** Even a failed disk commit can replace SharedPreferences' in-memory configuration. */
    private fun committed(editor: SharedPreferences.Editor): WriteResult {
        generation++
        return if (editor.commit().also { WebhookNotifier.reset() }) WriteResult.SAVED else WriteResult.STORAGE_FAILED
    }
}
