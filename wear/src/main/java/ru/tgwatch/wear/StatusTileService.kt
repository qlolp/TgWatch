package ru.tgwatch.wear

import android.os.SystemClock
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders as Layout
import androidx.wear.protolayout.ModifiersBuilders as Modifiers
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders as Timeline
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

class StatusTileService : TileService() {
    public override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        immediate(tile())
    public override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest):
        ListenableFuture<ResourceBuilders.Resources> =
        immediate(ResourceBuilders.Resources.Builder().setVersion("1").build())

    private fun tile(): TileBuilders.Tile {
        val snapshot = WatchRuntime.snapshot
        val elapsed = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val frame = WatchPresentation.from(snapshot, elapsed, wall)
        val timeline = Timeline.Timeline.Builder()
        // The renderer has an explicit old frame, so an ambient tile cannot keep a fresh OK forever.
        if (!frame.stale && snapshot != null) {
            val remaining = (WatchSnapshot.STALE_MS - (elapsed - snapshot.checkedElapsedMs)).coerceAtLeast(0)
            val expires = wall + remaining
            timeline.addTimelineEntry(Timeline.TimelineEntry.Builder().setLayout(layout(frame))
                .setValidity(Timeline.TimeInterval.Builder().setStartMillis(0).setEndMillis(expires).build()).build())
            val old = WatchPresentation.from(snapshot.copy(checkedElapsedMs = 0), elapsed, wall)
            timeline.addTimelineEntry(Timeline.TimelineEntry.Builder().setLayout(layout(old))
                .setValidity(
                    Timeline.TimeInterval.Builder().setStartMillis(expires).setEndMillis(Long.MAX_VALUE).build()
                ).build())
        } else timeline.addTimelineEntry(Timeline.TimelineEntry.Builder().setLayout(layout(frame)).build())
        return TileBuilders.Tile.Builder().setResourcesVersion("1").setTileTimeline(timeline.build())
            .setFreshnessIntervalMillis(TILE_FRESHNESS_MS).build()
    }
    private fun layout(frame: WatchPresentation): Layout.Layout {
        val activity = ActionBuilders.AndroidActivity.Builder().setPackageName(packageName)
            .setClassName(WatchActivity::class.java.name)
        WatchSurfaceAction.ticket?.let {
            activity.addKeyToExtraMapping(WatchSurfaceAction.EXTRA,
                ActionBuilders.AndroidStringExtra.Builder().setValue(it).build())
        }
        val modifiers = Modifiers.Modifiers.Builder()
            .setSemantics(Modifiers.Semantics.Builder().setContentDescription(frame.contentDescription).build())
            .setClickable(Modifiers.Clickable.Builder().setId("manual-check")
                .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build())
                .setMinimumClickableWidth(DimensionBuilders.dp(CLICK_MIN_DP))
                .setMinimumClickableHeight(DimensionBuilders.dp(CLICK_MIN_DP)).build())
            .setBackground(Modifiers.Background.Builder().setColor(ColorBuilders.argb(0xff111319.toInt())).build())
            .build()
        fun text(value: String, size: Float) = Layout.Text.Builder().setText(value).setMaxLines(2)
            .setMultilineAlignment(Layout.TEXT_ALIGN_CENTER).setFontStyle(Layout.FontStyle.Builder()
                .setSize(DimensionBuilders.sp(size)).setColor(ColorBuilders.argb(0xffeeeeee.toInt())).build()).build()
        val column = Layout.Column.Builder().setHorizontalAlignment(Layout.HORIZONTAL_ALIGN_CENTER)
            .addContent(text("TgWatch", TITLE_TEXT_SP)).addContent(text(frame.longText, TITLE_TEXT_SP))
            .addContent(text(getString(R.string.measured_on_watch), FOOTER_TEXT_SP))
            .addContent(text(getString(R.string.check_now), ACTION_TEXT_SP)).build()
        return Layout.Layout.Builder().setRoot(Layout.Box.Builder().setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand()).setHorizontalAlignment(Layout.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(Layout.VERTICAL_ALIGN_CENTER).setModifiers(modifiers).addContent(column).build())
            .build()
    }
    private fun <T> immediate(value: T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { it.set(value); "watch-cache" }

    private companion object {
        const val TILE_FRESHNESS_MS = 60_000L
        const val CLICK_MIN_DP = 48f
        const val TITLE_TEXT_SP = 18f
        const val FOOTER_TEXT_SP = 12f
        const val ACTION_TEXT_SP = 16f
    }
}
