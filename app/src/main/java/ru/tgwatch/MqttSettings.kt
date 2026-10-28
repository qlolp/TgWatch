package ru.tgwatch

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager

/**
 * Optional native MQTT adapter (at most one broker). Broker, topic and credentials are secrets: like the webhook
 * address they live only in credential-protected storage and never enter Prefs, Direct Boot storage, portable
 * backups, diagnostics, logs, public replies or saved view state.
 */
object MqttSettings {
    internal const val FILE = "mqtt-private-v1"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_TOPIC = "topic"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private var generation = 0L

    private fun store(ctx: Context): SharedPreferences {
        val app = ctx.applicationContext
        check(!app.isDeviceProtectedStorage) { "MQTT requires credential-protected storage" }
        return app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun unlocked(ctx: Context): Boolean =
        ctx.getSystemService(UserManager::class.java)?.isUserUnlocked == true

    /** Validated settings, or null before unlock / when incomplete. */
    @Synchronized internal fun endpoint(ctx: Context): MqttEndpoint? {
        if (!unlocked(ctx)) return null
        val saved = store(ctx)
        val host = MqttPolicy.normalizeHost(saved.getString(KEY_HOST, null))
        val port = saved.getInt(KEY_PORT, MqttPolicy.DEFAULT_PORT).takeIf { it in 1..MqttPolicy.MAX_PORT }
        val topic = MqttPolicy.normalizeTopic(saved.getString(KEY_TOPIC, null))
        val username = MqttPolicy.normalizeSecret(saved.getString(KEY_USERNAME, null),
            MqttPolicy.MAX_USERNAME_BYTES)?.ifEmpty { null }
        val password = MqttPolicy.normalizeSecret(saved.getString(KEY_PASSWORD, null),
            MqttPolicy.MAX_PASSWORD_BYTES)?.ifEmpty { null }
        return if (host == null || port == null || topic == null) null
            else MqttEndpoint(host, port, topic, username, password)
    }

    @Synchronized fun enabled(ctx: Context): Boolean =
        unlocked(ctx) && store(ctx).getBoolean(KEY_ENABLED, false) && endpoint(ctx) != null

    /** Host only, for display; topic and credentials are never shown. */
    @Synchronized fun hostLabel(ctx: Context): String? = endpoint(ctx)?.host

    /**
     * The coordinator sees only a placeholder address: identity is the credential generation, so broker details
     * never leave this object. Measured target status is unaffected; the adapter only consumes publications.
     */
    @Synchronized internal fun configuration(ctx: Context): WebhookConfiguration = WebhookConfiguration(
        if (endpoint(ctx) != null) "mqtts://configured" else null, enabled(ctx), generation, WebhookFormat.JSON)

    @Suppress("LongParameterList")
    @Synchronized fun setEndpointResult(ctx: Context, host: String, port: String, topic: String,
        username: String, password: String): WebhookSettings.WriteResult {
        val validHost = MqttPolicy.normalizeHost(host)
        val validPort = if (port.isBlank()) MqttPolicy.DEFAULT_PORT else MqttPolicy.normalizePort(port)
        val validTopic = MqttPolicy.normalizeTopic(topic)
        val validUser = MqttPolicy.normalizeSecret(username.trim(), MqttPolicy.MAX_USERNAME_BYTES)
        val validPassword = MqttPolicy.normalizeSecret(password, MqttPolicy.MAX_PASSWORD_BYTES)
        return when {
            validHost == null || validPort == null || validTopic == null || validUser == null ||
                validPassword == null -> WebhookSettings.WriteResult.INVALID
            !unlocked(ctx) -> WebhookSettings.WriteResult.STORAGE_FAILED
            else -> committed(store(ctx).edit().putString(KEY_HOST, validHost).putInt(KEY_PORT, validPort)
                .putString(KEY_TOPIC, validTopic).putString(KEY_USERNAME, validUser)
                .putString(KEY_PASSWORD, validPassword))
        }
    }

    @Synchronized fun setEnabledResult(ctx: Context, value: Boolean): WebhookSettings.WriteResult = when {
        !unlocked(ctx) -> WebhookSettings.WriteResult.STORAGE_FAILED
        value && endpoint(ctx) == null -> WebhookSettings.WriteResult.INVALID
        else -> committed(store(ctx).edit().putBoolean(KEY_ENABLED, value))
    }

    /** Removes broker, topic and credentials and turns the adapter off. */
    @Synchronized fun clear(ctx: Context): Boolean {
        if (!unlocked(ctx)) return false
        val editor = store(ctx).edit()
        listOf(KEY_ENABLED, KEY_HOST, KEY_PORT, KEY_TOPIC, KEY_USERNAME, KEY_PASSWORD).forEach(editor::remove)
        return committed(editor) == WebhookSettings.WriteResult.SAVED
    }

    /** Even a failed disk commit can replace SharedPreferences' in-memory configuration. */
    private fun committed(editor: SharedPreferences.Editor): WebhookSettings.WriteResult {
        generation++
        return if (editor.commit().also { MqttNotifier.reset() }) WebhookSettings.WriteResult.SAVED
            else WebhookSettings.WriteResult.STORAGE_FAILED
    }
}
