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
            Lang.t("Подготовка данных…", "Preparing data…")
            else Lang.t("Не удалось восстановить данные", "Data recovery failed")
        activity.findViewById<TextView>(R.id.tvSince).text = Lang.t("Проверки приостановлены", "Checks are paused")
        activity.findViewById<TextView>(R.id.tvReason).text = Lang.t(
            "Дождитесь завершения подготовки или повторите восстановление копии.",
            "Wait for preparation to finish, or retry restoring the backup.")
        activity.findViewById<Button>(R.id.btnRestoreBackup).isEnabled = !preparing
        return true
    }
    private fun disableControls(view: View) {
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) disableControls(view.getChildAt(index))
        } else if (view is Button || view is CompoundButton || view is EditText) view.isEnabled = false
    }
}
