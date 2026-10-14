package ru.tgwatch

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.WindowInsets

/** Preserve authored padding when repeated Android 15 edge-to-edge insets arrive. */
internal object ScreenInsets {
    fun apply(activity: Activity, root: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        // Deprecated only on Android 15, where edge-to-edge is already enforced; 11-14 still need it.
        @Suppress("DEPRECATION")
        activity.window.setDecorFitsSystemWindows(false)
        root.fitsSystemWindows = false
        val original = Rect(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(original.left + bars.left, original.top + bars.top,
                original.right + bars.right, original.bottom + bars.bottom)
            insets
        }
        root.requestApplyInsets()
    }
}
