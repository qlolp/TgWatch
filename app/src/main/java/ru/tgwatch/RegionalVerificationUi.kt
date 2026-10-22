package ru.tgwatch

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Both report layers are explicitly attributed to the user, including the browser-helper result. */
internal class RegionalVerificationUi(private val activity: Activity) {
    private val labels = mutableMapOf<Pair<RegionalService, RegionalVerificationLayer>, TextView>()

    fun add(content: LinearLayout, service: RegionalService) {
        RegionalVerificationLayer.entries.forEach { layer ->
            val label = TextView(activity)
            labels[service to layer] = label
            content.addView(label)
            content.addView(Button(activity).apply {
                setText(if (layer == RegionalVerificationLayer.BROWSER) R.string.regional_report_browser
                    else R.string.regional_report_feature)
                minimumHeight = (TOUCH_DP * resources.displayMetrics.density).toInt()
                setOnClickListener { report(service, layer) }
            })
        }
        render()
    }

    fun render() {
        labels.forEach { (key, label) ->
            val (service, layer) = key
            val note = RegionalVerificationRepository.current(activity, service, layer)
            val detail = note?.let { activity.getString(R.string.regional_verification_value,
                outcome(activity, it.outcome), RegionalPresentation.time(activity, it.at)) }
                ?: activity.getString(R.string.regional_verification_absent)
            label.text = activity.getString(R.string.regional_verification_row, source(activity, layer), detail)
        }
    }

    private fun report(service: RegionalService, layer: RegionalVerificationLayer) {
        val choices = RegionalVerificationOutcome.entries.filter { RegionalVerificationRules.allowed(layer, it) }
        AlertDialog.Builder(activity).setTitle(source(activity, layer))
            .setMessage(if (layer == RegionalVerificationLayer.BROWSER) R.string.regional_report_browser_help
                else R.string.regional_report_feature_help)
            .setPositiveButton(R.string.regional_report_choose) { _, _ -> choose(service, layer, choices) }
            .setNeutralButton(if (layer == RegionalVerificationLayer.BROWSER) R.string.regional_report_help
                else R.string.regional_why_browser) { _, _ ->
                if (layer == RegionalVerificationLayer.BROWSER) help()
                else RegionalExplanationUi.open(activity, service)
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun choose(service: RegionalService, layer: RegionalVerificationLayer,
        choices: List<RegionalVerificationOutcome>) {
        AlertDialog.Builder(activity).setTitle(service.title)
            .setItems(choices.map {
                if (it == RegionalVerificationOutcome.CLEARED) activity.getString(R.string.regional_report_clear)
                else outcome(activity, it)
            }.toTypedArray()) { _, index ->
                val generation = RegionalRepository.generation(activity)
                RegionalVerificationRepository.report(activity.applicationContext, service, layer, choices[index])
                    .whenComplete { saved, failure -> Handler(Looper.getMainLooper()).post {
                        if (!activity.isDestroyed && !activity.isFinishing) {
                            val current = generation == RegionalRepository.generation(activity)
                            Toast.makeText(activity, if (saved == true && failure == null && current)
                                R.string.regional_report_saved else R.string.regional_report_failed,
                                Toast.LENGTH_SHORT).show()
                            render()
                        }
                    } }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun help() {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HELP))) }.onFailure {
            Toast.makeText(activity, R.string.regional_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TOUCH_DP = 48
        private const val HELP = "https://github.com/qlolp/TgWatch/blob/main/docs/regional-services.md"
        fun source(ctx: Context, layer: RegionalVerificationLayer): String = ctx.getString(
            if (layer == RegionalVerificationLayer.BROWSER) R.string.regional_source_browser_reported
            else R.string.regional_source_feature_reported)

        fun outcome(ctx: Context, outcome: RegionalVerificationOutcome): String = ctx.getString(when (outcome) {
            RegionalVerificationOutcome.REGIONAL_ERROR -> R.string.regional_report_region
            RegionalVerificationOutcome.SIGN_IN -> R.string.regional_report_auth
            RegionalVerificationOutcome.CHALLENGE -> R.string.regional_report_challenge
            RegionalVerificationOutcome.NO_EXPLICIT_ERROR -> R.string.regional_report_no_error
            RegionalVerificationOutcome.WORKS -> R.string.regional_report_works
            RegionalVerificationOutcome.FAILED -> R.string.regional_report_not_working
            RegionalVerificationOutcome.CLEARED -> R.string.regional_report_cleared
        })
    }
}
