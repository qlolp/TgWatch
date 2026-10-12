package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class NetworkTransitionsTest {
    @Test fun repeatedCapabilitiesOfValidatedNetworkDoNotInvalidateResult() {
        val transitions = NetworkTransitions()
        assertTrue(transitions.available(100L))
        assertTrue(transitions.capabilities(100L, validated = true, captivePortal = false))
        // Signal strength and bandwidth updates repeat the same validated state.
        repeat(5) { assertFalse(transitions.capabilities(100L, validated = true, captivePortal = false)) }
    }

    @Test fun unvalidatedCapabilitiesAfterAvailableAreNotASecondChange() {
        val transitions = NetworkTransitions()
        assertTrue(transitions.available(100L))
        assertFalse(transitions.capabilities(100L, validated = false, captivePortal = false))
        assertFalse(transitions.available(100L))
        assertTrue(transitions.capabilities(100L, validated = true, captivePortal = false))
    }

    @Test fun validationAndCaptivePortalTransitionsAreMeaningful() {
        val transitions = NetworkTransitions()
        transitions.available(100L)
        assertTrue(transitions.capabilities(100L, validated = false, captivePortal = true))
        assertFalse(transitions.capabilities(100L, validated = false, captivePortal = true))
        assertTrue(transitions.capabilities(100L, validated = true, captivePortal = false))
        assertTrue(transitions.capabilities(100L, validated = false, captivePortal = false))
    }

    @Test fun networkIdentityChangeIsMeaningfulEvenWithSameCapabilities() {
        val transitions = NetworkTransitions()
        transitions.available(100L)
        transitions.capabilities(100L, validated = true, captivePortal = false)
        assertTrue(transitions.capabilities(200L, validated = true, captivePortal = false))
        assertTrue(transitions.available(300L))
    }

    @Test fun lossResetsIdentitySoTheSameNetworkReturningCounts() {
        val transitions = NetworkTransitions()
        transitions.available(100L)
        transitions.capabilities(100L, validated = true, captivePortal = false)
        transitions.lost()
        assertTrue(transitions.available(100L))
        assertTrue(transitions.capabilities(100L, validated = true, captivePortal = false))
    }

    @Test fun capabilitiesWithoutPriorAvailableEstablishIdentity() {
        val transitions = NetworkTransitions()
        assertTrue(transitions.capabilities(100L, validated = true, captivePortal = false))
        assertFalse(transitions.capabilities(100L, validated = true, captivePortal = false))
    }
}
