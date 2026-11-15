package io.github.driveenglishlab.pleos.domain

import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic approved-route finals; no platform/provider/physical-language claims. */
class CaptureConfirmationBoundaryTest {
    private val owner = OwnerContext(UUID.randomUUID(), 2, 4, UUID.randomUUID())
    private val task = PracticeContentRef(ContentRef(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ListeningMode.ORIGINAL_FULL), UUID.randomUUID(), UUID.randomUUID())
    private val lesson = UUID.randomUUID()
    private fun allowed() = AccessState(SafetyState(VehiclePosition.PARKED, true, true, true), WindowState(true, true),
        PowerAvailability.UNKNOWN, true, true, true, true, true).withSyntheticFeatureRoutes()
    private suspend fun ready() = SessionCoordinator().also { it.activate(owner, task, lesson); it.updateAccess(allowed()) }
    private suspend fun candidate(c: SessionCoordinator): Pair<CaptureLease,CaptureConfirmationView> {
        val lease = c.startAnswer()!!; assertTrue(c.finishAnswer(lease.captureId))
        val confirmation = c.beginCaptureConfirmation(lease.captureId, "  A city opens a bridge.  ", Capture.unverified(CapturePath.PARKED_STT))!!
        return lease to confirmation
    }
    @Test fun unknownFinalAsksOnceSameTokenAndReceiptPreserveOriginalFormAndText() = runBlocking {
        val c=ready();val (lease,prompt)=candidate(c)
        assertEquals(InputPhase.FINALIZING,c.snapshot().inputPhase);assertNull(c.snapshot().pendingAnswer)
        assertNull(c.beginCaptureConfirmation(lease.captureId,"replacement",Capture.unverified(CapturePath.PARKED_STT)))
        assertNull(c.chooseCaptureConfirmation(lease.captureId,UUID.randomUUID()))
        val draft=c.chooseCaptureConfirmation(lease.captureId,prompt.confirmationToken)!!
        assertEquals("A city opens a bridge.",draft.text)
        assertNull(c.chooseCaptureConfirmation(lease.captureId,prompt.confirmationToken))
        var stored:AcceptedAnswer?=null
        val answer=c.acceptConfirmedFinal(lease.captureId,prompt.confirmationToken,"clc1:${lease.captureId}"){stored=it}!!
        assertEquals(stored,answer);assertEquals(draft.text,answer.text)
        assertEquals(EnglishEvidence.SPECIFIC_CONFIRMATION,answer.capture.englishEvidence)
        assertEquals(CaptureFidelity.UNKNOWN,answer.capture.formFidelity)
        assertEquals(draft.originalCapture.uncertainSpans,answer.capture.uncertainSpans)
        assertNull(c.snapshot().captureConfirmation)
        assertNull(c.captureConfirmationCandidate(lease.captureId,prompt.confirmationToken))
        assertNull(c.acceptConfirmedFinal(lease.captureId,prompt.confirmationToken,"clc1:${lease.captureId}"){fail("duplicate")})
    }
    @Test fun knownKoreanAndVerifiedPathNeverUseUnknownConfirmationBranch() = runBlocking {
        for(capture in listOf(Capture(CapturePath.PARKED_STT,CaptureFidelity.UNKNOWN,emptyList(),SpokenLanguage.KO,EnglishEvidence.VERIFIED_NON_ENGLISH,"original-ko"),
            Capture(CapturePath.PARKED_STT,CaptureFidelity.UNKNOWN,emptyList(),SpokenLanguage.EN,EnglishEvidence.VERIFIED_PATH,"reviewed-path"))) {
            val c=ready();val lease=c.startAnswer()!!;c.finishAnswer(lease.captureId)
            assertNull(c.beginCaptureConfirmation(lease.captureId,"A bridge.",capture))
            assertEquals(capture,c.acceptFinal(lease.captureId,"A bridge.",capture){}!!.capture)
        }
    }
    @Test fun twoNonEnglishChoicesKeepOriginalUnverifiedCaptureAndDoNotCallHelper() = runBlocking {
        repeat(2) {
            val c=ready();val (lease,prompt)=candidate(c)
            val answer=c.acceptUnverifiedFinal(lease.captureId,prompt.confirmationToken){}!!
            assertEquals(Capture.unverified(CapturePath.PARKED_STT),answer.capture)
            assertEquals(InputPhase.ACCEPTED,c.snapshot().inputPhase)
            assertNull(c.chooseCaptureConfirmation(lease.captureId,prompt.confirmationToken))
        }
    }
    @Test fun transportFailureDoesNotAcceptAndExplicitRetryKeepsOriginalIdentity() = runBlocking {
        val c=ready();val(lease,prompt)=candidate(c)
        val original=c.chooseCaptureConfirmation(lease.captureId,prompt.confirmationToken)!!
        assertTrue(c.confirmationFailed(lease.captureId,prompt.confirmationToken))
        assertNull(c.snapshot().pendingAnswer)
        assertEquals(CaptureConfirmationPhase.RETRYABLE,c.snapshot().captureConfirmation!!.phase)
        assertNull(c.chooseCaptureConfirmation(lease.captureId,prompt.confirmationToken))
        assertEquals(original,c.retryCaptureConfirmation(lease.captureId,prompt.confirmationToken))
        assertNull(c.retryCaptureConfirmation(lease.captureId,prompt.confirmationToken))
        assertNotNull(c.acceptConfirmedFinal(lease.captureId,prompt.confirmationToken,"clc1:${lease.captureId}"){})
    }
    @Test fun cancellationAndEveryAccessLossDiscardRamAndRejectLateReceipt() = runBlocking {
        val changed=listOf<(AccessState)->AccessState>(
            {it.copy(safety=it.safety.copy(position=VehiclePosition.DRIVE_STOPPED,generation=1))},
            {it.copy(window=it.window.copy(visible=false,generation=1))},
            {it.copy(window=it.window.copy(focused=false,generation=1))},
            {it.copy(directReplyFocusAllowed=false)},
            {it.copy(components=it.components.copy(microphone=it.components.microphone.copy(permissionGeneration=2)))},
            {it.copy(power=PowerAvailability.SUSPENDING)})
        for(transform in changed) {
            val c=ready();val(lease,prompt)=candidate(c);c.chooseCaptureConfirmation(lease.captureId,prompt.confirmationToken)
            c.updateAccess(transform(allowed()));c.updateAccess(allowed())
            assertNull(c.snapshot().captureConfirmation)
            assertNull(c.captureConfirmationCandidate(lease.captureId,prompt.confirmationToken))
            assertNull(c.acceptConfirmedFinal(lease.captureId,prompt.confirmationToken,"clc1:${lease.captureId}"){fail("late")})
        }
        val c=ready();val(lease,prompt)=candidate(c);assertTrue(c.cancelCapture(owner))
        assertNull(c.acceptUnverifiedFinal(lease.captureId,prompt.confirmationToken){fail("cancelled")})
    }
    @Test fun ownerEpochNavigationAndNewCaptureCannotReuseOldTokenOrText() = runBlocking {
        val c=ready();val(old,prompt)=candidate(c)
        c.fenceOwner(owner);c.activate(owner.copy(privacyEpoch=3,revision=5),task,lesson);c.updateAccess(allowed())
        assertNull(c.acceptConfirmedFinal(old.captureId,prompt.confirmationToken,"clc1:${old.captureId}"){fail("old owner")})
        val next=c.startAnswer()!!;assertNotEquals(old.captureId,next.captureId);assertNotEquals(old.attemptId,next.attemptId)
        c.cancelCapture(next.owner)
        val fresh=ready();val(lease,question)=candidate(fresh)
        fresh.navigate(owner,SessionScreen.NEWS)
        assertNull(fresh.captureConfirmationCandidate(lease.captureId,question.confirmationToken))
        assertNull(fresh.snapshot().captureConfirmation)
    }
    @Test fun receiptRefAndTokenAreExactAndGenericAcceptCannotBypassPrompt() = runBlocking {
        val c=ready();val(lease,prompt)=candidate(c)
        assertNull(c.acceptFinal(lease.captureId,"replacement",Capture.unverified(CapturePath.PARKED_STT)){fail("bypass")})
        c.chooseCaptureConfirmation(lease.captureId,prompt.confirmationToken)
        assertNull(c.acceptConfirmedFinal(lease.captureId,prompt.confirmationToken,"yes"){fail("claim")})
        assertNull(c.acceptConfirmedFinal(lease.captureId,UUID.randomUUID(),"clc1:${lease.captureId}"){fail("token")})
        assertNotNull(c.acceptConfirmedFinal(lease.captureId,prompt.confirmationToken,"clc1:${lease.captureId}"){})
    }
    @Test fun localTransactionFailureConsumesLeaseAndLeavesNoReusableCandidate() = runBlocking {
        val c=ready();val(lease,prompt)=candidate(c);c.chooseCaptureConfirmation(lease.captureId,prompt.confirmationToken)
        try { c.acceptConfirmedFinal(lease.captureId,prompt.confirmationToken,"clc1:${lease.captureId}"){error("test local rollback")};fail() }
        catch(_:IllegalStateException){}
        assertNull(c.snapshot().pendingAnswer);assertNull(c.snapshot().captureLease);assertNull(c.snapshot().captureConfirmation)
        assertNull(c.acceptConfirmedFinal(lease.captureId,prompt.confirmationToken,"clc1:${lease.captureId}"){fail("reaccept")})
        assertNotEquals(lease.captureId,c.startAnswer()!!.captureId)
    }
    @Test fun acceptanceBeforeCancelCommitsOnceAndCancelCannotEraseOriginalAnswer() = runBlocking {
        val c=ready();val(lease,prompt)=candidate(c);c.chooseCaptureConfirmation(lease.captureId,prompt.confirmationToken)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var commits=0
        val accepted=async { c.acceptConfirmedFinal(lease.captureId,prompt.confirmationToken,"clc1:${lease.captureId}") {
            entered.complete(Unit);release.await();commits++
        } }
        entered.await();val cancelled=async {c.cancelCapture(owner)};release.complete(Unit)
        assertNotNull(accepted.await());assertFalse(cancelled.await());assertEquals(1,commits)
        assertEquals(lease.captureId,c.snapshot().pendingAnswer!!.lease.captureId)
    }
    @Test fun unrelatedMediaIntentChangeExpiresOnlyExistingConfirmationAndCannotStickSending() = runBlocking {
        for (sending in listOf(false,true)) {
            val c=ready();val (lease,prompt)=candidate(c)
            if(sending)c.chooseCaptureConfirmation(lease.captureId,prompt.confirmationToken)
            val access=allowed()
            c.updateAccess(access.copy(components=access.components.copy(audio=access.components.audio.copy(permissionGeneration=8))))
            assertNull(c.snapshot().captureConfirmation);assertNull(c.snapshot().captureLease)
            assertNull(c.captureConfirmationCandidate(lease.captureId,prompt.confirmationToken))
            assertFalse(c.confirmationFailed(lease.captureId,prompt.confirmationToken))
            assertTrue(c.snapshot().access.canCapture)
        }
    }
    @Test fun scopedCancellationCannotCancelANewerCaptureOfTheSameOwner() = runBlocking {
        val c=ready();val(old,prompt)=candidate(c)
        assertTrue(c.cancelCaptureConfirmation(old.captureId,prompt.confirmationToken))
        val fresh=c.startAnswer()!!
        assertFalse(c.cancelCaptureConfirmation(old.captureId,prompt.confirmationToken))
        assertFalse(c.cancelCapture(owner,old.captureId))
        assertEquals(fresh,c.snapshot().captureLease)
    }
    @Test fun finalRouteGuardRunsInsideAcceptanceForEnglishAndBothUnverifiedChoices() = runBlocking {
        for(english in listOf(true,false,false)) {
            val c=ready();val(lease,prompt)=candidate(c)
            if(english)c.chooseCaptureConfirmation(lease.captureId,prompt.confirmationToken)
            var guards=0;var stored=0
            val answer=if(english)c.acceptConfirmedFinal(lease.captureId,prompt.confirmationToken,"clc1:${lease.captureId}",
                currentRoute={guards++;false}){stored++}
            else c.acceptUnverifiedFinal(lease.captureId,prompt.confirmationToken,currentRoute={guards++;false}){stored++}
            assertNull(answer);assertEquals(1,guards);assertEquals(0,stored)
            assertNull(c.snapshot().pendingAnswer);assertNull(c.snapshot().captureConfirmation);assertNull(c.snapshot().captureLease)
        }
    }
    @Test fun emptyOversizedAndUnverifiedFormCannotBecomeConfirmationCandidates() = runBlocking {
        for(text in listOf("   ","x".repeat(1201))) {
            val c=ready();val lease=c.startAnswer()!!;c.finishAnswer(lease.captureId)
            assertNull(c.beginCaptureConfirmation(lease.captureId,text,Capture.unverified(CapturePath.PARKED_STT)))
        }
        val c=ready();val lease=c.startAnswer()!!;c.finishAnswer(lease.captureId)
        assertNull(c.beginCaptureConfirmation(lease.captureId,"Words",Capture.unverified(CapturePath.PARKED_STT).copy(formFidelity=CaptureFidelity.CONFIRMED_FORM)))
    }
}
