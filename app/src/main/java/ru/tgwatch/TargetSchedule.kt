package ru.tgwatch

/** One in-flight check and one coalesced follow-up per target; a single nearest wake-up. */
class TargetSchedule(definitions: List<TargetDefinition>) {
    private data class Entry(var definition: TargetDefinition, var due: Long,
        var running: Boolean = false, var urgent: Boolean = false,
        var intervalSec: Int = definition.intervalSec, var completedAt: Long? = null, var immediate: Boolean = true)
    private val entries = definitions.associate { it.id to Entry(it, 0L) }.toMutableMap()
    private var cursor = 0
    init { require(entries.size == definitions.size) }

    @Synchronized fun due(nowElapsed: Long): List<TargetId> {
        val ids = TargetId.entries.filter { entries[it]?.definition?.enabled == true }
        val start = if (ids.isEmpty()) 0 else cursor % ids.size
        val ordered = ids.drop(start) + ids.take(start)
        val due = ordered.filter { id -> entries[id]?.let {
            it.definition.enabled && !it.running && it.due <= nowElapsed
        } == true }
        due.forEach { entries.getValue(it).apply { running = true; immediate = false } }
        if (due.isNotEmpty()) cursor = (start + 1) % ids.size
        return due
    }

    @Synchronized fun request(id: TargetId, nowElapsed: Long): Boolean {
        val entry = entries[id]?.takeIf { it.definition.enabled } ?: return false
        return if (entry.running) {
            (!entry.urgent).also { entry.urgent = true }
        } else if (entry.due > nowElapsed) {
            entry.due = nowElapsed
            entry.immediate = true
            true
        } else false
    }

    @Synchronized fun finished(id: TargetId, nowElapsed: Long) {
        val entry = entries[id] ?: return
        if (entry.running) {
            entry.running = false
            entry.completedAt = nowElapsed
            entry.immediate = entry.urgent
            entry.due = if (entry.urgent) nowElapsed else deadline(nowElapsed, entry.intervalSec)
            entry.urgent = false
        }
    }

    @Synchronized fun nextWakeElapsed(): Long? =
        entries.values.filter { it.definition.enabled && !it.running }.minOfOrNull { it.due }

    @Synchronized fun setInterval(id: TargetId, intervalSec: Int, nowElapsed: Long) {
        require(intervalSec in TargetDefinition.MIN_TELEGRAM_SEC..TargetPowerPolicy.MAX_EFFECTIVE_SEC)
        val entry = entries[id] ?: return
        if (entry.intervalSec == intervalSec) return
        entry.intervalSec = intervalSec
        if (!entry.running && !entry.immediate) entry.completedAt?.let {
            entry.due = deadline(it, intervalSec).coerceAtLeast(nowElapsed)
        }
    }

    @Synchronized fun configure(definition: TargetDefinition, nowElapsed: Long) {
        val current = entries[definition.id]
        if (current == null) entries[definition.id] = Entry(definition, nowElapsed)
        else if (current.definition != definition) {
            current.definition = definition
            current.intervalSec = definition.intervalSec
            current.urgent = current.running && definition.enabled
            if (!current.running) { current.due = nowElapsed; current.immediate = true }
        }
    }

    private fun deadline(now: Long, interval: Int): Long {
        val delay = interval * MILLIS_PER_SECOND
        return if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
    }
    private companion object { const val MILLIS_PER_SECOND = 1_000L }
}
