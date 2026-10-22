package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EgressCountryTest {
    @Test fun parseTraceReadsOnlyLocAndRejectsGarbage() {
        val body = "fl=1\nh=www.cloudflare.com\nip=203.0.113.9\nts=1\nloc=DE\nwarp=off\n"
        assertEquals("DE", EgressCountry.parseTrace(body))
        assertNull(EgressCountry.parseTrace("ip=203.0.113.9\nloc=\n"))
        assertNull(EgressCountry.parseTrace("loc=ZZZ\n"))
        assertNull(EgressCountry.parseTrace("country=US\n"))
        assertNull(EgressCountry.parseTrace(""))
        assertEquals("US", EgressCountry.parseTrace("loc=us\nextra"))
        assertEquals("US", EgressCountry.parseTrace("loc=us"))
    }

    @Test fun flagUsesRegionalIndicatorsOnlyForValidIso() {
        assertEquals("🇺🇸", EgressCountry.flag("US"))
        assertEquals("🇩🇪", EgressCountry.flag("DE"))
        assertEquals("", EgressCountry.flag("USA"))
        assertEquals("", EgressCountry.flag("U1"))
        assertEquals("", EgressCountry.flag(""))
        assertTrue(EgressCountry.isValid("FR"))
        assertFalse(EgressCountry.isValid("fr"))
    }

    @Test fun labelCombinesFlagAndIso() {
        assertEquals("🇺🇸 US", EgressCountry.label("US"))
        assertEquals("", EgressCountry.label("XX"))
        assertEquals("", EgressCountry.label("nope"))
    }

    @Test fun refreshIsRateLimitedPerNetworkIdentity() {
        val at = 1_000_000L
        val stored = EgressCountry.Snapshot("US", at, networkHandle = 7L)
        assertFalse(EgressCountry.needsRefresh(stored, at + 60_000L, networkHandle = 7L))
        assertTrue(EgressCountry.needsRefresh(stored, at + EgressCountry.MIN_INTERVAL_MS, networkHandle = 7L))
        assertTrue(EgressCountry.needsRefresh(stored, at + 1_000L, networkHandle = 8L))
        assertTrue(EgressCountry.needsRefresh(null, at, networkHandle = 1L))
        assertTrue("Clock rollback invalidates the lookup", EgressCountry.needsRefresh(stored, at - 1, 7L))
    }

    @Test fun unknownAndReservedCountryCodesAreNotCountryFlags() {
        for (code in listOf("XX", "ZZ", "AA", "T1")) {
            assertFalse(code, EgressCountry.isValid(code))
            assertNull(EgressCountry.parseTrace("loc=$code"))
        }
    }

    @Test fun neverImpliesAddressStorage() {
        // Snapshot carries only the ISO code and local bookkeeping, never an address.
        val snapshot = EgressCountry.Snapshot("JP", 5L, 9L)
        assertEquals("JP", snapshot.iso)
        assertEquals(5L, snapshot.checkedAt)
        assertEquals(9L, snapshot.networkHandle)
    }
}
