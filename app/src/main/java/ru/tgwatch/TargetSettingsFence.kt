package ru.tgwatch

/** Only these short memory operations hold the monitor; disk work belongs to the ordered journal worker. */
class TargetSettingsFence {
    data class State(val revision: Long = 0L, val changing: Boolean = false)
    class Token internal constructor(val id: TargetId, val revision: Long)
    private val states = TargetId.entries.associateWith { State() }.toMutableMap()
    private val tokens = mutableMapOf<TargetId, Token>()

    @Synchronized fun snapshot(id: TargetId): State = states.getValue(id)

    @Synchronized fun begin(id: TargetId): Token? {
        val before = states.getValue(id)
        if (before.changing) return null
        val revision = Math.addExact(before.revision, 1L)
        states[id] = State(revision, true)
        return Token(id, revision).also { tokens[id] = it }
    }

    @Synchronized fun finish(token: Token): Boolean {
        val before = states.getValue(token.id)
        val current = before.changing && tokens[token.id] === token
        if (current) {
            states[token.id] = State(Math.addExact(before.revision, 1L), false)
            tokens.remove(token.id)
        }
        return current
    }

    @Synchronized fun bump(id: TargetId) {
        val before = states.getValue(id)
        states[id] = before.copy(revision = Math.addExact(before.revision, 1L))
    }
}
