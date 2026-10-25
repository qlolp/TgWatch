package ru.tgwatch

import android.content.Context

/**
 * Optional custom HTTPS goals. A slot is wiped both when it is removed and again when it is added,
 * so an address, history or alert state from an earlier use can never reappear in a new goal.
 */
internal object TargetSlots {
    private const val ADDED = "added"

    fun present(ctx: Context, id: TargetId): Boolean =
        !id.isOptional || Prefs.sp(ctx).getBoolean(TargetPreferences.key(id, ADDED), false)

    fun visible(ctx: Context): List<TargetId> = TargetId.entries.filter { present(ctx, it) }

    fun free(ctx: Context): TargetId? = TargetId.entries.firstOrNull { it.isOptional && !present(ctx, it) }

    /** Restore writes the flag for every goal the backup describes; public keys only. */
    fun putPresent(edit: android.content.SharedPreferences.Editor, id: TargetId, present: Boolean) {
        if (id.isOptional) edit.putBoolean(TargetPreferences.key(id, ADDED), present)
    }

    fun add(ctx: Context, id: TargetId): Boolean = id.isOptional && !present(ctx, id) &&
        TargetSettingWrites.slot(ctx, id) { reset(ctx, id, added = true) }

    fun remove(ctx: Context, id: TargetId): Boolean = id.isOptional && present(ctx, id) &&
        TargetSettingWrites.slot(ctx, id) { reset(ctx, id, added = false) }

    /** Ordered worker under the History monitor. Hiding commits first; a crash leaves only an invisible slot. */
    private fun reset(ctx: Context, id: TargetId, added: Boolean) {
        TargetRestore.requireUnlocked(ctx)
        val preferences = Prefs.sp(ctx)
        val prefix = TargetPreferences.key(id, "")
        val revision = TargetPreferences.endpointRevision(ctx, id)
        val hide = preferences.edit()
        preferences.all.keys.filter { it.startsWith(prefix) || it == MaintenanceWindows.key(id) }
            .forEach(hide::remove)
        // A new revision keeps any cached snapshot of the old goal from matching the new one.
        check(hide.putLong(TargetPreferences.key(id, "endpoint_revision"), Math.addExact(revision, 1L))
            .putBoolean(TargetPreferences.key(id, ADDED), false).commit()) { "Cannot hide target" }
        check(ctx.applicationContext.getSharedPreferences(TargetPreferences.PRIVATE_FILE, Context.MODE_PRIVATE)
            .edit().remove(id.wire).commit()) { "Cannot remove private endpoint" }
        TargetPersistence.storage().replaceRows(id, emptyList())
        if (added) check(preferences.edit().putBoolean(TargetPreferences.key(id, ADDED), true).commit()) {
            "Cannot add target"
        }
    }
}
