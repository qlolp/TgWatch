package ru.tgwatch

import android.app.Activity
import android.app.AlertDialog
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView

internal object TargetSettingsDialog {
    private const val MAX_ENDPOINT_CHARS = 256
    private const val PADDING_DP = 16
    private const val ENDPOINT_FENCE_ADVANCE = 2L
    private const val SHORT_TELEGRAM_SEC = 15
    private const val TWO_MINUTES_SEC = 120
    private const val THREE_MINUTES_SEC = 180

    fun show(activity: Activity, id: TargetId): AlertDialog {
        val app = activity.applicationContext
        val definition = TargetPreferences.storedDefinition(app, id)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (PADDING_DP * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            isSaveEnabled = false
        }
        fun toggle(label: Int, value: Boolean) = CheckBox(activity).apply {
            setText(label); isChecked = value; isSaveEnabled = false; content.addView(this)
        }
        val enabled = toggle(R.string.target_enabled, definition.enabled && Prefs.isEnabled(app))
        val intervals = intervals(definition)
        val interval = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                intervals.map { activity.getString(R.string.target_interval, it) })
            setSelection(intervals.indexOf(definition.intervalSec)); isSaveEnabled = false
            content.addView(this)
        }
        val power = if (id == TargetId.TELEGRAM) null else
            TargetPowerSettingsUi.add(activity, content, interval, intervals)
        val outage = toggle(R.string.target_notify_outage, definition.alerts.notifyOutage)
        val recovery = toggle(R.string.target_notify_recovery, definition.alerts.notifyRecovery)
        val slow = toggle(R.string.target_notify_slow, definition.alerts.notifySlow)
        val thresholdLabel = TextView(activity).also(content::addView)
        val threshold = threshold(activity, definition.alerts.slowThresholdMs, thresholdLabel).also(content::addView)
        val endpoint = if (id == TargetId.MATRIX || id == TargetId.CUSTOM) endpoint(activity, content) else null
        val dialog = AlertDialog.Builder(activity).setTitle(TargetNames.label(id))
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton(R.string.target_cancel, null)
            .setPositiveButton(R.string.target_save) { _, _ ->
                val value = definition.copy(enabled = enabled.isChecked,
                    intervalSec = intervals[interval.selectedItemPosition],
                    alerts = TargetAlertSettings(outage.isChecked, recovery.isChecked, slow.isChecked,
                        TargetAlertSettings.MIN_SLOW_MS + threshold.progress * TargetAlertSettings.SLOW_STEP_MS))
                save(activity, value, endpoint?.text?.toString()?.takeIf { it.isNotBlank() }, power?.isChecked)
            }.apply {
                if (endpoint != null) setNeutralButton(if (id == TargetId.MATRIX)
                    R.string.target_public_default else R.string.target_clear_endpoint) { _, _ ->
                    clearEndpoint(activity, id)
                }
            }.create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener { endpoint?.text?.clear() }
        conversion(activity, id, content) { dialog.dismiss() }
        dialog.show()
        return dialog
    }

    private fun conversion(activity: Activity, id: TargetId, content: LinearLayout, close: () -> Unit) {
        val app = activity.applicationContext
        val active = Prefs.isEnabled(app) && TargetPreferences.storedDefinition(app, id).enabled
        if (!active && TargetLegacyConversion.available(app, id)) content.addView(Button(activity).apply {
            setText(R.string.target_convert_extra)
            isSaveEnabled = false
            setOnClickListener {
                val expected = TargetPreferences.fence.snapshot(id).revision
                close()
                TargetActions.submit(activity, id) {
                    TargetLegacyConversion.convert(app, id, expected).also { if (it) TargetActions.reconcile(app, id) }
                }
            }
        })
    }

    private fun save(activity: Activity, value: TargetDefinition, endpoint: String?, powerSaving: Boolean?) {
        val app = activity.applicationContext
        if (!value.enabled) TargetPreferences.stop(app, value.id)
        val expected = TargetPreferences.fence.snapshot(value.id).revision
        TargetActions.submit(activity, value.id) {
            val changed = endpoint == null || TargetSettingWrites.endpoint(app, value.id, endpoint, expected)
            val revision = if (endpoint == null) expected else Math.addExact(expected, ENDPOINT_FENCE_ADVANCE)
            val current = TargetPreferences.storedDefinition(app, value.id)
            val applied = changed && TargetSettingWrites.definition(app,
                value.copy(requiresEndpoint = current.requiresEndpoint), revision)
            val saved = applied && (powerSaving == null || TargetPowerSettings.setEnabled(app, powerSaving))
            if (saved) TargetActions.reconcile(app, value.id)
            saved
        }
    }

    private fun clearEndpoint(activity: Activity, id: TargetId) {
        val app = activity.applicationContext
        val expected = TargetPreferences.fence.snapshot(id).revision
        TargetActions.submit(activity, id) {
            TargetSettingWrites.endpoint(app, id, "", expected).also { if (it) TargetActions.reconcile(app, id) }
        }
    }

    private fun intervals(definition: TargetDefinition): List<Int> {
        val choices = if (definition.id == TargetId.TELEGRAM)
            listOf(TargetDefinition.MIN_TELEGRAM_SEC, SHORT_TELEGRAM_SEC, TargetDefinition.DEFAULT_TELEGRAM_SEC,
                TargetDefinition.MIN_TARGET_SEC, TargetDefinition.MAX_TELEGRAM_SEC)
            else listOf(TargetDefinition.MIN_TARGET_SEC, TWO_MINUTES_SEC, THREE_MINUTES_SEC,
                TargetDefinition.MAX_TARGET_SEC)
        return (choices + definition.intervalSec).distinct().sorted()
    }

    private fun endpoint(activity: Activity, content: LinearLayout): EditText {
        content.addView(TextView(activity).apply { setText(R.string.target_endpoint_help) })
        return EditText(activity).apply {
            id = R.id.targetEndpointInput
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            filters = arrayOf(InputFilter.LengthFilter(MAX_ENDPOINT_CHARS))
            setHint(R.string.target_endpoint_hint)
            isSaveEnabled = false
            isSaveFromParentEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            content.addView(this)
        }
    }

    private fun threshold(activity: Activity, value: Long, label: TextView): SeekBar = SeekBar(activity).apply {
        max = ((TargetAlertSettings.MAX_SLOW_MS - TargetAlertSettings.MIN_SLOW_MS) /
            TargetAlertSettings.SLOW_STEP_MS).toInt()
        progress = ((value - TargetAlertSettings.MIN_SLOW_MS) / TargetAlertSettings.SLOW_STEP_MS).toInt()
        isSaveEnabled = false
        fun update() {
            label.text = activity.getString(R.string.target_slow_threshold,
                TargetAlertSettings.MIN_SLOW_MS + progress * TargetAlertSettings.SLOW_STEP_MS)
            contentDescription = label.text
        }
        update()
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = update()
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }
}
