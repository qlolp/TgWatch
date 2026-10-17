package ru.tgwatch

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Lifecycle-bound history presentation; a ticker only submits changed windows. */
internal class MainHistoryUi(private val activity: Activity, share: (String) -> Unit) {
    private companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR_MINUTES = 60
    }

    private val ui = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1))
    private val summary = activity.findViewById<TextView>(R.id.tvDayStats)
    private val uptime = activity.findViewById<TextView>(R.id.tvUptime)
    private val coverage = activity.findViewById<TextView>(R.id.tvCoverage)
    private val health = MonitorHealthView(activity)
    private val chart = activity.findViewById<ChartView>(R.id.chart)
    private val shareButton = activity.findViewById<Button>(R.id.btnShareStats)
    private val app = activity.applicationContext
    private var requested: HistoryDisplayKey? = null
    private var resumed = false
    private var days = 1
    private var targetId = TargetId.TELEGRAM
    private val loader = LatestRequestLoader(worker, Executor { ui.post(it) },
        { key: HistoryDisplayKey -> HistoryDisplay.load(app, key.days, key.targetId) }, ::show)

    init {
        shareButton.isEnabled = false
        shareButton.setOnClickListener { share(summary.text.toString()) }
    }

    fun resume() { resumed = true; requested = null }
    fun pause() { resumed = false; invalidate() }
    fun close() { loader.close(); worker.shutdownNow() }

    fun selectPeriod(selected: Int) {
        days = selected
        invalidate()
        summary.setText(R.string.history_loading)
        coverage.setText(R.string.history_loading)
        uptime.text = "—"
    }

    fun selectTarget(id: TargetId) {
        targetId = id
        selectPeriod(days)
        chart.setData(ChartView.prepare(emptyList(), System.currentTimeMillis(), HOUR_MINUTES))
    }

    fun invalidate() {
        requested = null
        loader.invalidate()
        shareButton.isEnabled = false
    }

    fun refresh(selected: Int, now: Long) {
        if (!resumed) return
        health.refresh(now)
        if (BackupManager.hasPending()) return
        days = selected
        val key = HistoryDisplayKey(TargetHistoryPresentation.version(targetId), now / MINUTE_MS, days,
            targetId, TargetPreferences.endpointRevision(app, targetId), TargetPersistence.active?.generationId)
        if (requested != key) {
            requested = key
            loader.request(key)
        }
    }

    private fun show(key: HistoryDisplayKey, result: Result<HistoryDisplay>) {
        val selected = key.days == days && key.targetId == targetId
        if (!resumed || activity.isFinishing || !selected) return
        if (BackupManager.hasPending()) { invalidate(); return }
        val value = result.getOrNull()
        val current = key.endpointRevision == TargetPreferences.endpointRevision(app, targetId) &&
            key.generation == TargetPersistence.active?.generationId
        if (value == null || !current || !currentWindow(value)) {
            invalidate()
            if (value == null) {
                summary.setText(R.string.history_load_failed)
                coverage.setText(R.string.history_load_failed)
                uptime.text = "—"
                chart.setData(ChartView.prepare(emptyList(), System.currentTimeMillis(), HOUR_MINUTES))
            }
        } else {
            uptime.text = if (value.uptime >= 0) formatPercent(value.uptime) else "—"
            summary.text = value.summary
            val measured = value.coverage
            val percent = measured.percent?.let(::formatPercent) ?: activity.getString(R.string.coverage_no_data)
            coverage.text = activity.getString(R.string.coverage_summary, percent,
                    durationStr(measured.assessedMs), durationStr(measured.windowMs),
                    durationStr(measured.offlineMs), durationStr(measured.unknownMs))
            chart.setData(value.chart)
            chart.contentDescription = TargetNames.label(targetId) + ": " + chart.contentDescription
            activity.findViewById<TextView>(R.id.tvChartTitle).setText(
                if (key.days == 1) R.string.chart_last_hour else R.string.chart_last_week)
            shareButton.isEnabled = true
        }
    }

    private fun currentWindow(value: HistoryDisplay): Boolean =
        value.version == TargetHistoryPresentation.version(targetId) &&
            value.at / MINUTE_MS == System.currentTimeMillis() / MINUTE_MS
}
