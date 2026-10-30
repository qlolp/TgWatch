package ru.tgwatch

import android.app.Activity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch

/** Feature-specific controls keep MainActivity's established target and lifecycle wiring unchanged. */
internal object IncidentCenterMainUi {
    fun bind(activity: Activity) {
        val inflater = LayoutInflater.from(activity)
        val summary = activity.findViewById<ViewGroup>(R.id.changeSummaryCard)
        val parent = summary.parent as ViewGroup
        val details = inflater.inflate(R.layout.incident_center_main_controls, parent, false)
        parent.addView(details, parent.indexOfChild(summary) + 1)
        details.findViewById<Button>(R.id.btnIncidentCenter).setOnClickListener {
            IncidentCenterActivity.open(activity)
        }

        val advanced = activity.findViewById<ViewGroup>(R.id.advancedSettings)
        val alerts = advanced.getChildAt(0) as ViewGroup
        val grouping = inflater.inflate(R.layout.group_incident_switch, alerts, false)
        alerts.addView(grouping)
        val toggle = grouping.findViewById<Switch>(R.id.swGroupIncidents)
        toggle.isChecked = IncidentAlertPreferences.enabled(activity)
        toggle.setOnCheckedChangeListener { _, checked ->
            IncidentAlertPreferences.setEnabled(activity, checked)
            if (MonitorService.running) MonitorService.send(activity, MonitorService.ACTION_SETTINGS)
        }
    }
}
