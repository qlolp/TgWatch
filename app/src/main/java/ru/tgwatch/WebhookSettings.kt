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
    const val MAX_RECEIVERS = 5
    private const val KEY_ENABLED = "enabled"
    private const val KEY_URL = "url"
    private const val KEY_FORMAT = "format"
    private const val KEY_EVENTS = "events"
    private const val KEY_SERVICES = "services"
    private const val KEY_RECEIVER_IDS = "receiver_ids"
    private val generations = LongArray(MAX_RECEIVERS)

    private fun store(ctx: Context): SharedPreferences {
        val app = ctx.applicationContext
        check(!app.isDeviceProtectedStorage) { "Webhook requires credential-protected storage" }
        return app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun unlocked(ctx: Context): Boolean =
        ctx.getSystemService(UserManager::class.java)?.isUserUnlocked == true

    /** Validated address, or null before unlock / when unset. */
    @Synchronized fun url(ctx: Context): String? = url(ctx, 0)

    @Synchronized fun url(ctx: Context, receiverId: Int): String? =
        if (receiverId in 0 until MAX_RECEIVERS && unlocked(ctx))
            WebhookPolicy.normalizeUrl(store(ctx).getString(key(receiverId, KEY_URL), null)) else null

    @Synchronized fun enabled(ctx: Context): Boolean = enabled(ctx, 0)

    @Synchronized fun enabled(ctx: Context, receiverId: Int): Boolean =
        receiverId in 0 until MAX_RECEIVERS && unlocked(ctx) &&
            store(ctx).getBoolean(key(receiverId, KEY_ENABLED), false) &&
            url(ctx, receiverId) != null

    /** The address to post to only while the switch is on; null otherwise. */
    @Synchronized fun activeUrl(ctx: Context): String? = activeUrl(ctx, 0)

    @Synchronized fun activeUrl(ctx: Context, receiverId: Int): String? =
        if (enabled(ctx, receiverId)) url(ctx, receiverId) else null

    /** The legacy single receiver remains receiver zero, backed by its original preference keys. */
    @Synchronized internal fun configuration(ctx: Context): WebhookConfiguration = configuration(ctx, 0)

    /** Readers and coordinator admission share this object's monitor with every settings write. */
    @Synchronized internal fun configuration(ctx: Context, receiverId: Int): WebhookConfiguration {
        require(receiverId in 0 until MAX_RECEIVERS)
        val current = if (unlocked(ctx)) store(ctx) else null
        val format = WebhookFormat.entries.firstOrNull {
            it.name == current?.getString(key(receiverId, KEY_FORMAT), null)
        }
            ?: WebhookFormat.TEXT
        val events = current?.getStringSet(key(receiverId, KEY_EVENTS), null)?.mapNotNull { saved ->
            WebhookEventKind.DELIVERY_EVENTS.firstOrNull { it.name == saved }
        }?.toSet() ?: WebhookEventKind.DELIVERY_EVENTS
        val services = current?.getStringSet(key(receiverId, KEY_SERVICES), null)
            ?.filter { it in WebhookStatuses.services }?.toSet() ?: WebhookStatuses.services
        return WebhookConfiguration(url(ctx, receiverId), enabled(ctx, receiverId), generations[receiverId],
            format, events, services, receiverId)
    }

    /** Receiver IDs contain no endpoint data and are bounded to a small fixed number of credential slots. */
    @Synchronized internal fun receiverIds(ctx: Context): List<Int> {
        val additional = if (unlocked(ctx)) store(ctx).getStringSet(KEY_RECEIVER_IDS, emptySet())
            .orEmpty().mapNotNull(String::toIntOrNull).filter { it in 1 until MAX_RECEIVERS }.distinct().sorted()
        else emptyList()
        return listOf(0) + additional
    }

    @Synchronized fun addReceiver(ctx: Context): Int? {
        if (!unlocked(ctx)) return null
        val current = receiverIds(ctx).toSet()
        val id = (1 until MAX_RECEIVERS).firstOrNull { it !in current } ?: return null
        val ids = current + id
        return if (committed(ctx, id, store(ctx).edit().putStringSet(KEY_RECEIVER_IDS,
                ids.filter { it != 0 }.map { it.toString() }.toSet())) == WriteResult.SAVED) id else null
    }

    @Synchronized fun removeReceiver(ctx: Context, receiverId: Int): Boolean {
        if (receiverId !in 1 until MAX_RECEIVERS || !unlocked(ctx)) return false
        val current = receiverIds(ctx)
        if (receiverId !in current) return false
        val editor = store(ctx).edit().putStringSet(KEY_RECEIVER_IDS,
            (current - receiverId).filter { it != 0 }.map { it.toString() }.toSet())
        listOf(KEY_ENABLED, KEY_URL, KEY_FORMAT, KEY_EVENTS, KEY_SERVICES)
            .forEach { editor.remove(key(receiverId, it)) }
        return committed(ctx, receiverId, editor) == WriteResult.SAVED
    }

    /** Selection only controls delivery; it never enables monitoring or changes a target's address. */
    @Synchronized fun setTargetsResult(ctx: Context, services: Set<String>): WriteResult =
        setTargetsResult(ctx, 0, services)

    @Synchronized fun setTargetsResult(ctx: Context, receiverId: Int, services: Set<String>): WriteResult = when {
        receiverId !in 0 until MAX_RECEIVERS -> WriteResult.INVALID
        !WebhookStatuses.services.containsAll(services) -> WriteResult.INVALID
        !unlocked(ctx) -> WriteResult.STORAGE_FAILED
        else -> committed(ctx, receiverId, store(ctx).edit().putStringSet(key(receiverId, KEY_SERVICES), services.toSet()))
    }

    /** Format and filter form one credential configuration generation; an empty filter is allowed. */
    @Synchronized fun setDeliveryResult(ctx: Context, format: WebhookFormat,
        events: Set<WebhookEventKind>): WriteResult = setDeliveryResult(ctx, 0, format, events)

    @Synchronized fun setDeliveryResult(ctx: Context, receiverId: Int, format: WebhookFormat,
        events: Set<WebhookEventKind>): WriteResult = when {
        receiverId !in 0 until MAX_RECEIVERS -> WriteResult.INVALID
        !WebhookEventKind.DELIVERY_EVENTS.containsAll(events) -> WriteResult.INVALID
        !unlocked(ctx) -> WriteResult.STORAGE_FAILED
        else -> committed(ctx, receiverId, store(ctx).edit().putString(key(receiverId, KEY_FORMAT), format.name)
            .putStringSet(key(receiverId, KEY_EVENTS), events.map { it.name }.toSet()))
    }

    /** Enabling requires a saved valid address. */
    @Synchronized fun setEnabled(ctx: Context, value: Boolean): Boolean =
        setEnabledResult(ctx, value) == WriteResult.SAVED

    @Synchronized fun setEnabled(ctx: Context, receiverId: Int, value: Boolean): Boolean =
        setEnabledResult(ctx, receiverId, value) == WriteResult.SAVED

    @Synchronized fun setEnabledResult(ctx: Context, value: Boolean): WriteResult =
        setEnabledResult(ctx, 0, value)

    @Synchronized fun setEnabledResult(ctx: Context, receiverId: Int, value: Boolean): WriteResult = when {
        receiverId !in 0 until MAX_RECEIVERS -> WriteResult.INVALID
        !unlocked(ctx) -> WriteResult.STORAGE_FAILED
        value && url(ctx, receiverId) == null -> WriteResult.INVALID
        else -> committed(ctx, receiverId, store(ctx).edit().putBoolean(key(receiverId, KEY_ENABLED), value))
    }

    /** Invalid input changes nothing. */
    @Synchronized fun setUrl(ctx: Context, raw: String): Boolean =
        setUrlResult(ctx, raw) == WriteResult.SAVED

    @Synchronized fun setUrl(ctx: Context, receiverId: Int, raw: String): Boolean =
        setUrlResult(ctx, receiverId, raw) == WriteResult.SAVED

    @Synchronized fun setUrlResult(ctx: Context, raw: String): WriteResult = setUrlResult(ctx, 0, raw)

    @Synchronized fun setUrlResult(ctx: Context, receiverId: Int, raw: String): WriteResult {
        if (receiverId !in 0 until MAX_RECEIVERS) return WriteResult.INVALID
        val url = WebhookPolicy.normalizeUrl(raw) ?: return WriteResult.INVALID
        return if (unlocked(ctx)) committed(ctx, receiverId, store(ctx).edit()
            .putString(key(receiverId, KEY_URL), url))
            else WriteResult.STORAGE_FAILED
    }

    /** Removes the address and turns the webhook off. */
    @Synchronized fun clear(ctx: Context): Boolean = clear(ctx, 0)

    @Synchronized fun clear(ctx: Context, receiverId: Int): Boolean {
        if (receiverId !in 0 until MAX_RECEIVERS || !unlocked(ctx)) return false
        val editor = store(ctx).edit()
        listOf(KEY_URL, KEY_ENABLED, KEY_FORMAT, KEY_EVENTS, KEY_SERVICES)
            .forEach { editor.remove(key(receiverId, it)) }
        return committed(ctx, receiverId, editor) == WriteResult.SAVED
    }

    /** Even a failed disk commit can replace SharedPreferences' in-memory configuration. */
    private fun committed(ctx: Context, receiverId: Int, editor: SharedPreferences.Editor): WriteResult {
        require(receiverId in 0 until MAX_RECEIVERS)
        generations[receiverId]++
        return if (editor.commit().also { WebhookNotifier.reset(receiverId) }) WriteResult.SAVED
            else WriteResult.STORAGE_FAILED
    }

    private fun key(receiverId: Int, key: String): String {
        require(receiverId in 0 until MAX_RECEIVERS)
        return if (receiverId == 0) key else "receiver_${receiverId}_$key"
    }
}
