package ru.tgwatch

internal enum class RegionalExplanationKind {
    PUBLIC_RESPONSE, REGION_PENDING, REGION_CONFIRMED, AUTH_REQUIRED, CHALLENGE, RATE_LIMITED,
    DNS_FAILURE, TLS_FAILURE, TIMEOUT, CONNECTION_FAILURE, NETWORK_FAILURE, HTTP_UNCLASSIFIED, UNMEASURED
}

internal enum class RegionalExplanationAction { OPEN_BROWSER, COMPARE_CONNECTIONS, VERIFY_FEATURE, CHECK_NOW }

/** Semantic facts only. Country and arbitrary response text cannot influence an explanation. */
internal data class RegionalExplanation(val kind: RegionalExplanationKind, val action: RegionalExplanationAction) {
    companion object {
        fun from(result: RegionalResult?): RegionalExplanation {
            val kind = result?.let(::kind) ?: RegionalExplanationKind.UNMEASURED
            return RegionalExplanation(kind, action(kind))
        }

        private fun kind(result: RegionalResult): RegionalExplanationKind = when (result.status) {
            RegionalStatus.WEB_REACHABLE -> RegionalExplanationKind.PUBLIC_RESPONSE
            RegionalStatus.REGION_RESTRICTED -> if (result.regionConfirmed)
                RegionalExplanationKind.REGION_CONFIRMED else RegionalExplanationKind.REGION_PENDING
            RegionalStatus.AUTH_REQUIRED -> RegionalExplanationKind.AUTH_REQUIRED
            RegionalStatus.CHALLENGE -> RegionalExplanationKind.CHALLENGE
            RegionalStatus.RATE_LIMITED -> RegionalExplanationKind.RATE_LIMITED
            RegionalStatus.NETWORK_ERROR -> failure(result.failure)
            RegionalStatus.UNKNOWN -> if (result.httpCode in HTTP_FIRST..HTTP_LAST)
                RegionalExplanationKind.HTTP_UNCLASSIFIED else RegionalExplanationKind.UNMEASURED
        }

        private fun failure(failure: RegionalFailure?): RegionalExplanationKind = when (failure) {
            RegionalFailure.DNS -> RegionalExplanationKind.DNS_FAILURE
            RegionalFailure.TLS -> RegionalExplanationKind.TLS_FAILURE
            RegionalFailure.TIMEOUT -> RegionalExplanationKind.TIMEOUT
            RegionalFailure.CONNECTION -> RegionalExplanationKind.CONNECTION_FAILURE
            RegionalFailure.UNMEASURED -> RegionalExplanationKind.UNMEASURED
            null -> RegionalExplanationKind.NETWORK_FAILURE
        }

        private fun action(kind: RegionalExplanationKind): RegionalExplanationAction = when (kind) {
            RegionalExplanationKind.PUBLIC_RESPONSE -> RegionalExplanationAction.VERIFY_FEATURE
            RegionalExplanationKind.REGION_PENDING, RegionalExplanationKind.REGION_CONFIRMED,
            RegionalExplanationKind.DNS_FAILURE, RegionalExplanationKind.TLS_FAILURE,
            RegionalExplanationKind.TIMEOUT, RegionalExplanationKind.CONNECTION_FAILURE,
            RegionalExplanationKind.NETWORK_FAILURE -> RegionalExplanationAction.COMPARE_CONNECTIONS
            RegionalExplanationKind.AUTH_REQUIRED, RegionalExplanationKind.CHALLENGE,
            RegionalExplanationKind.HTTP_UNCLASSIFIED -> RegionalExplanationAction.OPEN_BROWSER
            RegionalExplanationKind.RATE_LIMITED, RegionalExplanationKind.UNMEASURED ->
                RegionalExplanationAction.CHECK_NOW
        }

        private const val HTTP_FIRST = 100
        private const val HTTP_LAST = 599
    }
}
