package io.github.driveenglishlab.pleos.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual generic adapter's RAM submission seam. Cleanup/current values are explicit
 * synthetic facts, not SDK, OS permission, microphone, provider, notice or project approval proof. */
@RunWith(AndroidJUnit4::class)
class DirectSpeechInputGateTest {
    private fun lease():CaptureLease {
        val owner=OwnerContext(UUID.randomUUID(),0,1,UUID.randomUUID())
        val content=ContentRef(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),ListeningMode.ORIGINAL_FULL)
        return CaptureLease(UUID.randomUUID(),UUID.randomUUID(),owner,PracticeContentRef(content,UUID.randomUUID(),UUID.randomUUID()),UUID.randomUUID(),2,3,4)
    }
    @Test fun asynchronousResultEndAndExplicitFinishOrdersAllRequireCleanupAndDeliverOnce() {
        for(order in listOf(listOf("final","finish","end"),listOf("finish","end","final"),listOf("end","final","finish"))) {
            val lease=lease();val gate=DirectInputFinalGate(lease,11,13)
            for(step in order) {
                when(step){"final"->assertTrue(gate.final("  I saw the news. 🚗  "));"end"->gate.end();"finish"->gate.finish()}
                assertNull("SDK final/endpoint never bypass cleanup and explicit finish",gate.take(lease,11,13,true))
            }
            gate.cleanup(true)
            assertEquals("I saw the news. 🚗",gate.take(lease,11,13,true))
            assertNull(gate.take(lease,11,13,true));assertNull(gate.text)
        }
    }
    @Test fun normalFinalReadyWaitsForUserWhileWrongOwnerTaskOrOldGenerationsCannotConsumeIt() {
        val lease=lease();val gate=DirectInputFinalGate(lease,11,13)
        assertTrue(gate.final("My answer."));gate.end();gate.cleanup(true)
        assertNull(gate.take(lease,11,13,true)) // normal mic termination is not user submission
        gate.finish()
        assertNull(gate.take(lease.copy(owner=lease.owner.copy(privacyEpoch=1)),11,13,true))
        assertNull(gate.take(lease.copy(task=this.lease().task),11,13,true))
        assertNull(gate.take(lease,12,13,true));assertNull(gate.take(lease,11,14,true))
        assertNull(gate.take(lease,11,13,false))
        assertEquals("My answer.",gate.take(lease,11,13,true)) // same logical generations survive normal cleanup
    }
    @Test fun CancelLateCallbackAndConflictingFinalNeverReviveTheOriginalCandidate() {
        val lease=lease();val cancelled=DirectInputFinalGate(lease,11,13)
        assertTrue(cancelled.final("Original."));cancelled.cancel()
        cancelled.end();cancelled.finish();cancelled.cleanup(true)
        assertFalse(cancelled.final("Late."));assertNull(cancelled.take(lease,11,13,true));assertNull(cancelled.text)
        val ambiguous=DirectInputFinalGate(lease,11,13)
        assertTrue(ambiguous.final("Same."));assertTrue(ambiguous.final(" Same. "))
        assertFalse(ambiguous.final("Different."));ambiguous.end();ambiguous.finish();ambiguous.cleanup(true)
        assertNull(ambiguous.take(lease,11,13,true));assertNull(ambiguous.text)
        val invalid=DirectInputFinalGate(lease,11,13)
        assertFalse(invalid.final(" "));assertFalse(invalid.final("x".repeat(1201)))
        invalid.finish();invalid.end();invalid.cleanup(false)
        assertNull(invalid.take(lease,11,13,true))
    }
}
