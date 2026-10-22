package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class RegionalConsensusTest {
    private fun state(): Any {
        val type = runCatching { Class.forName("ru.tgwatch.RegionalConsensus") }.getOrNull()
        assertNotNull("Regional warnings need independent same-network confirmation",type)
        return type!!.getConstructor().newInstance()
    }
    private fun update(state: Any, status: RegionalStatus, scope: String, elapsed: Long) {
        state.javaClass.getMethod("update",RegionalStatus::class.java,String::class.java,
            Long::class.javaPrimitiveType).invoke(state,status,scope,elapsed)
    }
    private fun confirmed(state: Any) = state.javaClass.getMethod("confirmed").invoke(state) as Boolean
    private fun notify(state: Any, now: Long) = state.javaClass.getMethod("shouldNotify",
        Long::class.javaPrimitiveType).invoke(state,now) as Boolean
    private fun delivered(state: Any, now: Long) { state.javaClass.getMethod("recordNotification",
        Long::class.javaPrimitiveType).invoke(state,now) }

    @Test fun warningRequiresTwoObservationsFifteenSecondsApart() {
        val s = state()
        update(s,RegionalStatus.REGION_RESTRICTED,"n1:g1",0)
        assertFalse(confirmed(s)); assertFalse(notify(s,0))
        update(s,RegionalStatus.REGION_RESTRICTED,"n1:g1",14_999)
        assertFalse(confirmed(s))
        update(s,RegionalStatus.REGION_RESTRICTED,"n1:g1",15_000)
        assertTrue(confirmed(s)); assertTrue(notify(s,15_000))
    }
    @Test fun networkAndConfigurationChangesCannotCombineEvidence() {
        val s = state()
        update(s,RegionalStatus.REGION_RESTRICTED,"n1:g1",0)
        update(s,RegionalStatus.REGION_RESTRICTED,"n2:g1",20_000)
        assertFalse(confirmed(s))
        update(s,RegionalStatus.REGION_RESTRICTED,"n2:g2",40_000)
        assertFalse(confirmed(s))
        update(s,RegionalStatus.REGION_RESTRICTED,"n2:g2",55_000)
        assertTrue(confirmed(s))
    }
    @Test fun networkFailureBreaksCandidateEvidenceAndNeverReopensAnAlertedIncident() {
        val s = state()
        update(s,RegionalStatus.REGION_RESTRICTED,"n1:g1",0)
        update(s,RegionalStatus.NETWORK_ERROR,"n1:g1",20_000)
        update(s,RegionalStatus.REGION_RESTRICTED,"n1:g1",30_000)
        assertFalse(confirmed(s))
        update(s,RegionalStatus.REGION_RESTRICTED,"n1:g1",45_000)
        delivered(s,45_000)
        update(s,RegionalStatus.NETWORK_ERROR,"n1:g1",60_000)
        assertFalse(notify(s,60_000))
        update(s,RegionalStatus.REGION_RESTRICTED,"n1:g1",600_000)
        update(s,RegionalStatus.REGION_RESTRICTED,"n1:g1",615_000)
        assertTrue(confirmed(s)); assertFalse(notify(s,615_000))
    }
    @Test fun blockedOrQuietDeliveryDoesNotConsumeTheWarning() {
        val s = state()
        update(s,RegionalStatus.REGION_RESTRICTED,"n1:g1",0)
        update(s,RegionalStatus.REGION_RESTRICTED,"n1:g1",15_000)
        assertTrue(notify(s,15_000)); assertTrue(notify(s,20_000))
        delivered(s,20_000)
        assertFalse(notify(s,30_000))
    }
    @Test fun newIncidentsRespectFiveMinuteCooldown() {
        val s = state()
        update(s,RegionalStatus.REGION_RESTRICTED,"n1:g1",0)
        update(s,RegionalStatus.REGION_RESTRICTED,"n1:g1",15_000); delivered(s,15_000)
        update(s,RegionalStatus.WEB_REACHABLE,"n1:g1",30_000)
        update(s,RegionalStatus.REGION_RESTRICTED,"n1:g1",100_000)
        update(s,RegionalStatus.REGION_RESTRICTED,"n1:g1",115_000)
        assertFalse(notify(s,115_000)); assertTrue(notify(s,315_000))
    }
    @Test fun elapsedClockResetRequiresFreshEvidence() {
        val s = state()
        update(s,RegionalStatus.REGION_RESTRICTED,"n1:g1",100_000)
        update(s,RegionalStatus.REGION_RESTRICTED,"n1:g1",0)
        assertFalse(confirmed(s))
        update(s,RegionalStatus.REGION_RESTRICTED,"n1:g1",15_000)
        assertTrue(confirmed(s))
    }
}
