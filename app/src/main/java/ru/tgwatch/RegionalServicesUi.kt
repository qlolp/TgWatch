package ru.tgwatch

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.Switch
import android.widget.TextView

/** Advanced toggles and status-card binder for opt-in regional service probes. */
class RegionalServicesUi(private val activity: Activity, private val settingsChanged: () -> Unit) {
    init {
        for (service in RegionalServices.all) {
            val toggle = activity.findViewById<Switch>(switchId(service.id))
            toggle.isChecked = Prefs.regionalEnabled(activity, service.id)
            toggle.setOnCheckedChangeListener { _, checked ->
                Prefs.setRegionalEnabled(activity, service.id, checked)
                if (!checked) Prefs.clearRegionalOutcome(activity, service.id)
                settingsChanged()
                render(activity.findViewById(R.id.tvRegional), activity)
            }
        }
    }

    companion object {
        fun render(view: TextView, ctx: Context) {
            val blocked = RegionalServices.all.mapNotNull { service ->
                val outcome = Prefs.regionalOutcome(ctx, service.id) ?: return@mapNotNull null
                if (outcome != GeoBlockPolicy.Outcome.GEO_BLOCK) return@mapNotNull null
                if (!Prefs.regionalEnabled(ctx, service.id)) return@mapNotNull null
                ctx.getString(service.labelRes)
            }
            if (blocked.isEmpty()) {
                view.visibility = View.GONE
                return
            }
            view.visibility = View.VISIBLE
            view.text = ctx.getString(R.string.regional_status_blocked, blocked.joinToString(", "))
            view.contentDescription = view.text
        }

        private fun switchId(id: RegionalServices.Id): Int = when (id) {
            RegionalServices.Id.YOUTUBE -> R.id.swRegionalYoutube
            RegionalServices.Id.GEMINI -> R.id.swRegionalGemini
            RegionalServices.Id.NOTEBOOK_LM -> R.id.swRegionalNotebook
            RegionalServices.Id.CHATGPT -> R.id.swRegionalChatgpt
        }
    }
}
