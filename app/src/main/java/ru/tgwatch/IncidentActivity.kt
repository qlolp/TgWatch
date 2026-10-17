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

/** Local, derived episodes; sharing happens only from a card's explicit button. */
class IncidentActivity : Activity() {
    companion object {
        private const val DAYS = "history_days"
        private const val WEEK = 7
        private const val MINUTE_MS = 60_000L
        private const val TICK_MS = 1000L
        fun open(ctx: Context, days: Int, id: TargetId = TargetId.TELEGRAM) {
            ctx.startActivity(Intent(ctx, IncidentActivity::class.java).putExtra(DAYS, days)
                .putExtra(TargetAutomation.EXTRA_TARGET_ID, id.wire).apply {
                if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1))
    private lateinit var loader: LatestRequestLoader<HistoryDisplayKey, IncidentDisplay>
    private lateinit var adapter: IncidentAdapter
    private lateinit var empty: TextView
    private lateinit var period: Button
    private var requested: HistoryDisplayKey? = null
    private var targetId = TargetId.TELEGRAM
    private var days = WEEK
    private var resumed = false
    private val ticker = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, TICK_MS) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.update(this)
        setContentView(R.layout.activity_incidents)
        ScreenInsets.apply(this, findViewById(R.id.incidentRoot))
        days = if ((savedInstanceState?.getInt(DAYS) ?: intent.getIntExtra(DAYS, WEEK)) == 1) 1 else WEEK
        targetId = TargetId.fromWire((savedInstanceState?.getString(TargetAutomation.EXTRA_TARGET_ID)
            ?: intent.getStringExtra(TargetAutomation.EXTRA_TARGET_ID)).orEmpty()) ?: TargetId.TELEGRAM
        title = getString(R.string.incidents_title) + " · " + TargetNames.label(targetId)
        val app = applicationContext
        loader = LatestRequestLoader(worker, Executor { ui.post(it) },
            { key -> IncidentDisplay.load(app, key.days, key.targetId) }, ::show)
        adapter = IncidentAdapter(this, ::share)
        empty = findViewById(R.id.incidentEmpty)
        findViewById<ListView>(R.id.incidentList).apply { adapter = this@IncidentActivity.adapter; emptyView = empty }
        period = findViewById(R.id.incidentPeriod)
        updatePeriod()
        period.setOnClickListener {
            days = if (days == 1) WEEK else 1
            updatePeriod()
            loader.invalidate()
            requested = null
            adapter.clear()
            empty.setText(R.string.history_loading)
            refresh()
        }
        findViewById<Button>(R.id.incidentDone).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        Lang.update(this)
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
        outState.putString(TargetAutomation.EXTRA_TARGET_ID, targetId.wire)
        super.onSaveInstanceState(outState)
    }

    private fun updatePeriod() {
        period.text = TargetNames.label(targetId) + " · " +
            getString(if (days == 1) R.string.period_day else R.string.period_week)
    }

    private fun refresh() {
        if (!resumed || BackupManager.hasPending()) return
        val key = HistoryDisplayKey(TargetHistoryPresentation.version(targetId),
            System.currentTimeMillis() / MINUTE_MS, days,
            targetId, TargetPreferences.endpointRevision(this, targetId), TargetPersistence.active?.generationId)
        if (requested != key) { requested = key; loader.request(key) }
    }

    private fun show(key: HistoryDisplayKey, result: Result<IncidentDisplay>) {
        val selected = key.days == days && key.targetId == targetId
        if (!resumed || isFinishing || !selected) return
        val display = result.getOrNull()
        val currentIdentity = key.endpointRevision == TargetPreferences.endpointRevision(this, targetId) &&
            key.generation == TargetPersistence.active?.generationId
        val fresh = display != null && display.version == TargetHistoryPresentation.version(targetId) &&
            display.at / MINUTE_MS == System.currentTimeMillis() / MINUTE_MS

        if (fresh && currentIdentity && !BackupManager.hasPending()) {
            adapter.clear()
            adapter.addAll(display.cards)
            empty.setText(R.string.incidents_empty)
        } else {
            requested = null
            loader.invalidate()
            if (display == null) {
                adapter.clear()
                empty.setText(R.string.history_load_failed)
            }
        }
    }

    private fun share(text: String) {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.incidents_title))
                .putExtra(Intent.EXTRA_TEXT, text), getString(R.string.incident_share)))
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.incident_share_unavailable, Toast.LENGTH_SHORT).show()
        }
    }
}

private class IncidentAdapter(context: Context, private val share: (String) -> Unit) :
    ArrayAdapter<String>(context, 0) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = convertView ?: LayoutInflater.from(context).inflate(R.layout.incident_card, parent, false)
        val text = getItem(position).orEmpty()
        row.findViewById<TextView>(R.id.incidentText).text = text
        row.findViewById<Button>(R.id.incidentShare).setOnClickListener { share(text) }
        return row
    }
}
