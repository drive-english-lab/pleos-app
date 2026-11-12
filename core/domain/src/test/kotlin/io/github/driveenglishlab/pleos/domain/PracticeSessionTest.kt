package io.github.driveenglishlab.pleos.domain

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class PracticeSessionTest {
    @Test fun processingBudgetDoesNotConsumeAnyAnswerTurn() {
        val clock = PracticeRequestClock(); clock.start(100)
        assertEquals(PracticeRequestState.PROCESSING, clock.state(2_099))
        assertEquals(PracticeRequestState.PROCESSING_NOTICE, clock.state(2_100))
        assertEquals(PracticeRequestState.WAIT_EXPIRED, clock.state(10_100))
        clock.clear(); assertEquals(PracticeRequestState.DEVICE_ONLY, clock.state(20_000))
    }
    @Test fun heldRetryKeepsFirstSuccessAndRemainingRetry() {
        val first = UUID.randomUUID()
        val held = PracticeProgress(6, first, null, null, UUID.randomUUID(), "suspended", true, null, null)
        assertNull(held.nextTurn)
        assertEquals(AttemptTurn.RETRY, held.copy(pendingFeedbackAttemptId = null).nextTurn)
        assertEquals(first, held.validFirstAttemptId); assertEquals(true, held.contentSuccess)
        assertNull(held.retryOutcome)
        assertNull(held.copy(phase = "completed").nextTurn)
        assertNull(held.copy(pendingAttemptId = UUID.randomUUID()).nextTurn)
    }
    @Test fun originalLanguageConfirmationIsSpecificAndOnceAndDoesNotConfirmForm() {
        val id = UUID.randomUUID(); val confirmation = CaptureLanguageConfirmation(id, Capture.unverified(CapturePath.GLEO))
        assertTrue(confirmation.request()); assertFalse(confirmation.request())
        assertNull(confirmation.confirm(UUID.randomUUID(), SpokenLanguage.EN, "capture-specific-review"))
        val verified = confirmation.confirm(id, SpokenLanguage.EN, "capture-specific-review")!!
        assertEquals(EnglishEvidence.SPECIFIC_CONFIRMATION, verified.englishEvidence)
        assertEquals(CaptureFidelity.UNKNOWN, verified.formFidelity)
        assertNull(confirmation.confirm(id, SpokenLanguage.EN, "second-review"))
    }
    @Test fun knownNonEnglishCannotBeOverriddenByConfirmation() {
        val known = Capture(CapturePath.GLEO, CaptureFidelity.UNKNOWN, emptyList(), SpokenLanguage.KO,
            EnglishEvidence.VERIFIED_NON_ENGLISH, "original-ko-capture")
        val confirmation = CaptureLanguageConfirmation(UUID.randomUUID(), known)
        assertFalse(confirmation.request())
    }
}
