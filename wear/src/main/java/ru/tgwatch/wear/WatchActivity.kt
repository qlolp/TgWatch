package ru.tgwatch.wear

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import java.lang.ref.WeakReference
import java.text.DateFormat
import java.util.Date

class WatchActivity : Activity(), WatchRuntime.Observer {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var controller: WatchCheckController
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var check: Button
    private var resumed = false
    private var surfaceConsumed = false
    private val tick = object : Runnable {
        override fun run() { render(); main.postDelayed(this, 1_000) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceConsumed = savedInstanceState?.getBoolean("surface-consumed") ?: false
        setContentView(ru.tgwatch.wear.R.layout.activity_watch)
        status = findViewById(R.id.watch_status)
        detail = findViewById(R.id.watch_detail)
        check = findViewById(R.id.watch_check)
        val owner = WeakReference(this)
        val dispatch = main
        controller = WatchRuntime.controller { dispatch.post { owner.get()?.render() } }
        check.setOnClickListener { if (WatchRuntime.ready) controller.request(); render() }
        render()
    }
    override fun onStart() {
        super.onStart()
        WatchRuntime.observe(this)
        main.post(tick)
    }
    override fun onResume() { super.onResume(); resumed = true; render() }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        surfaceConsumed = false
        render()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("surface-consumed", surfaceConsumed)
        super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        controller.cancel()
        WatchRuntime.unobserve(this)
        main.removeCallbacksAndMessages(null)
        super.onStop()
    }
    override fun onWatchChanged() = render()
    private fun render() {
        if (!::status.isInitialized) return
        if (resumed && WatchRuntime.ready && !surfaceConsumed) {
            surfaceConsumed = true
            if (WatchSurfaceAction.valid(intent.getStringExtra(WatchSurfaceAction.EXTRA))) controller.request()
        }
        val value = WatchRuntime.snapshot
        val stale = value?.stale(SystemClock.elapsedRealtime(), WatchRuntime.boot) != false
        status.text = getString(when {
            controller.isChecking -> R.string.checking
            value == null -> R.string.no_result
            stale -> R.string.stale
            value.status == "OK" -> R.string.status_ok
            value.status == "PARTIAL" -> R.string.status_partial
            value.status == "NO_NETWORK" -> R.string.status_no_network
            else -> R.string.status_unknown
        })
        status.setTextColor(if (!stale && value.status == "OK") 0xffa8e6ba.toInt() else 0xffeeeeee.toInt())
        detail.text = buildString {
            if (value != null) {
                append(getString(R.string.last_result, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(value.checkedAtMs))))
                value.latencyMs?.let { append('\n'); append(getString(R.string.latency, it)) }
            }
            when (controller.attemptState) {
                WatchAttemptState.BUSY, WatchAttemptState.UNMEASURED -> { append('\n'); append(getString(R.string.busy)) }
                WatchAttemptState.FAILED, WatchAttemptState.TIMEOUT -> { append('\n'); append(getString(R.string.not_completed)) }
                else -> Unit
            }
        }.trim()
        check.isEnabled = WatchRuntime.ready && !controller.isChecking
    }
}
