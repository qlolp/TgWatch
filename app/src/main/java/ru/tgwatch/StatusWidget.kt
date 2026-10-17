package ru.tgwatch

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** Existing instances remain Telegram; new instances select their own fixed public goal. */
class StatusWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        refresh(ctx)
    }
    private fun refresh(ctx: Context) {
        val pending = goAsync()
        val completion = ComponentCompletion({ pending?.finish() })
        WidgetUpdates.request(ctx, false) { completion.finish() }
    }
    override fun onEnabled(ctx: Context) { refresh(ctx) }
    override fun onAppWidgetOptionsChanged(ctx: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        refresh(ctx)
    }
    override fun onDeleted(ctx: Context, ids: IntArray) {
        val owned = WidgetUpdates.ids(ctx).toSet()
        val removed = ids.filter { it > 0 && it !in owned }.take(MAX_DELETED)
        removed.forEach { id ->
            WidgetPreferences.token(ctx, id)?.let { token ->
                val target = WidgetPreferences.target(ctx, id)
                listOf("check", "mute").forEach { action ->
                    pending(ctx, WidgetIdentity(id, target, action, token)).cancel()
                }
            }
        }
        WidgetUpdates.delete(ctx, removed)
    }
    companion object {
        private const val MAX_DELETED = 256
        fun updateAll(ctx: Context) = WidgetUpdates.request(ctx, false)
        fun refreshIfChanged(ctx: Context) = WidgetUpdates.request(ctx, true)
        internal fun handleClick(ctx: Context) {
            if (!TargetActions.check(ctx, TargetId.TELEGRAM)) open(ctx, TargetId.TELEGRAM)
        }
        internal fun handleClick(ctx: Context, identity: WidgetIdentity) {
            if (!current(ctx, identity)) return
            if (!TargetActions.check(ctx, identity.targetId)) open(ctx, identity.targetId)
        }
        internal fun current(ctx: Context, identity: WidgetIdentity): Boolean =
            identity.widgetId in WidgetUpdates.ids(ctx) && WidgetPreferences.current(ctx, identity)
        internal fun open(ctx: Context, id: TargetId) {
            ctx.startActivity(Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(TargetAutomation.EXTRA_TARGET_ID, id.wire))
        }
        internal fun pending(ctx: Context, identity: WidgetIdentity): PendingIntent = PendingIntent.getBroadcast(ctx, 0,
            Intent(ctx, WidgetClickReceiver::class.java).setData(Uri.parse(identity.uri)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
