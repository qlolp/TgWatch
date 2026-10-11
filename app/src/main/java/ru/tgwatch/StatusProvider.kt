package ru.tgwatch

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.SystemClock

/** Read-only status for automation (Tasker / Termux content query). */
class StatusProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        if (matcher.match(uri) != CODE_CURRENT) return null
        Lang.update(ctx)
        MonitorService.restorePersistedState(ctx)
        val s = MonitorService.state
        val now = System.currentTimeMillis()
        val cols = arrayOf(
            "running", "status", "checked_at", "since", "latency_ms", "reason",
            "uptime_percent", "expected_interval_sec", "stale", "doze_missed",
        )
        val cursor = MatrixCursor(cols)
        cursor.addRow(
            arrayOf(
                if (MonitorService.running) 1 else 0,
                s.status.name,
                s.checkedAt,
                s.since,
                s.latencyMs,
                s.reason,
                History.uptimePercent(ctx),
                s.expectedIntervalSec,
                if (s.isStale(now, Prefs.intervalSec(ctx), Prefs.keepAwake(ctx))) 1 else 0,
                DozeStats.missedChecks(ctx),
            ),
        )
        return cursor
    }

    override fun getType(uri: Uri): String? =
        if (matcher.match(uri) == CODE_CURRENT) "vnd.android.cursor.item/vnd.ru.tgwatch.status" else null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "ru.tgwatch.status"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/current")
        private const val CODE_CURRENT = 1
        private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "current", CODE_CURRENT)
        }
    }
}
