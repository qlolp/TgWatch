package ru.tgwatch

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class RegionalHistoryActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var body: TextView
    private lateinit var count: TextView
    private lateinit var more: Button
    private var shown = PAGE_ROWS
    private var previous: RegionalJournalSnapshot? = null
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1))
    private lateinit var loader: LatestRequestLoader<Pair<RegionalJournalSnapshot, Int>, String>
    private val ticker = object : Runnable {
        override fun run() { render(); ui.postDelayed(this, REDRAW_MS) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.regional_history_title)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (PADDING_DP * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        ScreenInsets.apply(this, scroll)
        content.addView(TextView(this).apply { setText(R.string.regional_history_help) })
        content.addView(TextView(this).apply { setText(R.string.regional_history_sources) })
        count = TextView(this).also(content::addView)
        body = TextView(this).apply { setTextIsSelectable(true) }.also(content::addView)
        more = button(content, R.string.regional_history_more) {
            shown += PAGE_ROWS; previous = null; render()
        }
        val textContext = applicationContext.createConfigurationContext(Configuration(resources.configuration))
        loader = LatestRequestLoader(worker, Executor { ui.post(it) }, { key ->
            val state = key.first
            val clockChanged = state.rows.zipWithNext().any { (first, next) -> next.at < first.at }
            (if (clockChanged) textContext.getString(R.string.regional_history_clock) + "\n\n" else "") +
                state.rows.takeLast(key.second).asReversed().joinToString("\n\n") {
                    RegionalPresentation.event(textContext, it)
                }
        }, { _, result ->
            if (!isDestroyed && !isFinishing) body.text = result.getOrNull()
                ?: getString(R.string.regional_history_error)
        })
        button(content, R.string.regional_history_clear) {
            AlertDialog.Builder(this).setMessage(R.string.regional_history_clear_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    RegionalRepository.clear(applicationContext).whenComplete { _, _ -> ui.post {
                        if (!isDestroyed && !isFinishing) { previous = null; render() }
                    } }
                }.show()
        }
    }

    private fun button(parent: LinearLayout, text: Int, action: () -> Unit): Button = Button(this).apply {
        setText(text)
        minimumHeight = (TOUCH_DP * resources.displayMetrics.density).toInt()
        setOnClickListener { action() }
        parent.addView(this)
    }

    private fun render() {
        val state = RegionalRepository.snapshot(this)
        if (previous == state) return
        previous = state
        count.text = when {
            state.error != null -> getString(R.string.regional_history_error)
            state.loading -> getString(R.string.regional_history_loading)
            else -> resources.getQuantityString(R.plurals.regional_history_count, state.rows.size, state.rows.size)
        }
        loader.request(state to shown)
        more.visibility = if (state.rows.size > shown) android.view.View.VISIBLE else android.view.View.GONE
    }

    override fun onResume() { super.onResume(); previous = null; ui.post(ticker) }
    override fun onPause() { ui.removeCallbacks(ticker); loader.invalidate(); super.onPause() }
    override fun onDestroy() { loader.close(); worker.shutdownNow(); super.onDestroy() }

    private companion object {
        const val PAGE_ROWS = 50
        const val REDRAW_MS = 1_000L
        const val TOUCH_DP = 48
        const val PADDING_DP = 16
    }
}
