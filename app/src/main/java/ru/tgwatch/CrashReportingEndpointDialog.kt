package ru.tgwatch

import android.app.Activity
import android.app.AlertDialog
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

/** Secure credential editor kept outside the multi-purpose settings activity. */
internal class CrashReportingEndpointDialog(
    private val activity: Activity,
    private val refresh: () -> Unit,
) {
    private companion object {
        const val SCREEN_PADDING_DP = 20
        const val TEXT_PADDING_DP = 8
    }

    fun show(onDismiss: () -> Unit): AlertDialog {
        val input = EditText(activity).apply {
            setHint(R.string.crash_reporting_endpoint_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            id = AutomationSettingsActivity.ID_CRASH_REPORTING_INPUT
            filters = arrayOf(InputFilter.LengthFilter(CrashReportingPolicy.MAX_ENDPOINT_LENGTH))
            setSingleLine(true)
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val frame = LinearLayout(activity).apply {
            val padding = (SCREEN_PADDING_DP * activity.resources.displayMetrics.density).toInt()
            val topPadding = (TEXT_PADDING_DP * activity.resources.displayMetrics.density).toInt()
            setPadding(padding, topPadding, padding, 0)
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.crash_reporting_set_endpoint)
            .setMessage(R.string.crash_reporting_endpoint_message).setView(frame)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setNeutralButton(R.string.dialog_remove) { _, _ ->
                if (!CrashReportingSettings.clear(activity)) storageError()
                refresh()
            }
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                when (CrashReportingSettings.setEndpoint(activity, input.text.toString())) {
                    CrashReportingSettings.WriteResult.SAVED -> Unit
                    CrashReportingSettings.WriteResult.INVALID ->
                        Toast.makeText(activity, R.string.crash_reporting_endpoint_invalid, Toast.LENGTH_LONG).show()
                    CrashReportingSettings.WriteResult.STORAGE_FAILED -> storageError()
                }
                refresh()
            }.create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
        return dialog
    }

    private fun storageError() =
        Toast.makeText(activity, R.string.automation_storage_error, Toast.LENGTH_SHORT).show()
}
