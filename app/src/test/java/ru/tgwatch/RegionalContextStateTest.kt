package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class RegionalContextStateTest {
    @Test fun aNewNetworkDoesNotCarryThePreviousCountryIntoItsFirstLookup() {
        val state = RegionalContextState()
        val old = state.token(42, 1, "DE")
        val replacement = state.token(43, 1, null)
        assertNotEquals(old, replacement)
        assertEquals(replacement, state.token(43, 1, "NL"))
        assertEquals(replacement, state.token(43, 1, "NL"))
        assertNotEquals(replacement, state.token(43, 1, "DE"))
    }

    @Test fun configurationChangesAndInvalidationAlwaysCreateANewRandomContext() {
        val state = RegionalContextState()
        val first = state.token(42, 1, "DE")
        assertTrue(first.matches(Regex("[0-9a-f]{32}")))
        assertEquals(first, state.token(42, 1, null))
        val changed = state.token(42, 2, null)
        assertNotEquals(first, changed)
        state.invalidate()
        assertNotEquals(changed, state.token(42, 2, "NL"))
        assertThrows(IllegalArgumentException::class.java) { state.token(42, 2, "ZZ") }
    }
}
