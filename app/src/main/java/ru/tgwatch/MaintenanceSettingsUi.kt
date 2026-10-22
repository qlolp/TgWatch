package ru.tgwatch

import android.app.Activity
import android.app.TimePickerDialog
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Compact maintenance-window editor for one target. */
internal object MaintenanceSettingsUi {
    private const val MINUTES_PER_HOUR = 60
    private const val DEFAULT_START_MINUTE = 2 * MINUTES_PER_HOUR
    private const val ID_RADIX = 36
    private const val LAST_DAY_BIT = 6

    fun add(activity: Activity, content: LinearLayout, id: TargetId) {
        add(activity, content, { MaintenanceWindows.load(activity, id) },
            { MaintenanceWindows.save(activity, id, it) })
    }

    fun add(activity: Activity, content: LinearLayout, load: () -> List<MaintenanceWindow>,
        save: (List<MaintenanceWindow>) -> Unit) {
        val label = TextView(activity)
        content.addView(label)
        content.addView(Button(activity).apply {
            setText(R.string.maintenance_add)
            isSaveEnabled = false
            setOnClickListener { pickWindow(activity, load, save) { refresh(label, activity, load) } }
        })
        content.addView(Button(activity).apply {
            setText(R.string.maintenance_clear)
            isSaveEnabled = false
            setOnClickListener {
                save(emptyList())
                refresh(label, activity, load)
            }
        })
        refresh(label, activity, load)
    }

    private fun refresh(label: TextView, activity: Activity, load: () -> List<MaintenanceWindow>) {
        val windows = load()
        label.text = if (windows.isEmpty()) activity.getString(R.string.maintenance_empty)
        else windows.joinToString("\n") { describe(activity, it) }
    }

    private fun describe(activity: Activity, window: MaintenanceWindow): String {
        val start = formatMinute(window.startMinuteOfDay)
        val end = formatMinute(window.endMinuteOfDay)
        return activity.getString(R.string.maintenance_line, start, end) +
            if (window.daysMask != 0) " · " + daysLabel(window.daysMask) else ""
    }

    private fun formatMinute(minute: Int): String {
        val hour = minute / MINUTES_PER_HOUR
        val rest = minute % MINUTES_PER_HOUR
        return String.format(java.util.Locale.ROOT, "%02d:%02d", hour, rest)
    }

    private fun daysLabel(mask: Int): String = (0..LAST_DAY_BIT).filter { (mask shr it) and 1 == 1 }
        .joinToString(",") { (it + 1).toString() }

    private fun pickWindow(activity: Activity, load: () -> List<MaintenanceWindow>,
        save: (List<MaintenanceWindow>) -> Unit, done: () -> Unit) {
        val startDefault = DEFAULT_START_MINUTE
        TimePickerDialog(activity, { _, hour, minute ->
            val start = hour * MINUTES_PER_HOUR + minute
            TimePickerDialog(activity, { _, endHour, endMinute ->
                val end = endHour * MINUTES_PER_HOUR + endMinute
                appendWindow(load, save, start, end)
                done()
            }, startDefault / MINUTES_PER_HOUR, startDefault % MINUTES_PER_HOUR, true).show()
        }, startDefault / MINUTES_PER_HOUR, startDefault % MINUTES_PER_HOUR, true).show()
    }

    private fun appendWindow(load: () -> List<MaintenanceWindow>, save: (List<MaintenanceWindow>) -> Unit,
        start: Int, end: Int) {
        val windows = load().toMutableList()
        if (windows.size >= MaintenanceWindow.MAX_WINDOWS) windows.removeAt(0)
        windows += MaintenanceWindow(
            id = "w" + System.currentTimeMillis().toString(ID_RADIX),
            startMinuteOfDay = start.coerceIn(0, MaintenanceWindow.MAX_MINUTE),
            endMinuteOfDay = end.coerceIn(0, MaintenanceWindow.MAX_END_MINUTE),
        )
        save(windows)
    }
}
