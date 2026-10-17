package ru.tgwatch

/** Worker-confined; a non-measurement queue item must never close an untouched target's history. */
internal class TargetSessionLedger {
    private val touched = mutableMapOf<Long, MutableSet<TargetId>>()
    fun recorded(session: Long, id: TargetId) { touched.getOrPut(session) { mutableSetOf() }.add(id) }
    fun end(session: Long): Set<TargetId> = touched.remove(session)?.toSet().orEmpty()
}
