package ru.tgwatch

import android.app.Activity
import android.os.Bundle

/** Pending captures survive rotation in memory, never process death or portable restore. */
class RegionalComparisonActivity : Activity() {
    private lateinit var state: RegionalComparisonUiState
    private lateinit var screen: RegionalComparisonUi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.regional_compare_title)
        @Suppress("DEPRECATION")
        val retained = lastNonConfigurationInstance as? RegionalComparisonUiState
        state = retained ?: RegionalComparisonUiState()
        screen = RegionalComparisonUi(this, state)
    }

    @Deprecated("Native Activity retention keeps only process-local measurement state")
    override fun onRetainNonConfigurationInstance(): Any = state
    override fun onResume() { super.onResume(); screen.resume() }
    override fun onPause() { screen.pause(); super.onPause() }
    override fun onDestroy() { screen.close(); super.onDestroy() }
}
