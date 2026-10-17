package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class TargetSessionLedgerTest {
    @Test fun endingSecondaryOnlySessionNeverClosesUntouchedTelegramOrAnotherSession() {
        val ledger = TargetSessionLedger()
        ledger.recorded(1, TargetId.MATRIX)
        ledger.recorded(2, TargetId.TELEGRAM)
        assertEquals(setOf(TargetId.MATRIX), ledger.end(1))
        assertTrue(ledger.end(1).isEmpty())
        assertEquals(setOf(TargetId.TELEGRAM), ledger.end(2))
        assertTrue(ledger.end(3).isEmpty())
    }
}
