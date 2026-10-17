package ru.tgwatch

import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class RecoveryGateTest {
    @Test fun recoveryFailureDoesNotStartProbesAndAllowsRetry() {
        val pending = ArrayDeque<Runnable>()
        val gate = RecoveryGate(Executor { pending.add(it) })
        var probes = 0
        gate.whenReady { probes++ }
        gate.start { throw IllegalStateException("Interrupted durable restore") }
        assertEquals(RecoveryState.INITIALIZING, gate.state)
        assertEquals(0, probes)
        pending.removeFirst().run()
        assertEquals(RecoveryState.FAILED, gate.state)
        assertEquals(0, probes)
        gate.start { }
        pending.removeFirst().run()
        assertEquals(RecoveryState.READY, gate.state)
        assertEquals(1, probes)
    }

    @Test fun duplicateStartDoesNotDuplicateRecovery() {
        val pending = ArrayDeque<Runnable>()
        val gate = RecoveryGate(Executor { pending.add(it) })
        var recoveries = 0
        gate.start { recoveries++ }
        gate.start { recoveries++ }
        assertEquals(1, pending.size)
        pending.removeFirst().run()
        gate.whenReady { recoveries++ }
        assertEquals(2, recoveries)
        gate.start { recoveries++ }
        assertEquals(0, pending.size)
    }

    @Test fun executorRejectionKeepsGateClosed() {
        val gate = RecoveryGate(Executor { throw RejectedExecutionException() })
        gate.start { fail("Rejected recovery ran") }
        assertEquals(RecoveryState.FAILED, gate.state)
        gate.whenReady { fail("A failed recovery allowed a probe") }
    }
}
