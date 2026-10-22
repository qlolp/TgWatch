package ru.tgwatch

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

internal object RegionalExplanationUi {
    fun show(activity: Activity, service: RegionalService) {
        val result = RegionalCurrent.result(activity, service)
        val explanation = RegionalExplanation.from(result)
        AlertDialog.Builder(activity).setTitle(service.title).setMessage(body(activity, result))
            .setPositiveButton(actionText(explanation.action)) { _, _ -> act(activity, service, explanation.action) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    fun body(ctx: Context, result: RegionalResult?): String {
        val detail = ctx.getString(detailText(RegionalExplanation.from(result).kind))
        return if (result == null) detail else ctx.getString(R.string.regional_why_body,
            ctx.getString(R.string.regional_source_public), RegionalPresentation.time(ctx, result.checkedAt),
            RegionalPresentation.status(ctx, result.status, result.regionConfirmed),
            result.httpCode?.let { ctx.getString(R.string.regional_history_code, it) }.orEmpty(), detail)
    }

    private fun detailText(kind: RegionalExplanationKind): Int = when (kind) {
        RegionalExplanationKind.PUBLIC_RESPONSE -> R.string.regional_why_response
        RegionalExplanationKind.REGION_PENDING -> R.string.regional_why_region_pending
        RegionalExplanationKind.REGION_CONFIRMED -> R.string.regional_why_region_confirmed
        RegionalExplanationKind.AUTH_REQUIRED -> R.string.regional_why_auth
        RegionalExplanationKind.CHALLENGE -> R.string.regional_why_challenge
        RegionalExplanationKind.RATE_LIMITED -> R.string.regional_why_rate
        RegionalExplanationKind.DNS_FAILURE -> R.string.regional_why_dns
        RegionalExplanationKind.TLS_FAILURE -> R.string.regional_why_tls
        RegionalExplanationKind.TIMEOUT -> R.string.regional_why_timeout
        RegionalExplanationKind.CONNECTION_FAILURE -> R.string.regional_why_connection
        RegionalExplanationKind.NETWORK_FAILURE -> R.string.regional_why_network
        RegionalExplanationKind.HTTP_UNCLASSIFIED -> R.string.regional_why_http
        RegionalExplanationKind.UNMEASURED -> R.string.regional_why_unmeasured
    }

    private fun actionText(action: RegionalExplanationAction): Int = when (action) {
        RegionalExplanationAction.OPEN_BROWSER -> R.string.regional_why_browser
        RegionalExplanationAction.COMPARE_CONNECTIONS -> R.string.regional_compare_title
        RegionalExplanationAction.VERIFY_FEATURE -> R.string.regional_why_feature
        RegionalExplanationAction.CHECK_NOW -> R.string.regional_check
    }

    private fun act(activity: Activity, service: RegionalService, action: RegionalExplanationAction) {
        when (action) {
            RegionalExplanationAction.OPEN_BROWSER -> open(activity, service)
            RegionalExplanationAction.COMPARE_CONNECTIONS ->
                activity.startActivity(Intent(activity, RegionalComparisonActivity::class.java))
            RegionalExplanationAction.VERIFY_FEATURE -> feature(activity, service)
            RegionalExplanationAction.CHECK_NOW -> if (!MonitorService.requestRegionalCheck())
                Toast.makeText(activity, R.string.regional_enable_first, Toast.LENGTH_SHORT).show()
        }
    }

    fun open(activity: Activity, service: RegionalService) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(service.address))) }
            .onFailure { Toast.makeText(activity, R.string.regional_open_failed, Toast.LENGTH_SHORT).show() }
    }

    private fun feature(activity: Activity, service: RegionalService) {
        val instruction = when (service) {
            RegionalService.YOUTUBE -> R.string.regional_feature_youtube
            RegionalService.GEMINI -> R.string.regional_feature_gemini
            RegionalService.NOTEBOOK -> R.string.regional_feature_notebook
            RegionalService.ANTIGRAVITY -> R.string.regional_feature_antigravity
            RegionalService.DOTS -> R.string.regional_feature_dots
        }
        AlertDialog.Builder(activity).setTitle(service.title).setMessage(instruction)
            .setPositiveButton(R.string.regional_why_browser) { _, _ -> open(activity, service) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
}
