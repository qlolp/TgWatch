package ru.tgwatch

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Lifecycle-bound “what changed” card; refreshes on the same minute cadence as history. */
internal class MainChangeSummaryUi(private val activity: Activity, share: (String) -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1))
    private val card = activity.findViewById<LinearLayout>(R.id.changeSummaryCard)
    private val summary = activity.findViewById<TextView>(R.id.tvChangeSummary)
    private val shareButton = activity.findViewById<Button>(R.id.btnShareChangeSummary)
    private val app = activity.applicationContext
    private var requested: ChangeSummaryDisplayKey? = null
    private var resumed = false
    private val loader = LatestRequestLoader(worker, Executor { ui.post(it) },
        { key: ChangeSummaryDisplayKey -> ChangeSummaryLoader.load(app, key) }, ::show)

    init {
        shareButton.isEnabled = false
        shareButton.setOnClickListener {
            val text = summary.text?.toString().orEmpty()
            if (text.isNotBlank()) share(text)
        }
    }

    fun resume() { resumed = true; requested = null }
    fun pause() { resumed = false; invalidate() }
    fun close() { loader.close(); worker.shutdownNow() }

    fun invalidate() {
        requested = null
        loader.invalidate()
        shareButton.isEnabled = false
    }

    fun refresh(now: Long) {
        if (!resumed) return
        if (BackupManager.hasPending()) return
        val key = ChangeSummaryLoader.key(app, now)
        if (requested != key) {
            requested = key
            loader.request(key)
        }
    }

    private fun show(key: ChangeSummaryDisplayKey, result: Result<ChangeSummaryDisplay>) {
        if (!resumed || activity.isFinishing || BackupManager.hasPending()) {
            if (BackupManager.hasPending()) invalidate()
            return
        }
        val value = result.getOrNull()?.takeIf { it.key == key }
        if (value == null) {
            invalidate()
            card.visibility = View.GONE
        } else {
            val text = value.text
            val visible = !text.isNullOrBlank()
            card.visibility = if (visible) View.VISIBLE else View.GONE
            if (visible) summary.text = text
            shareButton.isEnabled = visible
        }
    }
}
