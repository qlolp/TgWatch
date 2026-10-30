package ru.tgwatch

import android.content.Context

internal object ObservationCoverageText {
    fun line(ctx: Context, id: TargetId, value: ObservationCoverage): String {
        val percent = value.percent?.let { formatPercent(ctx, it) } ?: ctx.getString(R.string.coverage_no_data)
        return ctx.getString(R.string.observation_coverage_line, TargetNames.label(ctx, id), percent,
            durationStr(ctx, value.unknownMs))
    }

    fun gaps(ctx: Context, rows: List<IncidentCenter.TargetCoverage>): String = rows.asSequence()
        .filter { !it.coverage.complete }
        .joinToString("\n") { line(ctx, it.id, it.coverage) }

    fun all(ctx: Context, rows: List<IncidentCenter.TargetCoverage>): String = rows.joinToString("\n") {
        line(ctx, it.id, it.coverage)
    }
}
