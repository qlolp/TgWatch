package ru.tgwatch

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Switch

/** The existing advanced controls still configure Telegram and reflect edits made in its target dialog. */
internal object TargetLegacySettingsUi {
    fun refresh(activity: Activity, intervals: Map<Int, Int>, changing: (Boolean) -> Unit) {
        val group = activity.findViewById<RadioGroup>(R.id.rgInterval)
        val selected = intervals[Prefs.intervalSec(activity)] ?: View.NO_ID
        if (group.checkedRadioButtonId != selected) {
            changing(true)
            try { group.check(selected) } finally { changing(false) }
        }
        val profile = activity.findViewById<Button>(R.id.btnProfile)
        val label = profileLabel(activity)
        if (profile.text.toString() != label) profile.text = label
        check(activity, R.id.swNotifyOutage, Prefs.notifyFor(activity, "TG_DOWN"))
        check(activity, R.id.swNotifyRecovery, Prefs.notifyRecovery(activity))
        check(activity, R.id.swNotifySlow, Prefs.notifyFor(activity, "SLOW"))
    }

    fun profileLabel(ctx: Context): String = ctx.getString(R.string.target_telegram_profile,
        Prefs.profile(ctx)?.title ?: ctx.getString(R.string.target_custom_interval))

    private fun check(activity: Activity, id: Int, selected: Boolean) {
        val toggle = activity.findViewById<Switch>(id)
        if (toggle.isChecked != selected) toggle.isChecked = selected
    }
}
