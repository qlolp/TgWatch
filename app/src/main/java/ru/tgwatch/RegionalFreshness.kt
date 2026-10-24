package ru.tgwatch

internal data class RegionalFreshnessScope(val nowElapsed: Long, val networkHandle: Long?,
    val configurationRevision: Long, val monitoring: Boolean, val profileEnabled: Boolean, val maxAgeMs: Long)

internal object RegionalFreshness {
    fun current(result: RegionalResult?, scope: RegionalFreshnessScope): Boolean {
        val identity = scope.monitoring && scope.profileEnabled && scope.networkHandle != null &&
            result?.networkHandle == scope.networkHandle && result.configurationRevision == scope.configurationRevision
        val time = result != null && result.checkedElapsed >= 0 && scope.maxAgeMs >= 0 &&
            scope.nowElapsed >= result.checkedElapsed && scope.nowElapsed - result.checkedElapsed <= scope.maxAgeMs
        return identity && time
    }
}
