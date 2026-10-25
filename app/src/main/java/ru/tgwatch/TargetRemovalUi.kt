package ru.tgwatch

import android.app.Activity
import android.app.AlertDialog
import android.widget.Button
import android.widget.LinearLayout

/** Only optional custom slots can be removed; the first custom goal keeps its pre-1.24 identity. */
internal object TargetRemovalUi {
    fun add(activity: Activity, id: TargetId, content: LinearLayout, close: () -> Unit) {
        if (!id.isOptional) return
        content.addView(Button(activity).apply {
            setText(R.string.target_remove)
            isSaveEnabled = false
            setOnClickListener {
                AlertDialog.Builder(activity)
                    .setMessage(activity.getString(R.string.target_remove_confirm, TargetNames.label(activity, id)))
                    .setNegativeButton(R.string.target_cancel, null)
                    .setPositiveButton(R.string.target_remove) { _, _ ->
                        close()
                        val app = activity.applicationContext
                        TargetPreferences.stop(app, id)
                        TargetActions.submit(activity, id) {
                            TargetSlots.remove(app, id).also { TargetActions.reconcile(app, id) }
                        }
                    }.show()
            }
        })
    }
}
