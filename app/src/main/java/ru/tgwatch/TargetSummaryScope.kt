package ru.tgwatch

import android.content.Context

/** A history wait cannot publish a summary for goals/configurations that the user has since changed. */
internal class TargetSummaryScope private constructor(val ids: List<TargetId>, private val force: Boolean,
    private val revisions: Map<TargetId, Long>) {
    fun current(ctx: Context): Boolean = synchronized(TargetPreferences) {
        ids == selected(ctx, force) && revisions.all { (id, revision) ->
            TargetPreferences.fence.snapshot(id).revision == revision
        }
    }
    companion object {
        fun capture(ctx: Context, force: Boolean): TargetSummaryScope = synchronized(TargetPreferences) {
            val ids = selected(ctx, force)
            TargetSummaryScope(ids, force, ids.associateWith { TargetPreferences.fence.snapshot(it).revision })
        }
        private fun selected(ctx: Context, force: Boolean): List<TargetId> =
            TargetId.entries.filter { TargetPreferences.storedDefinition(ctx, it).enabled }
                .ifEmpty { if (force) listOf(TargetId.TELEGRAM) else emptyList() }
    }
}
