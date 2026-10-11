package ru.tgwatch

import android.app.Activity
import android.app.AlertDialog
import android.text.InputFilter
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast

/** Advanced-settings controls for informational probes; they change diagnostics text only. */
class ExtraProbesUi(private val activity: Activity, private val settingsChanged: () -> Unit) {
    private val custom: Switch = activity.findViewById(R.id.swExtraCustom)
    private val customUrl: Button = activity.findViewById(R.id.btnExtraCustomUrl)
    private var updating = false

    init {
        toggle(R.id.swExtraMatrix, Prefs.extraMatrix(activity)) { Prefs.setExtraMatrix(activity, it) }
        toggle(R.id.swExtraWhatsapp, Prefs.extraWhatsapp(activity)) { Prefs.setExtraWhatsapp(activity, it) }
        update()
        custom.setOnCheckedChangeListener { _, checked ->
            if (!updating) {
                if (checked && Prefs.extraCustomUrl(activity).isEmpty()) {
                    update()
                    edit(enableAfterSave = true)
                } else {
                    Prefs.setExtraCustom(activity, checked)
                    settingsChanged()
                }
            }
        }
        customUrl.setOnClickListener { edit(enableAfterSave = false) }
    }

    private fun toggle(id: Int, value: Boolean, store: (Boolean) -> Unit) {
        val toggle = activity.findViewById<Switch>(id)
        toggle.isChecked = value
        toggle.setOnCheckedChangeListener { _, checked ->
            store(checked)
            settingsChanged()
        }
    }

    private fun update() {
        val url = Prefs.extraCustomUrl(activity)
        updating = true
        custom.isChecked = Prefs.extraCustom(activity) && url.isNotEmpty()
        updating = false
        customUrl.text = if (url.isEmpty()) activity.getString(R.string.extra_probe_custom_url_unset)
            else activity.getString(R.string.extra_probe_custom_url_set, HttpsUrlPolicy.hostLabel(url))
    }

    private fun edit(enableAfterSave: Boolean) {
        val input = EditText(activity).apply {
            setText(Prefs.extraCustomUrl(activity))
            setHint(R.string.extra_probe_custom_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(ExtraProbes.MAX_CUSTOM_URL_LENGTH))
            setSingleLine(true)
        }
        val padding = (DIALOG_PADDING_DP * activity.resources.displayMetrics.density).toInt()
        val frame = LinearLayout(activity).apply {
            setPadding(padding, 0, padding, 0)
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(activity).setTitle(R.string.extra_probe_custom)
            .setMessage(R.string.extra_probe_custom_url_message).setView(frame)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setNeutralButton(R.string.dialog_remove) { _, _ ->
                Prefs.setExtraCustomUrl(activity, "")
                update()
                settingsChanged()
            }
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                if (Prefs.setExtraCustomUrl(activity, input.text.toString())) {
                    val saved = Prefs.extraCustomUrl(activity).isNotEmpty()
                    if (enableAfterSave && saved) Prefs.setExtraCustom(activity, true)
                    settingsChanged()
                } else {
                    Toast.makeText(activity, R.string.extra_probe_custom_url_invalid, Toast.LENGTH_LONG).show()
                }
                update()
            }.show()
    }

    private companion object {
        const val DIALOG_PADDING_DP = 20
    }
}
