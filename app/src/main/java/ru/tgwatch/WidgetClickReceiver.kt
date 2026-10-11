package ru.tgwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Taps on the widget. Not exported: only the widget's own PendingIntent reaches it,
 * so other apps cannot start checks through the exported widget provider.
 */
class WidgetClickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        StatusWidget.handleClick(context)
    }
}
