package ru.tgwatch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class LatencyActivity : Activity() {
    private data class Key(val id: TargetId, val window: LatencyWindow, val series: Int,
        val endpoint: Long, val generation: String?, val version: Long)
    private val ui = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1))
    private lateinit var loader: LatestRequestLoader<Key, LatencyScreen>
    private var target = TargetId.TELEGRAM
    private var window = LatencyWindow.HOUR
    private var series = 0
    private var resumed = false
    private var dialog: android.app.AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.update(this)
        target = TargetId.fromWire(savedInstanceState?.getString(TARGET)
            ?: intent.getStringExtra(TARGET).orEmpty()) ?: TargetId.TELEGRAM
        window = LatencyWindow.entries.getOrElse(savedInstanceState?.getInt(WINDOW) ?: 0) { LatencyWindow.HOUR }
        series = (savedInstanceState?.getInt(SERIES) ?: 0).coerceIn(0, MAX_SERIES)
        setContentView(R.layout.activity_latency)
        ScreenInsets.apply(this, findViewById(R.id.latencyRoot))
        title = getString(R.string.latency_title) + " · " + TargetNames.label(target)
        findViewById<TextView>(R.id.latencyMeaning).setText(if (target == TargetId.TELEGRAM)
            R.string.latency_tg_meaning else R.string.latency_http_meaning)
        val app = applicationContext
        loader = LatestRequestLoader(worker, Executor { ui.post(it) }, { key -> synchronized(History) {
            LatencyPresentation.prepare(app, key.id, key.series,
                LatencyDisplay.load(app, key.id, key.window).valueOrThrow(), DcLatencyRecorder.read())
        } }, ::show)
        setupSelectors()
        findViewById<Button>(R.id.latencyDone).setOnClickListener { finish() }
        findViewById<Button>(R.id.latencySettings).setOnClickListener {
            dialog = TargetSettingsDialog.show(this, target)
        }
    }
    private fun setupSelectors() {
        spinner(R.id.latencyWindow, listOf(getString(R.string.latency_hour), getString(R.string.latency_day),
            getString(R.string.latency_week)), window.ordinal) {
            window = LatencyWindow.entries[it]; refresh()
        }
        val labels = listOf(getString(R.string.latency_scalar), getString(R.string.latency_all_dc), "IPv4", "IPv6") +
            MtProtoEndpoints.all.map { checkNotNull(MtProtoEndpoints.idFor(it.name)) }
        spinner(R.id.latencyEndpoint, labels, series) { series = it; refresh() }
        findViewById<Spinner>(R.id.latencyEndpoint).visibility =
            if (target == TargetId.TELEGRAM) View.VISIBLE else View.GONE
    }
    private fun spinner(id: Int, labels: List<String>, selected: Int, changed: (Int) -> Unit) {
        findViewById<Spinner>(id).apply {
            adapter = ArrayAdapter(this@LatencyActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(selected)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, row: Long) =
                    changed(position)
            }
        }
    }
    override fun onResume() { super.onResume(); resumed = true; refresh() }
    override fun onPause() { resumed = false; loader.invalidate(); super.onPause() }
    override fun onDestroy() { dialog?.dismiss(); loader.close(); worker.shutdownNow(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(TARGET, target.wire)
        outState.putInt(WINDOW, window.ordinal)
        outState.putInt(SERIES, series)
        super.onSaveInstanceState(outState)
    }
    private fun key() = Key(target, window, series, TargetPreferences.endpointRevision(this, target),
        TargetPersistence.active?.generationId, TargetHistoryPresentation.version(target))
    private fun refresh() {
        if (!resumed) return
        loader.invalidate()
        findViewById<TextView>(R.id.latencySummary).setText(R.string.history_loading)
        findViewById<TextView>(R.id.latencyDcSummary).text = ""
        findViewById<LatencyChartView>(R.id.latencyChart).show(emptyList(), 0, 1)
        if (!BackupManager.hasPending()) loader.request(key())
    }
    private fun show(request: Key, result: Result<LatencyScreen>) {
        if (!resumed || isFinishing) return
        if (request != key() || BackupManager.hasPending()) { refresh(); return }
        val data = result.getOrNull()
        if (data == null) {
            findViewById<TextView>(R.id.latencySummary).setText(R.string.history_load_failed)
        } else {
            findViewById<TextView>(R.id.latencySummary).text = data.summary
            findViewById<TextView>(R.id.latencyDcSummary).text = data.dcSummary
            findViewById<LatencyChartView>(R.id.latencyChart).show(data.points, data.scalar.fromMs, data.scalar.untilMs)
        }
    }
    companion object {
        private const val TARGET = "target_id"
        private const val WINDOW = "window"
        private const val SERIES = "series"
        private const val MAX_SERIES = 13
        fun open(ctx: Context, id: TargetId) {
            ctx.startActivity(Intent(ctx, LatencyActivity::class.java).putExtra(TARGET, id.wire).apply {
                if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
