package ru.tgwatch

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean

internal class RegionalWatchUi(private val activity: Activity) {
    private val buttons = mutableMapOf<RegionalService, Button>()
    private val writing = mutableSetOf<RegionalService>()
    private val main = Handler(Looper.getMainLooper())

    fun add(content: LinearLayout, service: RegionalService) {
        buttons[service] = Button(activity).apply {
            minimumHeight = (TOUCH_DP * resources.displayMetrics.density).toInt()
            setOnClickListener { change(service) }
            content.addView(this)
        }
        render()
    }

    fun render() {
        buttons.forEach { (service, button) ->
            val request = RegionalWatchPreferences.current(activity, service)
            val result = RegionalCurrent.result(activity, service)
            val confirmed = result?.status == RegionalStatus.REGION_RESTRICTED && result.regionConfirmed
            button.isEnabled = service !in writing && (request != null || confirmed)
            button.text = if (request == null) activity.getString(R.string.regional_watch_arm) else {
                val remaining = request.request.deadlineElapsed - SystemClock.elapsedRealtime()
                activity.getString(R.string.regional_watch_cancel,
                    RegionalPresentation.time(activity, System.currentTimeMillis() + remaining.coerceAtLeast(0)))
            }
        }
    }

    private fun change(service: RegionalService) {
        val ctx = activity.applicationContext
        val generation = RegionalRepository.generation(ctx)
        val existing = RegionalWatchPreferences.current(ctx, service)
        val changed = AtomicBoolean()
        writing.add(service)
        render()
        HistorySessions.queue(ctx).exclusive { synchronized(History) {
            if (!BackupManager.hasPending() && generation == RegionalRepository.generation(ctx)) {
                if (existing != null) changed.set(RegionalWatchPreferences.cancel(ctx, service, existing))
                else RegionalCurrent.result(ctx, service)?.let { changed.set(RegionalWatchPreferences.arm(ctx, it)) }
            }
        } }.whenComplete { _, failure -> main.post {
            writing.remove(service)
            val current = generation == RegionalRepository.generation(ctx)
            val saved = changed.get() && failure == null && current
            if (saved && existing == null)
                MonitorService.requestRegionalCheck()
            if (!activity.isDestroyed && !activity.isFinishing) {
                Toast.makeText(activity, if (saved)
                    R.string.regional_watch_saved else R.string.regional_watch_changed, Toast.LENGTH_SHORT).show()
                render()
            }
        } }
    }

    private companion object { const val TOUCH_DP = 48 }
}
