package ru.tgwatch

import android.content.Context
import android.os.Looper

/** Explicit user opt-in only. Private legacy addresses move on the bounded settings worker. */
internal object TargetLegacyConversion {
    private const val ENDPOINT_FENCE_ADVANCE = 2L
    fun available(ctx: Context, id: TargetId): Boolean = when (id) {
        TargetId.MATRIX -> Prefs.extraMatrix(ctx)
        TargetId.WHATSAPP -> Prefs.extraWhatsapp(ctx)
        TargetId.CUSTOM -> Prefs.extraCustom(ctx)
        else -> false
    }
    fun convert(ctx: Context, id: TargetId, expected: Long): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper())
        val revision = if (available(ctx, id)) prepareEndpoint(ctx, id, expected) else null
        if (revision == null) return false
        val definition = TargetPreferences.storedDefinition(ctx, id).copy(enabled = true)
        val saved = TargetSettingWrites.definition(ctx, definition, revision)
        if (saved) when (id) {
            TargetId.MATRIX -> Prefs.setExtraMatrix(ctx, false)
            TargetId.WHATSAPP -> Prefs.setExtraWhatsapp(ctx, false)
            TargetId.CUSTOM -> Prefs.setExtraCustom(ctx, false)
            else -> Unit
        }
        return saved
    }
    private fun prepareEndpoint(ctx: Context, id: TargetId, revision: Long): Long? =
        if (id != TargetId.CUSTOM) revision else
            ExtraProbes.normalizeCustomUrl(Prefs.extraCustomUrl(ctx))?.takeIf {
                TargetSettingWrites.endpoint(ctx, id, it, revision)
            }?.let { Math.addExact(revision, ENDPOINT_FENCE_ADVANCE) }

}
