package ru.tgwatch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTest {
    @Test fun showsOnlyOnFreshInstallThatWasNeverShown() {
        assertTrue(Onboarding.shouldShow(seen = false, alreadyConfigured = false))
        assertFalse(Onboarding.shouldShow(seen = true, alreadyConfigured = false))
        assertFalse(Onboarding.shouldShow(seen = false, alreadyConfigured = true))
    }
}
