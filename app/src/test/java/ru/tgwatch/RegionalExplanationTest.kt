package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class RegionalExplanationTest {
    private fun result(status: RegionalStatus, failure: RegionalFailure? = null, code: Int? = null,
        confirmed: Boolean = false) = RegionalResult(RegionalService.GEMINI, status, 100, 100, 7, 1, 2,
        code, failure, confirmed)

    @Test fun eachStatusHasAnHonestExplanationAndAction() {
        val expected = mapOf(
            RegionalStatus.WEB_REACHABLE to RegionalExplanationKind.PUBLIC_RESPONSE,
            RegionalStatus.REGION_RESTRICTED to RegionalExplanationKind.REGION_PENDING,
            RegionalStatus.AUTH_REQUIRED to RegionalExplanationKind.AUTH_REQUIRED,
            RegionalStatus.CHALLENGE to RegionalExplanationKind.CHALLENGE,
            RegionalStatus.RATE_LIMITED to RegionalExplanationKind.RATE_LIMITED,
            RegionalStatus.NETWORK_ERROR to RegionalExplanationKind.NETWORK_FAILURE,
            RegionalStatus.UNKNOWN to RegionalExplanationKind.UNMEASURED)
        expected.forEach { (status, kind) -> assertEquals(kind, RegionalExplanation.from(result(status)).kind) }
        assertEquals(RegionalExplanationKind.REGION_CONFIRMED,
            RegionalExplanation.from(result(RegionalStatus.REGION_RESTRICTED, confirmed = true)).kind)
        assertEquals(RegionalExplanationAction.VERIFY_FEATURE,
            RegionalExplanation.from(result(RegionalStatus.WEB_REACHABLE)).action)
        assertEquals(RegionalExplanationAction.OPEN_BROWSER,
            RegionalExplanation.from(result(RegionalStatus.AUTH_REQUIRED)).action)
        assertEquals(RegionalExplanationAction.COMPARE_CONNECTIONS,
            RegionalExplanation.from(result(RegionalStatus.REGION_RESTRICTED)).action)
    }

    @Test fun failedStagesAreSpecificButDoNotClaimLaterStagesWereMeasured() {
        mapOf(RegionalFailure.DNS to RegionalExplanationKind.DNS_FAILURE,
            RegionalFailure.TLS to RegionalExplanationKind.TLS_FAILURE,
            RegionalFailure.TIMEOUT to RegionalExplanationKind.TIMEOUT,
            RegionalFailure.CONNECTION to RegionalExplanationKind.CONNECTION_FAILURE,
            RegionalFailure.UNMEASURED to RegionalExplanationKind.UNMEASURED).forEach { (failure, kind) ->
            assertEquals(kind, RegionalExplanation.from(result(RegionalStatus.NETWORK_ERROR, failure)).kind)
        }
    }

    @Test fun genericDenialsAndMissingObservationsAreNeverGeoProof() {
        listOf(403, 451, 500).forEach { code ->
            assertEquals(RegionalExplanationKind.HTTP_UNCLASSIFIED,
                RegionalExplanation.from(result(RegionalStatus.UNKNOWN, code = code)).kind)
        }
        assertEquals(RegionalExplanationKind.UNMEASURED, RegionalExplanation.from(null).kind)
        assertEquals(RegionalExplanationAction.CHECK_NOW, RegionalExplanation.from(null).action)
    }
}
