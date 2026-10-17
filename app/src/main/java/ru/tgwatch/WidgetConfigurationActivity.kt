package ru.tgwatch

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference

/** Explicit user choice changes only one widget; it never enables a monitor. */
class WidgetConfigurationActivity : Activity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var selector: Spinner
    private var initialToken: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.update(this)
        setResult(RESULT_CANCELED)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId !in WidgetUpdates.ids(this)) { finish(); return }
        initialToken = WidgetPreferences.token(this, widgetId)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (PADDING_DP * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply { setText(R.string.widget_configure) })
        selector = Spinner(this).apply {
            adapter = ArrayAdapter(this@WidgetConfigurationActivity, android.R.layout.simple_spinner_dropdown_item,
                TargetId.entries.map(TargetNames::label))
            setSelection(savedInstanceState?.getInt(SELECTION) ?: WidgetPreferences.target(context, widgetId).ordinal)
            minimumHeight = (TOUCH_DP * resources.displayMetrics.density).toInt()
            contentDescription = getString(R.string.widget_configure)
        }
        content.addView(selector)
        content.addView(Button(this).apply {
            setText(R.string.target_save)
            setOnClickListener { save(TargetId.entries[selector.selectedItemPosition]) }
        })
        setContentView(content)
        ScreenInsets.apply(this, content)
    }
    private fun save(target: TargetId) {
        val app = applicationContext
        val id = widgetId
        val expected = initialToken
        val screen = WeakReference(this)
        val accepted = TargetSettingsExecutor.background(target,
            { id in WidgetUpdates.ids(app) && WidgetPreferences.token(app, id) == expected &&
                WidgetPreferences.setTarget(app, id, target) }, { saved ->
                Handler(Looper.getMainLooper()).post { screen.get()?.takeUnless {
                    it.isFinishing || it.isDestroyed
                }?.let {
                    if (saved) {
                        StatusWidget.updateAll(app)
                        it.setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                        it.finish()
                    } else {
                        it.initialToken = WidgetPreferences.token(app, id)
                        Toast.makeText(it, R.string.target_settings_failed, Toast.LENGTH_LONG).show()
                    }
                } }
            })
        if (!accepted) Toast.makeText(this, R.string.target_settings_busy, Toast.LENGTH_SHORT).show()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        if (::selector.isInitialized) outState.putInt(SELECTION, selector.selectedItemPosition)
        super.onSaveInstanceState(outState)
    }
    private companion object {
        const val PADDING_DP = 16
        const val TOUCH_DP = 48
        const val SELECTION = "selection"
    }
}
