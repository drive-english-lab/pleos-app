package io.github.driveenglishlab.pleos.domain

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ReviewRuntimeTest {
    private val owner = OwnerContext(UUID.randomUUID(), 0, 1, UUID.randomUUID())
    private val profile = ProfileContext(owner.profileId, 0)
    private val selection = UUID.randomUUID()
    private val offer = UUID.randomUUID()
    private fun context(history: Boolean = false) = ReviewContext(profile, selection, 1,
        if (history) ReviewOrigin.HISTORY_REPLAY else ReviewOrigin.REGULAR,
        if (history) ReviewEndPolicy.MANUAL_HISTORY else ReviewEndPolicy.REGULAR_AUTO,
        offer, null, if (history) UUID.randomUUID() else null, null, null, null)
    private fun gate(policy: ReviewPolicyState = ReviewPolicyState(context())) = ReviewOfferGate(owner, policy, SegmentPhase.ARTICLE_OFFER, true, true)

    @Test fun settingsDiscardOldCountdownAndOnlyMatchingNewAnnouncementStartsFreshEightSeconds() {
        val gate = gate()
        val first = ReviewOfferReducer.reduce(ReviewOfferRuntime(owner, selection, offer), ReviewOfferEvent.Announce(UUID.randomUUID()), gate).state
        val oldOutput = requireNotNull(first.expected)
        val waiting = ReviewOfferReducer.reduce(first, ReviewOfferEvent.OutputCompleted(oldOutput, 100), gate).state
        val settings = ReviewOfferReducer.reduce(waiting, ReviewOfferEvent.SettingsOpened(ReviewEntryOrigin.NEWS, true), gate).state
        assertNull(settings.wait)
        val left = ReviewOfferReducer.reduce(settings, ReviewOfferEvent.SettingsUserLeft(UUID.randomUUID()), gate).state
        assertNotEquals(oldOutput.requestId, left.expected?.requestId)
        assertEquals(offer, left.expected?.offerId)
        assertNull(ReviewOfferReducer.reduce(left, ReviewOfferEvent.OutputCompleted(oldOutput, 10_000), gate).state.wait)
        val actual = ReviewOfferReducer.reduce(left, ReviewOfferEvent.OutputCompleted(requireNotNull(left.expected), 10_000), gate).state
        assertTrue(ReviewOfferReducer.reduce(actual, ReviewOfferEvent.Tick(17_999), gate).effects.isEmpty())
        assertTrue(ReviewOfferReducer.reduce(actual, ReviewOfferEvent.Tick(18_000), gate).effects.single() is ReviewOfferEffect.RequestNextAuto)
    }

    @Test fun historyCancelAndRestorationCannotAcquireAutomaticOfferTimer() {
        for (policy in listOf(ReviewPolicyState(context(true)), ReviewPolicyState(context(), localCancelledOfferId = offer))) {
            val gate = gate(policy)
            val opened = ReviewOfferReducer.reduce(ReviewOfferRuntime(owner, selection, offer), ReviewOfferEvent.SettingsOpened(ReviewEntryOrigin.HISTORY, true), gate).state
            assertTrue(ReviewOfferReducer.reduce(opened, ReviewOfferEvent.SettingsUserLeft(UUID.randomUUID()), gate).effects.none { it is ReviewOfferEffect.AnnounceFullOffer })
        }
        val restored = ReviewOfferReducer.reduce(ReviewOfferRuntime(owner, selection, offer), ReviewOfferEvent.Restored, gate()).state
        assertNull(ReviewOfferReducer.reduce(restored, ReviewOfferEvent.Announce(UUID.randomUUID()), gate()).state.expected)
        val cancelled = ReviewPolicyState(context(), localCancelledOfferId = offer)
        assertFalse(ReviewContextPolicy.acknowledge(cancelled, context()).canAutoOffer)
    }

    private fun snapshot(): ReviewReturnSnapshot {
        val cursor = PlaybackCursor6.Segment(UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), 1, "b".repeat(64), SegmentPhase.MAIN,
            UUID.randomUUID(), 0, true, PauseReason.NONE)
        return ReviewReturnSnapshot(profile, selection, UUID.randomUUID(), 1, null, SegmentPhase.MAIN, cursor, true, PauseReason.NONE, 1)
    }
    private fun access() = AccessState(SafetyState(VehiclePosition.PARKED, true, true, true), WindowState(true, true),
        PowerAvailability.ALLOWED, true, true, true, true, true)

    @Test fun closeDiscardsUnsubmittedOnlyAndLateFeedbackCannotReopenOrNavigate() {
        val token = PracticeModalToken(owner, selection, UUID.randomUUID(), UUID.randomUUID(), 1)
        val modal = requireNotNull(PracticeModalReducer.open(token, snapshot(), ReviewEntryOrigin.HISTORY,
            PracticeModalLesson(PracticeModalPhase.QUESTION, 1), access(), owner, PracticeModalAdmission(true, true, true, true, true)))
        val recording = PracticeModalReducer.reduce(modal, PracticeModalEvent.BeginCapture(UUID.randomUUID()), access(), owner).session
        val close = PracticeModalReducer.reduce(recording, PracticeModalEvent.Close(PracticeModalCloseReason.FINISH), access(), owner)
        assertFalse(close.session.visible)
        assertFalse(close.session.lesson.complete)
        assertEquals(PracticeModalPhase.QUESTION, close.session.phase)
        assertTrue(close.effects.contains(PracticeModalEffect.StopCaptureAndDiscardUnsubmitted))
        val late = PracticeModalReducer.reduce(close.session, PracticeModalEvent.LessonUpdated(token,
            PracticeModalLesson(PracticeModalPhase.FEEDBACK, 2, UUID.randomUUID())), access(), owner)
        assertEquals(close.session, late.session)
        assertTrue(late.effects.single() is PracticeModalEffect.StoreOriginalLessonOnly)
        assertTrue(close.session.canReturnToHistory) // An available explicit route is not a navigation effect.
    }

    @Test fun switchingAuxiliaryPreservesFirstReturnPointAndCancelIsIndependentOfMaterialSuccess() {
        val original = snapshot()
        val context = context().copy(returnSnapshot = null)
        val initial = ReviewPolicyState(context)
        val offerSnapshot = original.copy(phase = SegmentPhase.ARTICLE_OFFER, cursor = (original.cursor as PlaybackCursor6.Segment).copy(
            phase = SegmentPhase.ARTICLE_OFFER, segmentId = null, segmentIndex = null))
        val sentence = ReviewContextPolicy.enterAuxiliary(initial, ReviewAuxiliaryKind.SENTENCE, offerSnapshot)
        assertTrue(sentence.effects.any { it is ReviewContextEffect.PersistLocalCancellation })
        val switched = ReviewContextPolicy.enterAuxiliary(sentence.state, ReviewAuxiliaryKind.PREVIEW, original)
        assertEquals(offerSnapshot, switched.state.original)
        assertFalse(ReviewContextPolicy.closeAuxiliary(switched.state).state.canAutoOffer)
    }
}
