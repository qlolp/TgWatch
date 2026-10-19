package ru.tgwatch

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone

/** Frozen public presentation; only resource preparation can construct its immutable labels. */
internal class StatusShareDocument private constructor(val snapshot: StatusShareSnapshot, labels: List<String>) {
    val labels: List<String> = Collections.unmodifiableList(ArrayList(labels))
    val text: String = this.labels.joinToString("\n")
    companion object {
        private const val MINUTE_MS = 60_000
        fun prepare(ctx: Context, snapshot: StatusShareSnapshot): StatusShareDocument {
            val config = Configuration(ctx.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(snapshot.localeTag)))
            }
            val display = ctx.createConfigurationContext(config)
            return StatusShareDocument(snapshot, labels(display, snapshot))
        }
        private fun labels(ctx: android.content.Context, value: StatusShareSnapshot): List<String> {
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", Locale.ROOT).apply {
                timeZone = SimpleTimeZone(value.zoneOffsetMinutes * MINUTE_MS, "fixed")
            }
            val latency = value.latencyMs?.takeIf { value.status in setOf(TargetStatus.OK, TargetStatus.PARTIAL) }
                ?.let { "$it " + ctx.renderString(R.string.copy_status_share_document_ms) } ?: "—"
            return listOf("TgWatch · " + name(ctx, value),
                ctx.renderString(R.string.copy_status_share_document_last_result) + status(ctx, value.status),
                if (value.running) ctx.renderString(
                    R.string.copy_status_share_document_monitoring_enabled_at_capture)
                    else ctx.renderString(R.string.copy_status_share_document_monitoring_stopped),
                ctx.renderString(R.string.copy_status_share_document_captured) + format.format(Date(
                    value.capturedAtMs)),
                ctx.renderString(R.string.copy_status_share_document_checked) + if (
                    value.checkedAtMs > 0) format.format(Date(value.checkedAtMs)) else "—",
                if (value.stale) ctx.renderString(R.string.copy_status_share_document_measurement_is_stale_or)
                    else ctx.renderString(R.string.copy_status_share_document_measurement_was_fresh_at),
                ctx.renderString(R.string.copy_status_share_document_response_time) + latency,
                value.coveragePercent?.let { ctx.renderString(
                    R.string.copy_status_share_document_one_hour_coverage) + AndroidText.percent(ctx, it) }
                    ?: ctx.renderString(R.string.copy_status_share_document_no_history_data_for),
                ctx.renderString(R.string.copy_status_share_document_last_hour_gaps_are), graphLabel(ctx, value))
        }
        private fun name(ctx: android.content.Context, value: StatusShareSnapshot): String = when (value.targetId) {
            TargetId.TELEGRAM -> "Telegram"
            TargetId.MATRIX -> "Matrix"
            TargetId.WHATSAPP -> ctx.renderString(R.string.copy_status_share_document_whatsapp_web)
            TargetId.CUSTOM -> ctx.renderString(R.string.copy_status_share_document_custom_service)
        }
        private fun status(ctx: android.content.Context, status: TargetStatus): String {
            return when (status) {
                TargetStatus.OK -> ctx.renderString(R.string.copy_status_share_document_reachable)
                TargetStatus.PARTIAL -> ctx.renderString(R.string.copy_status_share_document_partially_reachable)
                TargetStatus.DOWN -> ctx.renderString(R.string.copy_status_share_document_unreachable)
                TargetStatus.NO_NETWORK -> ctx.renderString(R.string.copy_status_share_document_no_network)
                TargetStatus.UNKNOWN -> ctx.renderString(R.string.copy_status_share_document_unknown)
            }
        }
        private fun graphLabel(ctx: android.content.Context, value: StatusShareSnapshot): String = if (
            value.chart.isEmpty()) "—" else
            TargetStatus.entries.joinToString(" · ") {
                status(ctx, it) + ": " + value.chart.count { row -> row == it }
            }
    }
}
