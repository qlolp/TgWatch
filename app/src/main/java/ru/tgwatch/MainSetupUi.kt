package ru.tgwatch

import android.view.View
import android.widget.Button
import android.widget.TextView

/** Permission guidance resolves the screen resources without adding state to the activity. */
internal object MainSetupUi {
    fun render(activity: android.app.Activity, notifOk: Boolean, batteryOk: Boolean, alarmOk: Boolean) {
        if (notifOk && batteryOk && alarmOk) {
            activity.findViewById<View>(R.id.setupCard).visibility = View.GONE
            return
        }
        activity.findViewById<View>(R.id.setupCard).visibility = View.VISIBLE
        val lines = mutableListOf(activity.renderString(R.string.copy_main_without_these_permissions_the))
        if (!notifOk) lines += activity.renderString(R.string.copy_main_turn_on_notifications_or)
        if (!batteryOk) lines += activity.renderString(
            R.string.copy_main_allow_unrestricted_background_use)
        if (!alarmOk) lines += activity.renderString(R.string.copy_main_allow_exact_alarms_or)
        OemTips.manufacturerHint()?.let { lines += "• $it" }
        activity.findViewById<TextView>(R.id.tvSetup).text = lines.joinToString("\n")
        activity.findViewById<Button>(R.id.btnSetupNotif).visibility = if (notifOk) View.GONE else View.VISIBLE
        activity.findViewById<Button>(R.id.btnSetupBattery).visibility = if (batteryOk) View.GONE else View.VISIBLE
        activity.findViewById<Button>(R.id.btnSetupAlarm).visibility = if (alarmOk) View.GONE else View.VISIBLE
    }

}
