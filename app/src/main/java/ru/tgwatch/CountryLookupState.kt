package ru.tgwatch

/** Tickets and persistence share one monitor, so an older completion cannot win a newer refresh. */
class CountryLookupState {
    data class Ticket(val revision: Long, val networkHandle: Long, val requestedAt: Long)
    private var revision = 0L
    private var latest: Ticket? = null
    private var pending = false

    @Synchronized fun begin(networkHandle: Long, now: Long): Ticket =
        Ticket(++revision, networkHandle, now).also { latest = it; pending = true }

    @Synchronized fun checking(networkHandle: Long): Boolean = pending && latest?.networkHandle == networkHandle

    @Synchronized fun invalidate() { revision++; latest = null; pending = false }

    @Synchronized fun finish(ticket: Ticket, activeNetwork: Long, iso: String?,
        persist: (EgressCountry.Snapshot) -> Unit): Boolean {
        if (ticket != latest || ticket.networkHandle != activeNetwork) return false
        pending = false
        if (iso != null && EgressCountry.isValid(iso))
            persist(EgressCountry.Snapshot(iso, ticket.requestedAt, ticket.networkHandle))
        return true
    }
}
