package ru.tgwatch

import android.content.Context
import android.os.UserManager
import android.provider.Settings
import java.security.SecureRandom

/** Credentials never enter Prefs, device-protected storage, portable backup, or diagnostics. */
object AutomationSettings {
    internal const val FILE = "automation-private-v1"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TOKEN = "token"
    private const val KEY_LAST_ELAPSED = "last_check_elapsed"
    private const val KEY_BOOT = "last_check_boot"
    private const val TOKEN_BYTES = 32
    private const val BYTE_MASK = 0xff
    private const val NIBBLE_MASK = 0x0f
    private const val NIBBLE_BITS = 4

    private fun store(ctx: Context): android.content.SharedPreferences {
        val app = ctx.applicationContext
        check(!app.isDeviceProtectedStorage) { "Automation requires credential-protected storage" }
        return app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun unlocked(ctx: Context): Boolean =
        ctx.getSystemService(UserManager::class.java)?.isUserUnlocked == true

    @Synchronized fun enabled(ctx: Context): Boolean = unlocked(ctx) &&
        store(ctx).getBoolean(KEY_ENABLED, false) && AutomationPolicy.validToken(store(ctx).getString(KEY_TOKEN, null))

    @Synchronized fun token(ctx: Context): String? = if (enabled(ctx)) store(ctx).getString(KEY_TOKEN, null) else null

    @Synchronized fun setEnabled(ctx: Context, value: Boolean): Boolean {
        if (!unlocked(ctx)) return false
        val prefs = store(ctx)
        val edit = prefs.edit().putBoolean(KEY_ENABLED, value)
        if (value) {
            if (!AutomationPolicy.validToken(prefs.getString(KEY_TOKEN, null))) edit.putString(KEY_TOKEN, newToken())
        } else {
            edit.remove(KEY_TOKEN).remove(KEY_LAST_ELAPSED).remove(KEY_BOOT)
            prefs.all.keys.filter { it.startsWith("$KEY_LAST_ELAPSED.") || it.startsWith("$KEY_BOOT.") }
                .forEach(edit::remove)
        }
        return edit.commit()
    }

    @Synchronized fun regenerate(ctx: Context): String? {
        if (!enabled(ctx)) return null
        val next = newToken()
        return if (store(ctx).edit().putString(KEY_TOKEN, next).commit()) next else null
    }

    /** Persist the elapsed-time throttle across process recreation, reset only on a new boot. */
    @Synchronized internal fun lastCheckElapsed(ctx: Context, id: TargetId = TargetId.TELEGRAM): Long? {
        val prefs = store(ctx)
        val boot = bootCount(ctx)
        val suffix = if (id == TargetId.TELEGRAM) "" else ".${id.wire}"
        if (!prefs.contains(KEY_LAST_ELAPSED + suffix) || prefs.getInt(KEY_BOOT + suffix, -1) != boot) return null
        return prefs.getLong(KEY_LAST_ELAPSED + suffix, 0)
    }

    @Synchronized internal fun recordCheck(ctx: Context, elapsed: Long, id: TargetId = TargetId.TELEGRAM): Boolean {
        val suffix = if (id == TargetId.TELEGRAM) "" else ".${id.wire}"
        return store(ctx).edit().putLong(KEY_LAST_ELAPSED + suffix, elapsed)
            .putInt(KEY_BOOT + suffix, bootCount(ctx)).commit()
    }

    private fun bootCount(ctx: Context): Int =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)

    private fun newToken(): String {
        val random = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
        val hex = "0123456789abcdef"
        return buildString(AutomationPolicy.TOKEN_LENGTH) {
            random.forEach { byte ->
                val value = byte.toInt() and BYTE_MASK
                append(hex[value ushr NIBBLE_BITS])
                append(hex[value and NIBBLE_MASK])
            }
        }
    }
}
