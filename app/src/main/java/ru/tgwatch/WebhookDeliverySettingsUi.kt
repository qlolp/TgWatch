package ru.tgwatch

import android.app.Activity
import android.app.AlertDialog
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.TextView

/** Delivery choices live beside the credential URL; none enter saved view state or backup. */
internal class WebhookDeliverySettingsUi(private val activity: Activity, private val receiverId: () -> Int = { 0 }) {
    private val format = Button(activity)
    private val filters = Button(activity)
    private val targets = Button(activity)
    private val events = WebhookEventKind.DELIVERY_EVENTS.toList()
    private val eventLabels = intArrayOf(R.string.webhook_event_unreachable, R.string.webhook_event_partial,
        R.string.webhook_event_network_lost, R.string.webhook_event_recovery, R.string.webhook_event_network_restored)

    fun attach(column: LinearLayout) {
        listOf(format, filters, targets).forEach { button ->
            button.isSaveEnabled = false
            button.minHeight = (BUTTON_DP * activity.resources.displayMetrics.density).toInt()
            column.addView(button, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        column.addView(TextView(activity).apply {
            setText(R.string.webhook_heartbeat_help)
            isSaveEnabled = false
        })
        format.id = AutomationSettingsActivity.ID_WEBHOOK_FORMAT
        filters.id = AutomationSettingsActivity.ID_WEBHOOK_FILTERS
        targets.id = AutomationSettingsActivity.ID_WEBHOOK_TARGETS
        format.setOnClickListener { chooseFormat() }
        filters.setOnClickListener { chooseFilters() }
        targets.setOnClickListener { chooseTargets() }
        refresh()
    }

    fun refresh() {
        val current = WebhookSettings.configuration(activity, receiverId())
        format.text = activity.getString(R.string.webhook_format_current,
            activity.getString(formatLabel(current.format)))
        filters.text = activity.getString(R.string.webhook_filters_current, current.events.size, events.size)
        val goals = TargetSlots.visible(activity)
        targets.text = activity.getString(R.string.webhook_targets_current,
            goals.count { it.wire in current.services }, goals.size)
    }

    fun chooseTargets(): AlertDialog {
        val goals = TargetSlots.visible(activity)
        val current = WebhookSettings.configuration(activity, receiverId())
        val selected = BooleanArray(goals.size) { goals[it].wire in current.services }
        return secure(AlertDialog.Builder(activity).setTitle(R.string.webhook_targets_title)
            .setMultiChoiceItems(goals.map { TargetNames.label(activity, it) }.toTypedArray(),
                selected) { _, index, checked ->
                selected[index] = checked
            }.setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                if (WebhookSettings.setTargetsResult(activity, receiverId(),
                        goals.filterIndexed { index, _ -> selected[index] }.map { it.wire }.toSet()) !=
                    WebhookSettings.WriteResult.SAVED)
                    Toast.makeText(activity, R.string.automation_storage_error, Toast.LENGTH_SHORT).show()
                refresh()
            }.create())
    }

    fun chooseFormat(): AlertDialog {
        val current = WebhookSettings.configuration(activity, receiverId())
        val options = WebhookFormat.entries.map { activity.getString(formatLabel(it)) }.toTypedArray()
        return secure(AlertDialog.Builder(activity).setTitle(R.string.webhook_format_title)
            .setSingleChoiceItems(options, current.format.ordinal) { dialog, choice ->
                save(WebhookFormat.entries[choice], WebhookSettings.configuration(activity, receiverId()).events)
                dialog.dismiss()
            }.setNegativeButton(R.string.dialog_cancel, null).create())
    }

    private fun chooseFilters() {
        val current = WebhookSettings.configuration(activity, receiverId())
        val selected = BooleanArray(events.size) { events[it] in current.events }
        secure(AlertDialog.Builder(activity).setTitle(R.string.webhook_filters_title)
            .setMultiChoiceItems(eventLabels.map(activity::getString).toTypedArray(), selected) { _, index, checked ->
                selected[index] = checked
            }.setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                save(WebhookSettings.configuration(activity, receiverId()).format,
                    events.filterIndexed { index, _ -> selected[index] }.toSet())
            }.create())
    }

    private fun save(format: WebhookFormat, events: Set<WebhookEventKind>) {
        if (WebhookSettings.setDeliveryResult(activity, receiverId(), format, events) !=
            WebhookSettings.WriteResult.SAVED)
            Toast.makeText(activity, R.string.automation_storage_error, Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun secure(dialog: AlertDialog): AlertDialog {
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        return dialog
    }

    private fun formatLabel(format: WebhookFormat): Int = when (format) {
        WebhookFormat.TEXT -> R.string.webhook_format_text
        WebhookFormat.JSON -> R.string.webhook_format_json
        WebhookFormat.JSON_V2 -> R.string.webhook_format_json_v2
    }

    private companion object { const val BUTTON_DP = 48 }
}
