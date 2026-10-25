package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Test

class TargetOverviewPolicyTest {
    @Test fun targetStatesStayIndependentAndPendingHasPriority() {
        val ok = TargetSnapshot(TargetId.MATRIX, TargetStatus.OK, checkedAtMs = 10_000,
            checkedElapsedMs = 100_000, expectedIntervalSec = 60)
        val down = TargetSnapshot(TargetId.CUSTOM, TargetStatus.DOWN, checkedAtMs = 11_000,
            checkedElapsedMs = 100_000, expectedIntervalSec = 60)
        assertEquals(TargetOverviewState.MEASURED, TargetOverviewPolicy.state(input(ok)))
        assertEquals(TargetOverviewState.MEASURED, TargetOverviewPolicy.state(input(down)))
        assertEquals(TargetOverviewState.PENDING, TargetOverviewPolicy.state(input(down).copy(
            running = false, enabled = false, pending = true)))
    }

    @Test fun stoppedCheckingAndStaleResultsAreNotPresentedAsFreshMeasurements() {
        val unknown = TargetSnapshot(TargetId.CUSTOM_2)
        assertEquals(TargetOverviewState.OFF, TargetOverviewPolicy.state(input(unknown).copy(running = false)))
        assertEquals(TargetOverviewState.CHECKING, TargetOverviewPolicy.state(input(unknown)))
        val stale = unknown.copy(status = TargetStatus.OK, checkedAtMs = 1_000,
            checkedElapsedMs = 1_000, expectedIntervalSec = 60)
        assertEquals(TargetOverviewState.STALE, TargetOverviewPolicy.state(input(stale).copy(elapsedMs = 400_000)))
    }

    @Test fun restoredSnapshotWithoutElapsedClockUsesWallClockOnlyForStaleness() {
        val restored = TargetSnapshot(TargetId.WHATSAPP, TargetStatus.OK, checkedAtMs = 10_000,
            expectedIntervalSec = 60)
        assertEquals(TargetOverviewState.MEASURED, TargetOverviewPolicy.state(input(restored)))
        assertEquals(TargetOverviewState.STALE, TargetOverviewPolicy.state(input(restored).copy(nowMs = 400_000)))
    }

    private fun input(snapshot: TargetSnapshot) = TargetOverviewInput(snapshot, running = true,
        enabled = true, pending = false, nowMs = 20_000, elapsedMs = 101_000)
}
