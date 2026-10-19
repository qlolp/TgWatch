package ru.tgwatch

import android.os.StrictMode

/**
 * Debug-only detection of accidental main-thread disk and network access.
 *
 * `penaltyLog` keeps the detection observational: a violation is reported in logcat and never
 * changes behaviour, so release builds and user devices are unaffected.
 */
internal object DebugStrictMode {
    fun enable() {
        val policy = StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .penaltyLog()
            .build()
        StrictMode.setThreadPolicy(policy)
    }
}
