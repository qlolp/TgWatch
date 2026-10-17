package ru.tgwatch

/** Per-target configuration fences and immutable snapshots; never performs storage or network I/O. */
class TargetRepository(initial: List<TargetDefinition> = TargetId.entries.map(TargetDefinition::default),
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = { java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }) {
    private val configurations = initial.associate { it.id to TargetConfiguration(it) }.toMutableMap()
    private var networkRevision = 0L
    val currentNetworkRevision: Long get() = synchronized(this) { networkRevision }
    private val snapshots = mutableMapOf<TargetId, TargetSnapshot>()
    init { require(initial.size == TargetId.entries.size && configurations.size == TargetId.entries.size) }

    @Synchronized fun definitions(): List<TargetDefinition> =
        TargetId.entries.map { configurations.getValue(it).definition }
    @Synchronized fun configuration(id: TargetId): TargetConfiguration = configurations.getValue(id)
    @Synchronized fun snapshot(id: TargetId): TargetSnapshot? = snapshots[id]

    @Synchronized fun configure(definition: TargetDefinition, endpointChanged: Boolean = false): TargetConfiguration {
        val before = configurations.getValue(definition.id)
        if (!endpointChanged && before.definition == definition) return before
        val next = TargetConfiguration(definition, Math.addExact(before.generation, 1L),
            if (endpointChanged) Math.addExact(before.endpointRevision, 1L) else before.endpointRevision)
        configurations[definition.id] = next
        snapshots.remove(definition.id)
        return next
    }

    @Synchronized fun install(configuration: TargetConfiguration) {
        if (configurations[configuration.definition.id] != configuration) {
            configurations[configuration.definition.id] = configuration
            snapshots.remove(configuration.definition.id)
        }
    }

    @Synchronized fun networkChanged(revision: Long) {
        require(revision >= networkRevision)
        networkRevision = revision
    }

    @Synchronized fun publish(attempt: TargetAttempt, report: TargetReport): Boolean {
        if (!report.publishable || attempt.networkRevision != networkRevision) return false
        val current = configurations.getValue(attempt.id)
        val revision = Math.addExact(snapshots[attempt.id]?.revision ?: -1L, 1L)
        return publish(TargetSnapshot(attempt.id, report.status, report.checkedAtMs.takeIf { it > 0L } ?: wallClock(),
            report.checkedElapsedMs.takeIf { it > 0L } ?: elapsedClock(),
            current.definition.intervalSec, report.latencyMs, attempt.generation, revision, attempt.endpointRevision))
    }

    @Synchronized fun publish(snapshot: TargetSnapshot): Boolean {
        val current = configurations.getValue(snapshot.id)
        val previous = snapshots[snapshot.id]
        val accepted = current.definition.enabled && snapshot.generation == current.generation &&
            snapshot.endpointRevision == current.endpointRevision &&
            (previous == null || snapshot.revision > previous.revision)
        if (accepted) snapshots[snapshot.id] = snapshot
        return accepted
    }

    companion object { val process = TargetRepository() }
}
