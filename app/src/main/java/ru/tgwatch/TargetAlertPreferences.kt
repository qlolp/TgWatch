package ru.tgwatch

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/** Per-goal transient mute timers are local to this boot and never part of portable target settings. */
internal object TargetAlertPreferences {
    fun mute(ctx: Context, id: TargetId): Boolean =
        AlertMute.mute(store(ctx, id), SystemClock.elapsedRealtime(), boot(ctx))
    fun clear(ctx: Context, id: TargetId): Boolean = AlertMute.clear(store(ctx, id))
    fun remaining(ctx: Context, id: TargetId): Long {
        val state = store(ctx, id).read()
        val readOnly = object : AlertMute.Store {
            override fun read() = state
            override fun write(state: AlertMute.State) = false
        }
        return AlertMute.remaining(readOnly, System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot(ctx))
    }
    private fun boot(ctx: Context): Int? = runCatching {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }
    }.getOrNull()
    private fun store(ctx: Context, id: TargetId): AlertMute.Store = object : AlertMute.Store {
        private val preferences = Prefs.sp(ctx)
        private fun key(field: String) = TargetPreferences.key(id, "mute_$field")
        override fun read(): AlertMute.State = preferences.all.let {
            AlertMute.State(it[key("boot")] as? Int, it[key("started")] as? Long ?: 0,
                it[key("until")] as? Long ?: 0)
        }
        override fun write(state: AlertMute.State): Boolean {
            val edit = preferences.edit()
            if (state.bootCount == null) edit.remove(key("boot")).remove(key("started")).remove(key("until"))
            else edit.putInt(key("boot"), state.bootCount).putLong(key("started"), state.startedElapsed)
                .putLong(key("until"), state.untilElapsed)
            return edit.commit()
        }
    }
}
