package ru.tgwatch

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import java.lang.ref.WeakReference

/** Only fixed public IDs survive rotation; endpoint input is owned by a disposable secure dialog. */
internal class TargetSelectionUi(private val activity: Activity, saved: Bundle?,
    private val history: MainHistoryUi, private val changed: () -> Unit, color: (Int) -> Unit) {
    private companion object { const val SELECTED = "selected_target" }
    private var goals: List<TargetId> = TargetSlots.visible(activity)
    var selected: TargetId = (TargetId.fromWire(saved?.getString(SELECTED).orEmpty())
        ?: TargetId.fromWire(activity.intent.getStringExtra(TargetAutomation.EXTRA_TARGET_ID).orEmpty()))
        ?.takeIf { it in goals } ?: TargetId.TELEGRAM
        private set
    private val spinner = activity.findViewById<Spinner>(R.id.targetSelector)
    private val add = activity.findViewById<Button>(R.id.btnAddTarget)
    private val status = TargetStatusUi(activity, color)
    private val overview = TargetOverviewUi(activity, { goals }, { id ->
        spinner.setSelection(goals.indexOf(id))
        select(id)
    })
    private var dialog: AlertDialog? = null

    init {
        bind()
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, itemId: Long) {
                goals.getOrNull(position)?.let(::select)
            }
        }
        activity.findViewById<Button>(R.id.btnTargetSettings).setOnClickListener { settings() }
        activity.findViewById<Button>(R.id.btnTargetOverview).setOnClickListener { overview.show() }
        add.setOnClickListener { addCustom() }
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
        val visible = TargetSlots.visible(activity)
        if (visible != goals) {
            overview.close()
            goals = visible
            val removed = selected !in goals
            if (removed) selected = TargetId.TELEGRAM
            bind()
            if (removed) history.selectTarget(selected)
        }
        TargetSlots.free(activity).let { free ->
            add.isEnabled = free != null && !TargetSettingsExecutor.busy(free)
            add.text = if (free != null) activity.getString(R.string.target_add)
                else activity.getString(R.string.target_add_full, TargetId.custom.size)
        }
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
                intent.action != Intent.ACTION_MAIN })?.takeIf { it in goals }?.let {
            spinner.setSelection(goals.indexOf(it))
        }
    }

    private fun bind() {
        spinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
            goals.map { TargetNames.label(activity, it) })
        spinner.setSelection(goals.indexOf(selected).coerceAtLeast(0))
    }

    private fun select(id: TargetId) {
        if (selected != id) { selected = id; history.selectTarget(id); changed() }
    }

    /** The new slot is wiped and shown on the settings worker; its dialog opens once that is durable. */
    private fun addCustom() {
        val id = TargetSlots.free(activity) ?: return
        val app = activity.applicationContext
        val screen = WeakReference(this)
        val ui = Handler(Looper.getMainLooper())
        add.isEnabled = false
        val started = TargetSettingsExecutor.background(id, { TargetSlots.add(app, id) }) { added ->
            ui.post { screen.get()?.added(id, added) }
        }
        if (!started) Toast.makeText(activity, R.string.target_settings_busy, Toast.LENGTH_SHORT).show()
    }

    private fun added(id: TargetId, added: Boolean) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!added) { Toast.makeText(activity, R.string.target_remove_failed, Toast.LENGTH_SHORT).show(); return }
        goals = TargetSlots.visible(activity)
        selected = id
        bind()
        history.selectTarget(id)
        changed()
        dialog?.dismiss(); dialog = TargetSettingsDialog.show(activity, id)
    }
    fun close() { overview.close(); dialog?.dismiss(); dialog = null }
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
