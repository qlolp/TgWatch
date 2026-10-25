package ru.tgwatch

import android.app.Activity
import android.app.AlertDialog
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A snapshot of independent targets. It contains public labels only, never endpoints or health criteria. */
internal class TargetOverviewUi(private val activity: Activity, private val goals: () -> List<TargetId>,
    private val select: (TargetId) -> Unit) {
    private companion object { const val PADDING_DP = 16 }
    private var dialog: AlertDialog? = null

    fun show() {
        close()
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (PADDING_DP * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(activity).apply { setText(R.string.target_overview_note) })
        goals().forEach { id -> content.addView(row(id, now, elapsed)) }
        dialog = AlertDialog.Builder(activity).setTitle(R.string.target_overview_title)
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton(android.R.string.ok, null).create().also { it.show() }
    }

    fun close() { dialog?.dismiss(); dialog = null }

    private fun row(id: TargetId, now: Long, elapsed: Long): Button {
        val snapshot = TargetStateStore.current(activity, id)
        val state = TargetOverviewPolicy.state(TargetOverviewInput(snapshot, MonitorService.running,
            TargetPreferences.definition(activity, id).enabled,
            TargetSettingsExecutor.busy(id) || TargetConfigTransactions.isPending(id), now, elapsed))
        val name = TargetNames.label(activity, id)
        val summary = when (state) {
            TargetOverviewState.OFF -> activity.getString(R.string.target_overview_off)
            TargetOverviewState.PENDING -> activity.getString(R.string.target_settings_busy)
            TargetOverviewState.CHECKING -> activity.getString(R.string.target_checking, name)
            TargetOverviewState.STALE -> activity.getString(R.string.target_overview_stale)
            TargetOverviewState.MEASURED -> activity.getString(R.string.target_title, name,
                activity.getString(statusLabel(snapshot.status)))
        }
        val checked = if (snapshot.checkedAtMs > 0L)
            activity.getString(R.string.target_checked, agoStr(activity, snapshot.checkedAtMs, now))
        else activity.getString(R.string.target_overview_no_checks)
        return Button(activity).apply {
            text = "$name\n$summary\n$checked"
            contentDescription = text
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            isAllCaps = false
            setOnClickListener { close(); select(id) }
        }
    }

    private fun statusLabel(status: TargetStatus): Int = when (status) {
        TargetStatus.OK -> R.string.target_ok
        TargetStatus.DOWN -> R.string.target_down
        TargetStatus.PARTIAL -> R.string.target_partial
        TargetStatus.NO_NETWORK -> R.string.target_offline
        TargetStatus.UNKNOWN -> R.string.target_unknown
    }
}
