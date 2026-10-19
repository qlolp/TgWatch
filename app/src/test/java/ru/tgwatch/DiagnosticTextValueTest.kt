package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class DiagnosticTextValueTest {
    @Test fun reportAndFailureRemainTypedUntilAndroidPresentation() {
        val report = ProbeReport(emptyList(), connected = false)
        assertNotNull(report.reason.key)
        assertTrue(ProbeRules.describeHttpFailure(503).args.contains(TextArgument.Number(503)))
    }
    @Test fun exceptionMessageCannotLeakIntoDiagnosticPresentation() {
        val secret = "https://private.example/token-secret"
        val failure = PhoneProbeAdapter.error(IllegalStateException(secret))
        assertNotNull(failure.key)
        assertFalse(failure.allArguments().any { it is TextArgument.Text && secret in it.value })
        assertTrue(failure.allArguments().contains(TextArgument.Text("IllegalStateException")))
    }
}
