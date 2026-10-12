package ru.tgwatch

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertMutePrefsTest {
    @Test fun expiredLegacyDeadlineCannotReviveAfterWallClockRollback() {
        val now = 1_760_000_000_000L
        val settings = MuteTestPreferences(mapOf("alerts_muted_until" to AlertMute.until(now)))
        val ctx = settings.context()
        assertEquals(0L, Prefs.alertsMutedUntil(ctx, now + AlertMute.DURATION_MS))
        assertEquals("An expired persisted mute must remain cleared after clock rollback", 0L,
            Prefs.alertsMutedUntil(ctx, now))
    }

    @Test fun alertSuppressionDoesNotDependOnWallClockProjectionBeingPositive() {
        val settings = MuteTestPreferences()
        val ctx = settings.context()
        assertTrue(Prefs.muteAlerts(ctx))
        assertFalse(Prefs.alertsAllowed(ctx, Long.MIN_VALUE))
    }
}

/** Android's commit updates the in-process map even when its disk write fails. */
internal class MuteTestPreferences(initial: Map<String, Any> = emptyMap()) {
    val values = initial.toMutableMap()
    var writesSucceed = true
    val preferences: SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
    ) { _, method, args ->
        when (method.name) {
            "getAll" -> values.toMap()
            "getLong", "getInt", "getBoolean", "getString" -> values[args!![0]] ?: args[1]
            "contains" -> values.containsKey(args!![0])
            "edit" -> editor()
            else -> null
        }
    } as SharedPreferences

    private fun editor(): SharedPreferences.Editor {
        val changes = mutableMapOf<String, Any?>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)
        ) { _, method, args ->
            when {
                method.name.startsWith("put") -> { changes[args!![0] as String] = args[1]; editor }
                method.name == "remove" -> { changes[args!![0] as String] = null; editor }
                method.name == "commit" || method.name == "apply" -> {
                    changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                    if (method.name == "commit") writesSucceed else null
                }
                else -> editor
            }
        } as SharedPreferences.Editor
        return editor
    }

    fun context(): Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
    }
}
