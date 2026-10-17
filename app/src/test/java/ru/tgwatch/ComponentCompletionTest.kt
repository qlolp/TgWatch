package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class ComponentCompletionTest {
    @Test fun deadlineFinishesReceiverOnceAndQueuedWorkCanSeeItsExpiredAdmission() {
        var expire: (() -> Unit)? = null
        var finishes = 0
        var cancellations = 0
        val completion = ComponentCompletion({ finishes++ }, { ms, action ->
            assertEquals(8_000L, ms); expire = action; { cancellations++ }
        })
        assertTrue(completion.current)
        expire!!.invoke()
        assertFalse(completion.current)
        completion.finish()
        assertEquals(1, finishes)
        assertEquals(1, cancellations)
    }
    @Test fun synchronousDeadlineRegistrationRaceStillCancelsAndFinishesOnce() {
        var finishes = 0
        var cancellations = 0
        val completion = ComponentCompletion({ finishes++ }, { _, action -> action(); { cancellations++ } })
        completion.finish()
        assertFalse(completion.current)
        assertEquals(1, finishes)
        assertEquals(1, cancellations)
    }
}
