package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class CountryLookupStateTest {
    @Test fun oldNetworkCompletionCannotPublish() {
        val state = CountryLookupState(); val saved = mutableListOf<EgressCountry.Snapshot>()
        val ticket = state.begin(1,100)
        assertFalse(state.finish(ticket,2,"US") { saved.add(it) })
        assertTrue(saved.isEmpty())
    }
    @Test fun olderForcedRefreshCannotOverwriteNewerResultOnSameNetwork() {
        val state = CountryLookupState(); val saved = mutableListOf<EgressCountry.Snapshot>()
        val old = state.begin(7,100); val fresh = state.begin(7,200)
        assertTrue(state.finish(fresh,7,"DE") { saved.add(it) })
        assertFalse(state.finish(old,7,"US") { saved.add(it) })
        assertEquals(listOf(EgressCountry.Snapshot("DE",200,7)),saved)
    }
    @Test fun failedRefreshCannotPublishAConfidentCountry() {
        val state = CountryLookupState(); val saved = mutableListOf<EgressCountry.Snapshot>()
        val ticket = state.begin(7,100)
        assertTrue(state.finish(ticket,7,null) { saved.add(it) })
        assertTrue(saved.isEmpty())
    }
}
