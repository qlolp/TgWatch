package ru.tgwatch

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Native screen: small live progress on main, immutable comparison text prepared off main. */
internal class RegionalComparisonUi(private val activity: Activity, private val state: RegionalComparisonUiState) {
    private val ui = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1))
    private val controller = RegionalComparisonController(activity.applicationContext, state, ::render)
    private val progress = TextView(activity)
    private val body = TextView(activity).apply { setTextIsSelectable(true) }
    private val a: Button
    private val b: Button
    private val cancel: Button
    private val clear: Button
    private var resumed = false
    private val loader: LatestRequestLoader<RegionalComparisonViewKey, String>
    private val ticker = object : Runnable {
        override fun run() { controller.tick(); render(); ui.postDelayed(this, REDRAW_MS) }
    }

    init {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (PADDING_DP * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val scroll = ScrollView(activity).apply { addView(content) }
        activity.setContentView(scroll)
        ScreenInsets.apply(activity, scroll)
        content.addView(TextView(activity).apply { setText(R.string.regional_compare_help) })
        val nameA = field(content, R.string.regional_compare_name_a, "A")
        a = button(content, R.string.regional_compare_a) { controller.start(false, nameA.text.toString().trim()) }
        val nameB = field(content, R.string.regional_compare_name_b, "B")
        b = button(content, R.string.regional_compare_b) { controller.start(true, nameB.text.toString().trim()) }
        content.addView(progress)
        cancel = button(content, R.string.regional_compare_cancel) { controller.cancel() }
        content.addView(body)
        clear = button(content, R.string.regional_compare_clear) {
            AlertDialog.Builder(activity).setMessage(R.string.regional_compare_clear_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ -> controller.clear() }.show()
        }
        val textContext = activity.applicationContext.createConfigurationContext(
            Configuration(activity.resources.configuration))
        loader = LatestRequestLoader(worker, Executor { ui.post(it) },
            { key -> RegionalComparisonPresentation.render(textContext, key) },
            { _, result -> if (resumed && !activity.isDestroyed) body.text = result.getOrNull()
                ?: activity.getString(R.string.regional_compare_save_error) })
    }

    private fun field(parent: LinearLayout, title: Int, initial: String): EditText {
        parent.addView(TextView(activity).apply { setText(title) })
        return EditText(activity).apply {
            setText(initial)
            setSingleLine(true)
            isSaveEnabled = false
            filters = arrayOf(InputFilter.LengthFilter(LABEL_LIMIT))
            minimumHeight = (TOUCH_DP * resources.displayMetrics.density).toInt()
            contentDescription = activity.getString(title)
            parent.addView(this)
        }
    }

    private fun button(parent: LinearLayout, text: Int, action: () -> Unit): Button = Button(activity).apply {
        setText(text)
        minimumHeight = (TOUCH_DP * resources.displayMetrics.density).toInt()
        setOnClickListener { action() }
        parent.addView(this)
    }

    private fun render() {
        if (!resumed) return
        val pending = state.pending
        progress.text = when {
            pending != null -> activity.getString(R.string.regional_compare_progress, pending.measured, pending.total)
            state.saving -> activity.getString(R.string.regional_compare_saving)
            else -> activity.getString(state.message)
        }
        a.isEnabled = pending == null && !state.saving
        b.isEnabled = pending == null && !state.saving && state.a != null
        cancel.isEnabled = pending != null
        val history = RegionalComparisonRepository.snapshot(activity)
        clear.isEnabled = history.pairs.isNotEmpty() && !state.saving
        loader.request(RegionalComparisonViewKey(history, state.a, state.latest))
    }

    fun resume() { resumed = true; EgressCountryUi.refreshActive(activity.applicationContext); ui.post(ticker) }
    fun pause() { resumed = false; ui.removeCallbacks(ticker); loader.invalidate() }
    fun close() { controller.close(); loader.close(); worker.shutdownNow() }

    private companion object {
        const val REDRAW_MS = 1_000L
        const val TOUCH_DP = 48
        const val PADDING_DP = 16
        const val LABEL_LIMIT = 40
    }
}
