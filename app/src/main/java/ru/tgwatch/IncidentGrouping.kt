package ru.tgwatch

/** Presentation-only alert grouping. Histories and delivery integrations never consume this policy. */
internal class IncidentGrouping {
    data class Decision(
        val postIndividual: Boolean,
        val cancelIndividuals: List<TargetId> = emptyList(),
        val group: List<TargetId>? = null,
    )

    data class Update(val group: List<TargetId>? = null, val cancelGroup: Boolean = false)

    private val active = linkedMapOf<TargetId, Long>()
    private var firstAt = 0L

    fun outage(id: TargetId, at: Long, enabled: Boolean): Decision {
        if (!enabled) {
            clear(id)
            return Decision(postIndividual = true)
        }
        if (active.isNotEmpty() && (at < firstAt || at - firstAt > WINDOW_MS)) reset()
        if (active.isEmpty()) firstAt = at
        active[id] = at
        val members = active.keys.sortedBy { it.ordinal }
        if (members.size < 2) return Decision(postIndividual = true)
        return Decision(postIndividual = false, cancelIndividuals = members, group = members)
    }

    fun clear(id: TargetId): Update {
        active.remove(id) ?: return Update()
        if (active.isEmpty()) reset()
        val members = active.keys.sortedBy { it.ordinal }
        return when {
            members.size >= 2 -> Update(group = members)
            active.isNotEmpty() -> Update(cancelGroup = true)
            else -> Update(cancelGroup = true)
        }
    }

    fun setEnabled(enabled: Boolean): Update {
        if (enabled) return Update()
        val hadGroup = active.size >= 2
        reset()
        return Update(cancelGroup = hadGroup)
    }

    fun reset() {
        active.clear()
        firstAt = 0L
    }

    companion object { const val WINDOW_MS = 2 * 60_000L }
}
