package ru.tgwatch

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView

internal class RecoveryUi(private val activity: Activity) {
    private var waited = false
    fun preparing(): Boolean {
        if (RecoveryCoordinator.state == RecoveryState.READY) {
            if (waited) activity.recreate()
            return waited
        }
        waited = true
        disableControls(activity.findViewById(android.R.id.content))
        val preparing = RecoveryCoordinator.state == RecoveryState.INITIALIZING
        activity.findViewById<TextView>(R.id.tvTitle).text = if (preparing)
            activity.renderString(R.string.copy_recovery_ui_preparing_data)
            else activity.renderString(R.string.copy_recovery_ui_data_recovery_failed)
        activity.findViewById<TextView>(R.id.tvSince).text = activity.renderString(
            R.string.copy_recovery_ui_checks_are_paused)
        activity.findViewById<TextView>(R.id.tvReason).text = activity.renderString(
            R.string.copy_recovery_ui_wait_for_preparation_to)
        activity.findViewById<Button>(R.id.btnRestoreBackup).isEnabled = !preparing
        return true
    }
    private fun disableControls(view: View) {
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) disableControls(view.getChildAt(index))
        } else if (view is Button || view is CompoundButton || view is EditText) view.isEnabled = false
    }
}
