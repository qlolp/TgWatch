package ru.tgwatch

import android.app.Activity
import android.view.View
import android.widget.AdapterView
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

internal object TargetPowerSettingsUi {
    fun add(activity: Activity, content: LinearLayout, interval: Spinner, intervals: List<Int>): CheckBox {
        val state = TargetPowerMonitor.readState(activity.applicationContext)
        val option = CheckBox(activity).apply {
            id = R.id.targetPowerSaving
            setText(R.string.target_power_saving)
            isChecked = TargetPowerSettings.enabled(activity.applicationContext)
            isSaveEnabled = false
            content.addView(this)
        }
        val description = TextView(activity).apply { id = R.id.targetPowerInterval; content.addView(this) }
        fun update() {
            val configured = intervals.getOrElse(interval.selectedItemPosition) { intervals.first() }
            val effective = TargetPowerPolicy.intervalSec(configured, option.isChecked, state)
            val reason = when {
                !option.isChecked -> R.string.target_power_configured
                state.powerSave -> R.string.target_power_system
                !state.charging && state.batteryPercent?.let { it <= LOW_BATTERY_PERCENT } == true ->
                    R.string.target_power_low_battery
                !state.screenOn -> R.string.target_power_screen_off
                else -> R.string.target_power_configured
            }
            description.text = activity.getString(R.string.target_power_interval,
                configured, effective, activity.getString(reason))
        }
        option.setOnCheckedChangeListener { _, _ -> update() }
        interval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = update()
            override fun onNothingSelected(parent: AdapterView<*>?) = update()
        }
        update()
        return option
    }
    private const val LOW_BATTERY_PERCENT = 15
}
