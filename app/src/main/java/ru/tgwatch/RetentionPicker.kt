package ru.tgwatch

import android.app.Activity
import android.widget.RadioGroup

/** History retention radio group on the main history card. */
internal object RetentionPicker {
    private val ids = mapOf(
        Retention.WEEK to R.id.rbRetentionWeek,
        Retention.MONTH to R.id.rbRetentionMonth,
        Retention.QUARTER to R.id.rbRetentionQuarter,
    )

    fun bind(activity: Activity) {
        val group = activity.findViewById<RadioGroup>(R.id.rgRetention)
        group.check(ids[Prefs.historyRetention(activity)] ?: R.id.rbRetentionWeek)
        group.setOnCheckedChangeListener { _, checkedId ->
            val value = ids.entries.firstOrNull { it.value == checkedId }?.key ?: Retention.WEEK
            Prefs.setHistoryRetention(activity, value)
            (activity as? MainActivity)?.notifyServiceSettingsChanged()
        }
    }
}
