package ru.tgwatch

import android.content.Context

internal object TargetArchive {
    fun snapshot(ctx: Context, now: Long): TargetBackupSnapshot = synchronized(History) {
        TargetPersistence.initialize(ctx)
        // Apply the existing rollback policy before reading all the journals under the same monitor.
        val telegram = History.snapshot(ctx, now)
        val public = TargetPersistence.storage().snapshot()
        val revision = TargetPreferences.endpointRevision(ctx, TargetId.TELEGRAM)
        val rows = public.observations.mapValues { (id, rows) ->
            if (id == TargetId.TELEGRAM) rows.filter { it.endpointRevision != revision } +
                telegram.map { TargetObservation(TargetPersistence.publicRow(it), revision) }
            else clip(TargetPersistence.storage().recordedRows(id,
                TargetPreferences.endpointRevision(ctx, id), now).valueOrThrow(), now)
        }
        TargetBackupSnapshot(now, BackupManager.snapshot(ctx, now).settings - TargetBackupCodec.LEGACY_ENDPOINT_KEY,
            TargetPreferences.definitions(ctx), rows, EventLog.all(ctx).toList())
    }

    private fun clip(rows: List<TargetObservation>, now: Long): List<TargetObservation> =
        rows.groupBy { it.endpointRevision }.flatMap { (revision, cohort) ->
            val epoch = cohort.maxOfOrNull { it.row.clockEpoch } ?: 0L
            val clipped = cohort.mapNotNull { value ->
                val row = value.row
                if (row.clockEpoch != epoch || row.isEpochMetadata) value
                else if (row.at >= now) null else value.copy(row = row.copy(until = minOf(row.until, now)))
            }
            if (cohort.isNotEmpty() && clipped.none { it.row.clockEpoch == epoch })
                clipped + TargetObservation(Observation(now, now, "UNKNOWN", clockEpoch = epoch), revision)
            else clipped
        }
}
