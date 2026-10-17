package ru.tgwatch

import android.content.Context
import java.util.UUID

internal object WidgetPreferences {
    private const val DELETED = "deleted"
    private const val LEGACY_TOKEN = "00000000000000000000000000000000"
    private fun key(id: Int) = "widget.$id"
    fun target(ctx: Context, appWidgetId: Int): TargetId =
        TargetId.fromWire(Prefs.sp(ctx).getString(key(appWidgetId), null)?.substringBefore('|').orEmpty())
            ?: TargetId.TELEGRAM
    fun token(ctx: Context, appWidgetId: Int): String? {
        val stored = Prefs.sp(ctx).getString(key(appWidgetId), null)
        return if (stored == DELETED) null else stored?.substringAfter('|') ?: LEGACY_TOKEN
    }
    fun setTarget(ctx: Context, appWidgetId: Int, target: TargetId): Boolean {
        require(appWidgetId > 0)
        val token = UUID.randomUUID().toString().replace("-", "")
        return Prefs.sp(ctx).edit().putString(key(appWidgetId), "${target.wire}|$token").commit()
    }
    fun remove(ctx: Context, appWidgetId: Int): Boolean =
        Prefs.sp(ctx).edit().putString(key(appWidgetId), DELETED).commit()
    fun current(ctx: Context, identity: WidgetIdentity): Boolean =
        target(ctx, identity.widgetId) == identity.targetId && token(ctx, identity.widgetId) == identity.token
}
