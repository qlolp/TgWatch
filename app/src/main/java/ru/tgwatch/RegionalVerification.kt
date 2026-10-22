package ru.tgwatch

enum class RegionalVerificationLayer { BROWSER, FEATURE }
enum class RegionalVerificationOutcome { REGIONAL_ERROR, SIGN_IN, CHALLENGE, NO_EXPLICIT_ERROR, WORKS, FAILED, CLEARED }

object RegionalVerificationRules {
    fun allowed(layer: RegionalVerificationLayer, outcome: RegionalVerificationOutcome): Boolean = when (layer) {
        RegionalVerificationLayer.BROWSER -> outcome in setOf(RegionalVerificationOutcome.REGIONAL_ERROR,
            RegionalVerificationOutcome.SIGN_IN, RegionalVerificationOutcome.CHALLENGE,
            RegionalVerificationOutcome.NO_EXPLICIT_ERROR, RegionalVerificationOutcome.CLEARED)
        RegionalVerificationLayer.FEATURE -> outcome in setOf(RegionalVerificationOutcome.REGIONAL_ERROR,
            RegionalVerificationOutcome.WORKS, RegionalVerificationOutcome.FAILED, RegionalVerificationOutcome.CLEARED)
    }
}

/** An explicit user report, never an automatic measurement or input to public consensus. */
data class RegionalVerification(val service: RegionalService, val layer: RegionalVerificationLayer,
    val outcome: RegionalVerificationOutcome, val at: Long, val checkedElapsed: Long,
    val connection: RegionalConnection) {
    init { require(checkedElapsed >= 0); RegionalJournalCodec.validate(entry()) }

    fun entry(): RegionalJournalEntry = RegionalJournalEntry(
        if (layer == RegionalVerificationLayer.BROWSER) RegionalEventKind.BROWSER else RegionalEventKind.FEATURE,
        at, connection.token, connection.route, connection.country, connection.countryAt, service,
        verification = outcome)

    fun current(context: RegionalConnection, now: Long, elapsed: Long): Boolean {
        val same = context.token == connection.token && context.route == connection.route &&
            context.route != RegionalRoute.NONE
        val time = now >= at && now - at <= TTL_MS && elapsed >= checkedElapsed && elapsed - checkedElapsed <= TTL_MS
        return same && time && outcome != RegionalVerificationOutcome.CLEARED
    }

    companion object { const val TTL_MS = 15 * 60_000L }
}

/** Intentionally never hydrated from historical storage. A new process starts with no current notes. */
internal class RegionalVerificationState {
    private val notes = mutableMapOf<Pair<RegionalService, RegionalVerificationLayer>, RegionalVerification>()

    @Synchronized fun record(note: RegionalVerification) {
        val key = note.service to note.layer
        if (note.outcome == RegionalVerificationOutcome.CLEARED) notes.remove(key) else notes[key] = note
    }

    @Synchronized fun current(service: RegionalService, layer: RegionalVerificationLayer,
        context: RegionalConnection, now: Long, elapsed: Long): RegionalVerification? =
        notes[service to layer]?.takeIf { it.current(context, now, elapsed) }

    @Synchronized fun reset() { notes.clear() }
}
