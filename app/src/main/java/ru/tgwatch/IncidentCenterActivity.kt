package ru.tgwatch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Cross-target local evidence and safe drill-down; temporal proximity is not presented as causation. */
class IncidentCenterActivity : Activity() {
    companion object {
        private const val DAYS = "history_days"
        private const val DAY = 1
        private const val WEEK = 7
        private const val MINUTE_MS = 60_000L
        private const val TICK_MS = 1000L
        fun open(ctx: Context, days: Int = 1) {
            ctx.startActivity(Intent(ctx, IncidentCenterActivity::class.java).putExtra(DAYS, days).apply {
                if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1))
    private lateinit var loader: LatestRequestLoader<IncidentCenterKey, IncidentCenterDisplay>
    private lateinit var adapter: IncidentCenterAdapter
    private lateinit var empty: TextView
    private lateinit var coverage: TextView
    private lateinit var period: Button
    private var requested: IncidentCenterKey? = null
    private var days = 1
    private var resumed = false
    private val ticker = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, TICK_MS) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_incident_center)
        ScreenInsets.apply(this, findViewById(R.id.incidentCenterRoot))
        days = if ((savedInstanceState?.getInt(DAYS) ?: intent.getIntExtra(DAYS, DAY)) == WEEK) WEEK else DAY
        val app = applicationContext
        loader = LatestRequestLoader(worker, Executor { ui.post(it) },
            { key -> IncidentCenterLoader.load(app, key) }, ::show)
        adapter = IncidentCenterAdapter(this, ::details, ::check)
        empty = findViewById(R.id.incidentCenterEmpty)
        coverage = findViewById(R.id.incidentCenterCoverage)
        findViewById<ListView>(R.id.incidentCenterList).apply {
            adapter = this@IncidentCenterActivity.adapter
            emptyView = empty
        }
        period = findViewById(R.id.incidentCenterPeriod)
        updatePeriod()
        period.setOnClickListener {
            days = if (days == DAY) WEEK else DAY
            updatePeriod()
            loader.invalidate()
            requested = null
            adapter.clear()
            empty.setText(R.string.history_loading)
            refresh()
        }
        findViewById<Button>(R.id.incidentCenterCompare).setOnClickListener {
            startActivity(Intent(this, RegionalComparisonActivity::class.java))
        }
        findViewById<Button>(R.id.incidentCenterDone).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        requested = null
        ui.post(ticker)
    }

    override fun onPause() {
        resumed = false
        loader.invalidate()
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() { loader.close(); worker.shutdownNow(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(DAYS, days)
        super.onSaveInstanceState(outState)
    }

    private fun updatePeriod() { period.setText(if (days == 1) R.string.period_day else R.string.period_week) }

    private fun refresh() {
        if (!resumed || BackupManager.hasPending()) return
        val key = IncidentCenterLoader.key(this, days, System.currentTimeMillis())
        if (requested != key) { requested = key; loader.request(key) }
    }

    private fun show(key: IncidentCenterKey, result: Result<IncidentCenterDisplay>) {
        if (!resumed || isFinishing) return
        val display = result.getOrNull()
        val current = display != null && display.key == key &&
            key.generation == TargetPersistence.active?.generationId &&
            key.regionalGeneration == RegionalRepository.generation(this) &&
            key.ids.all { id ->
                key.versions[id] == TargetHistoryPresentation.version(id) &&
                    key.endpointRevisions[id] == TargetPreferences.endpointRevision(this, id)
            }
        val fresh = display != null && display.at / MINUTE_MS == System.currentTimeMillis() / MINUTE_MS
        if (current && fresh && !BackupManager.hasPending()) {
            adapter.clear()
            adapter.addAll(display.cards)
            coverage.text = display.coverage.ifEmpty { getString(R.string.coverage_no_data) }
            empty.setText(R.string.incident_center_empty)
        } else {
            requested = null
            loader.invalidate()
            if (display == null) {
                adapter.clear()
                coverage.setText(R.string.history_load_failed)
                empty.setText(R.string.history_load_failed)
            }
        }
    }

    private fun details(id: TargetId) = IncidentActivity.open(this, days, id)

    private fun check(id: TargetId) {
        val accepted = TargetActions.check(this, id)
        Toast.makeText(this, if (accepted) R.string.incident_center_check_queued
            else R.string.incident_center_check_unavailable, Toast.LENGTH_SHORT).show()
    }
}

private class IncidentCenterAdapter(context: Context, private val details: (TargetId) -> Unit,
    private val check: (TargetId) -> Unit) : ArrayAdapter<IncidentCenterCard>(context, 0) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = convertView ?: LayoutInflater.from(context).inflate(R.layout.incident_center_card, parent, false)
        val card = getItem(position)
        row.findViewById<TextView>(R.id.incidentCenterText).text = card?.text.orEmpty()
        val detailsButton = row.findViewById<Button>(R.id.incidentCenterDetails)
        val checkButton = row.findViewById<Button>(R.id.incidentCenterCheck)
        val visible = card?.targetId != null
        detailsButton.visibility = if (visible) View.VISIBLE else View.GONE
        checkButton.visibility = if (visible) View.VISIBLE else View.GONE
        detailsButton.setOnClickListener { card?.targetId?.let(details) }
        checkButton.setOnClickListener { card?.targetId?.let(check) }
        return row
    }
}
