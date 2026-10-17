package ru.tgwatch

import android.content.Context

/** Commit a public definition without replacing a newer stop/profile choice or opting another target in. */
internal object TargetDefinitionUpdate {
    private data class Before(val definition: TargetDefinition, val enabled: Boolean, val profile: String?,
        val otherEnabled: Map<TargetId, Boolean>, val revisions: Map<TargetId, Long>)

    fun apply(ctx: Context, value: TargetDefinition, token: TargetSettingsFence.Token) {
        val before = synchronized(TargetPreferences) { capture(ctx, value.id, token) }
        require(value.requiresEndpoint == before.definition.requiresEndpoint) { "Use the endpoint transaction" }
        val privateReady = !value.requiresEndpoint || TargetPreferences.storedEndpoint(ctx, value.id) != null
        require(!value.enabled || privateReady) { "Review an endpoint before enabling this goal" }
        synchronized(TargetPreferences) { publish(ctx, value, token, before) }
        if (!Prefs.sp(ctx).edit().commit()) {
            synchronized(TargetPreferences) { rollback(ctx, value.id, token, before) }
            error("Cannot save target settings")
        }
    }

    private fun capture(ctx: Context, id: TargetId, token: TargetSettingsFence.Token): Before {
        check(TargetPreferences.fence.snapshot(id).revision == token.revision) { "Settings were superseded" }
        return Before(TargetPreferences.storedDefinition(ctx, id), Prefs.isEnabled(ctx),
            Prefs.sp(ctx).getString("power_profile", null),
            TargetId.entries.filter { it != id }.associateWith { TargetPreferences.storedDefinition(ctx, it).enabled },
            TargetId.entries.associateWith { TargetPreferences.fence.snapshot(it).revision })
    }

    private fun publish(ctx: Context, value: TargetDefinition, token: TargetSettingsFence.Token, before: Before) {
        check(TargetPreferences.fence.snapshot(value.id).revision == token.revision) { "Settings were superseded" }
        val edit = TargetSettingWrites.write(Prefs.sp(ctx).edit(), value)
        if (value.id == TargetId.TELEGRAM && value.intervalSec != before.definition.intervalSec)
            edit.putString("power_profile", "CUSTOM")
        if (value.enabled) {
            if (!before.enabled) before.otherEnabled.keys.forEach {
                edit.putBoolean(TargetPreferences.key(it, "enabled"), false)
            }
            edit.putBoolean("enabled", true)
        }
        edit.apply()
    }

    private fun rollback(ctx: Context, id: TargetId, token: TargetSettingsFence.Token, before: Before) {
        if (TargetPreferences.fence.snapshot(id).revision != token.revision) return
        val edit = TargetSettingWrites.write(Prefs.sp(ctx).edit(), before.definition)
            .putBoolean("enabled", before.enabled)
        if (id == TargetId.TELEGRAM) edit.putString("power_profile", before.profile)
        before.otherEnabled.forEach { (other, enabled) ->
            if (TargetPreferences.fence.snapshot(other).revision == before.revisions.getValue(other))
                edit.putBoolean(TargetPreferences.key(other, "enabled"), enabled)
        }
        edit.apply()
    }
}
