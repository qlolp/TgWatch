package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class IncidentGroupingTest {
    @Test fun disabledPolicyAlwaysUsesIndividualPresentation() {
        val policy = IncidentGrouping()
        assertTrue(policy.outage(TargetId.TELEGRAM, 0, enabled = false).postIndividual)
        assertTrue(policy.outage(TargetId.MATRIX, 1, enabled = false).postIndividual)
    }

    @Test fun secondDistinctTargetFormsOneGroupAndCancelsBothIndividualCards() {
        val policy = IncidentGrouping()
        assertTrue(policy.outage(TargetId.TELEGRAM, 100, enabled = true).postIndividual)
        val second = policy.outage(TargetId.MATRIX, 200, enabled = true)
        assertFalse(second.postIndividual)
        assertEquals(listOf(TargetId.TELEGRAM, TargetId.MATRIX), second.cancelIndividuals)
        assertEquals(second.cancelIndividuals, second.group)
        val update = policy.outage(TargetId.WHATSAPP, 300, enabled = true)
        assertEquals(3, update.group?.size)
        assertFalse(update.postIndividual)
    }

    @Test fun repeatedFailureOfOneTargetDoesNotFormAGroup() {
        val policy = IncidentGrouping()
        policy.outage(TargetId.TELEGRAM, 100, enabled = true)
        assertTrue(policy.outage(TargetId.TELEGRAM, 200, enabled = true).postIndividual)
    }

    @Test fun recoveryCancelsGroupBelowTwoTargetsWithoutResurrectingSingles() {
        val policy = IncidentGrouping()
        policy.outage(TargetId.TELEGRAM, 100, enabled = true)
        policy.outage(TargetId.MATRIX, 200, enabled = true)
        val remaining = policy.clear(TargetId.TELEGRAM)
        assertTrue(remaining.cancelGroup)
        assertNull(remaining.group)
        assertTrue(policy.clear(TargetId.MATRIX).cancelGroup)
        assertTrue(policy.outage(TargetId.MATRIX, 300, enabled = true).postIndividual)
    }

    @Test fun windowAndClockRollbackStartANewIndividualSequence() {
        val policy = IncidentGrouping()
        policy.outage(TargetId.TELEGRAM, 100, enabled = true)
        assertTrue(policy.outage(TargetId.MATRIX, 100 + IncidentGrouping.WINDOW_MS + 1, true).postIndividual)
        policy.outage(TargetId.MATRIX, 1_000, enabled = true)
        assertTrue(policy.outage(TargetId.WHATSAPP, 900, enabled = true).postIndividual)
    }

    @Test fun optingOutCancelsExistingGroup() {
        val policy = IncidentGrouping()
        policy.outage(TargetId.TELEGRAM, 100, enabled = true)
        policy.outage(TargetId.MATRIX, 200, enabled = true)
        assertTrue(policy.setEnabled(false).cancelGroup)
        assertTrue(policy.outage(TargetId.WHATSAPP, 300, enabled = true).postIndividual)
    }
}
