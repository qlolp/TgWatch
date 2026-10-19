package ru.tgwatch

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner

/** Only fixed public IDs survive rotation; endpoint input is owned by a disposable secure dialog. */
internal class TargetSelectionUi(private val activity: Activity, saved: Bundle?,
    private val history: MainHistoryUi, private val changed: () -> Unit, color: (Int) -> Unit) {
    private companion object { const val SELECTED = "selected_target" }
    var selected: TargetId = TargetId.fromWire(saved?.getString(SELECTED).orEmpty())
        ?: TargetId.fromWire(activity.intent.getStringExtra(TargetAutomation.EXTRA_TARGET_ID).orEmpty())
        ?: TargetId.TELEGRAM
        private set
    private val spinner = activity.findViewById<Spinner>(R.id.targetSelector)
    private val status = TargetStatusUi(activity, color)
    private var dialog: AlertDialog? = null

    init {
        spinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
            TargetId.entries.map { TargetNames.label(activity, it) })
        spinner.setSelection(selected.ordinal)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, itemId: Long) {
                val id = TargetId.entries[position]
                if (selected != id) { selected = id; history.selectTarget(id); changed() }
            }
        }
        activity.findViewById<Button>(R.id.btnTargetSettings).setOnClickListener { settings() }
        activity.findViewById<Button>(R.id.btnToggle).setOnClickListener {
            val busy = TargetSettingsExecutor.busy(selected)
            if (busy || Prefs.isEnabled(activity) && TargetPreferences.storedDefinition(activity, selected).enabled)
                TargetActions.stop(activity, selected) else TargetActions.start(activity, selected)
            changed()
        }
        activity.findViewById<Button>(R.id.btnCheck).setOnClickListener { TargetActions.check(activity, selected) }
        activity.findViewById<Button>(R.id.btnResumeAlerts).setOnClickListener {
            val app = activity.applicationContext
            val id = selected
            TargetActions.submit(activity, id) {
                val legacy = id != TargetId.TELEGRAM || Prefs.clearAlertMute(app)
                TargetAlertPreferences.clear(app, id) && legacy
            }
        }
    }

    fun render(now: Long) {
        status.render(selected, now)
        val raw = TargetPreferences.storedDefinition(activity, selected)
        val stopping = TargetSettingsExecutor.busy(selected) || Prefs.isEnabled(activity) && raw.enabled
        activity.findViewById<Button>(R.id.btnToggle).apply {
            isEnabled = true
            text = activity.getString(if (stopping) R.string.target_stop else R.string.target_start,
                TargetNames.label(activity, selected))
        }
        activity.findViewById<Button>(R.id.btnCheck).isEnabled =
            MonitorService.running && TargetPreferences.definition(activity, selected).enabled
        activity.findViewById<Button>(R.id.btnTargetSettings).apply {
            isEnabled = !TargetSettingsExecutor.busy(selected)
            setText(if (TargetConfigTransactions.isPending(selected)) R.string.target_retry
                else R.string.target_settings)
        }
    }

    fun save(out: Bundle) { out.putString(SELECTED, selected.wire) }
    fun open(intent: Intent) {
        (TargetId.fromWire(intent.getStringExtra(TargetAutomation.EXTRA_TARGET_ID).orEmpty())
            ?: TargetId.TELEGRAM.takeIf { !intent.hasExtra(TargetAutomation.EXTRA_TARGET_ID) &&
                intent.action != Intent.ACTION_MAIN })?.let {
            spinner.setSelection(it.ordinal)
        }
    }
    fun close() { dialog?.dismiss(); dialog = null }
    private fun settings() {
        val id = selected
        if (TargetConfigTransactions.isPending(id)) {
            val app = activity.applicationContext
            TargetActions.submit(activity, id) {
                TargetConfigTransactions.retry(app, id).also { if (it) TargetActions.reconcile(app, id) }
            }
        } else { dialog?.dismiss(); dialog = TargetSettingsDialog.show(activity, id) }
    }
}
