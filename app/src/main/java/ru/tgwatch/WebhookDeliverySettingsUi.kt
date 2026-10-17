package ru.tgwatch

import android.app.Activity
import android.app.AlertDialog
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast

/** Delivery choices live beside the credential URL; none enter saved view state or backup. */
internal class WebhookDeliverySettingsUi(private val activity: Activity) {
    private val format = Button(activity)
    private val filters = Button(activity)
    private val events = WebhookEventKind.DELIVERY_EVENTS.toList()
    private val eventLabels = intArrayOf(R.string.webhook_event_unreachable, R.string.webhook_event_partial,
        R.string.webhook_event_network_lost, R.string.webhook_event_recovery, R.string.webhook_event_network_restored)

    fun attach(column: LinearLayout) {
        listOf(format, filters).forEach { button ->
            button.isSaveEnabled = false
            button.minHeight = (BUTTON_DP * activity.resources.displayMetrics.density).toInt()
            column.addView(button, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        format.id = AutomationSettingsActivity.ID_WEBHOOK_FORMAT
        filters.id = AutomationSettingsActivity.ID_WEBHOOK_FILTERS
        format.setOnClickListener { chooseFormat() }
        filters.setOnClickListener { chooseFilters() }
        refresh()
    }

    fun refresh() {
        val current = WebhookSettings.configuration(activity)
        format.text = activity.getString(R.string.webhook_format_current, current.format.name)
        filters.text = activity.getString(R.string.webhook_filters_current, current.events.size, events.size)
    }

    private fun chooseFormat() {
        val current = WebhookSettings.configuration(activity)
        val options = arrayOf(activity.getString(R.string.webhook_format_text),
            activity.getString(R.string.webhook_format_json))
        secure(AlertDialog.Builder(activity).setTitle(R.string.webhook_format_title)
            .setSingleChoiceItems(options, current.format.ordinal) { dialog, choice ->
                save(WebhookFormat.entries[choice], WebhookSettings.configuration(activity).events)
                dialog.dismiss()
            }.setNegativeButton(R.string.dialog_cancel, null).create())
    }

    private fun chooseFilters() {
        val current = WebhookSettings.configuration(activity)
        val selected = BooleanArray(events.size) { events[it] in current.events }
        secure(AlertDialog.Builder(activity).setTitle(R.string.webhook_filters_title)
            .setMultiChoiceItems(eventLabels.map(activity::getString).toTypedArray(), selected) { _, index, checked ->
                selected[index] = checked
            }.setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                save(WebhookSettings.configuration(activity).format,
                    events.filterIndexed { index, _ -> selected[index] }.toSet())
            }.create())
    }

    private fun save(format: WebhookFormat, events: Set<WebhookEventKind>) {
        if (WebhookSettings.setDeliveryResult(activity, format, events) != WebhookSettings.WriteResult.SAVED)
            Toast.makeText(activity, R.string.automation_storage_error, Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun secure(dialog: AlertDialog) {
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }

    private companion object { const val BUTTON_DP = 48 }
}
