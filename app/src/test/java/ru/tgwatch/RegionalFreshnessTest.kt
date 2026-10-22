package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class RegionalFreshnessTest {
    private val result = RegionalResult(RegionalService.GEMINI, RegionalStatus.WEB_REACHABLE,
        200, 100, 7, 1, 2, 200)
    private val scope = RegionalFreshnessScope(200, 7, 2, true, true, 100)

    @Test fun onlyLiveSameNetworkConfigurationAndEnabledProfileAreCurrent() {
        assertTrue(RegionalFreshness.current(result, scope))
        listOf(scope.copy(networkHandle = null), scope.copy(networkHandle = 8),
            scope.copy(configurationRevision = 3), scope.copy(monitoring = false),
            scope.copy(profileEnabled = false), scope.copy(nowElapsed = 201),
            scope.copy(nowElapsed = 99), scope.copy(maxAgeMs = -1)).forEach {
            assertFalse(RegionalFreshness.current(result, it))
        }
        assertFalse(RegionalFreshness.current(null, scope))
        assertFalse(RegionalFreshness.current(result.copy(checkedElapsed = -1), scope))
    }
}
