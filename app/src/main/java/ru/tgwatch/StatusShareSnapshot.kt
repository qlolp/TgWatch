package ru.tgwatch

import android.content.Context
import android.os.SystemClock
import android.os.UserManager
import java.util.Collections
import java.util.TimeZone

/** Typed export allowlist. Arbitrary strings, addresses, diagnostics and labels cannot enter a card. */
@Suppress("LongParameterList") // Immutable export DTO with explicit public fields only.
class StatusShareSnapshot(val targetId: TargetId, val status: TargetStatus, val capturedAtMs: Long,
    val checkedAtMs: Long, val zoneOffsetMinutes: Int, val stale: Boolean, val running: Boolean,
    val latencyMs: Long?, val coveragePercent: Double?, chart: List<TargetStatus>, val revision: Long,
    val localeTag: String) {
    val chart: List<TargetStatus> = Collections.unmodifiableList(ArrayList(chart))
    init {
        require(capturedAtMs >= 0 && checkedAtMs >= 0 && revision >= 0 && zoneOffsetMinutes in -MAX_OFFSET..MAX_OFFSET)
        require(latencyMs == null || latencyMs >= 0)
        require(coveragePercent == null || coveragePercent.isFinite() && coveragePercent in 0.0..MAX_PERCENT)
        require(chart.size <= BINS)
        require(validLocaleTag(localeTag))
    }
    companion object {
        internal fun validLocaleTag(tag: String): Boolean = tag.length in 2..MAX_LOCALE_LENGTH &&
            java.util.Locale.forLanguageTag(tag).let { it.language.isNotEmpty() && it.toLanguageTag() == tag }
        private const val MAX_LOCALE_LENGTH = 64
        private const val MAX_OFFSET = 14 * 60
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 3_600_000L
        private const val BINS = 60
        private const val MAX_PERCENT = 100.0
        /** Worker-only; freezes the exact public data used for both preview and outgoing bytes. */
        fun capture(ctx: Context, id: TargetId, nowMs: Long = System.currentTimeMillis()): StatusShareSnapshot =
            synchronized(History) {
                val state = TargetStateStore.current(ctx, id)
                val definition = TargetPreferences.definition(ctx, id)
                val running = MonitorService.running && definition.enabled
                val locked = definition.requiresEndpoint &&
                    (!ctx.getSystemService(UserManager::class.java).isUserUnlocked ||
                        Prefs.sp(ctx).getBoolean(TargetPreferences.key(id, "endpoint_review"), false))
                val fresh = WidgetSnapshot.fresh(state, running, locked, nowMs, SystemClock.elapsedRealtime())
                val from = (nowMs - HOUR_MS).coerceAtLeast(0)
                val rows = runCatching { TargetHistories.rows(ctx, id, nowMs) }.getOrNull()
                val segments = rows?.let { Timeline.segments(it, from, nowMs) }.orEmpty()
                var cursor = 0
                val bins = if (rows == null || rows.isEmpty()) emptyList() else List(BINS) { index ->
                    val center = from + (nowMs - from) * index / BINS
                    while (cursor < segments.size && segments[cursor].until <= center) cursor++
                    segments.getOrNull(cursor)?.takeIf { it.at <= center && center < it.until }
                        ?.kind?.let(TargetStatus::fromWire) ?: TargetStatus.UNKNOWN
                }
                val coverage = rows?.let { CoverageStats.from(Timeline.stats(it, from, nowMs), nowMs - from).percent }
                val offset = (TimeZone.getDefault().getOffset(nowMs) / MINUTE_MS).toInt()
                    .coerceIn(-MAX_OFFSET, MAX_OFFSET)
                StatusShareSnapshot(id, state.status, nowMs, state.checkedAtMs, offset,
                    !fresh, running, state.latencyMs, coverage, bins,
                    TargetHistoryPresentation.version(id), ctx.resources.configuration.locales[0].toLanguageTag())
            }
    }
}
