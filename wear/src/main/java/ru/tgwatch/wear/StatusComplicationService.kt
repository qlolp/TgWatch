package ru.tgwatch.wear

import android.os.SystemClock
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeRange
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import java.time.Instant

class StatusComplicationService : ComplicationDataSourceService() {
    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        listener.onComplicationData(data(request.complicationType))
    }
    override fun getPreviewData(type: ComplicationType): ComplicationData? = data(type, preview = true)
    private fun data(type: ComplicationType, preview: Boolean = false): ComplicationData? {
        val snapshot = if (preview) null else WatchRuntime.snapshot
        val elapsed = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val frame = WatchPresentation.from(snapshot, elapsed, wall)
        fun text(value: String) = PlainComplicationText.Builder(value).build()
        val description = text(frame.contentDescription)
        val range = if (frame.stale || snapshot == null) TimeRange.ALWAYS else TimeRange.before(
            Instant.ofEpochMilli(
                wall + (WatchSnapshot.STALE_MS - (elapsed - snapshot.checkedElapsedMs)).coerceAtLeast(0)
            )
        )
        val tap = WatchSurfaceAction.pendingIntent(this)
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(frame.shortText), description)
                .setTitle(text("TG" )).setTapAction(tap).setValidTimeRange(range).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text(frame.longText), description)
                .setTitle(text("TgWatch")).setTapAction(tap).setValidTimeRange(range).build()
            else -> null
        }
    }
}
