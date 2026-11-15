package io.github.driveenglishlab.pleos.domain

import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Explicit synthetic current routes/policies. These values are never production verification evidence. */
internal fun AccessState.withSyntheticFeatureRoutes()=copy(
    components=ComponentPolicies(5,ComponentAccess(true,true,5,1),ComponentAccess(true,true,5,1),ComponentAccess(true,true,5,1)),
    mediaRouteAvailable=true,mediaFocusAllowed=true,backgroundMediaAllowed=true,
    gleoRouteAvailable=true,gleoVehicleAllowed=true,parkedOutputAvailable=true,gleoPermissionGeneration=1,directReplyFocusAllowed=true,gleoReplyFocusAllowed=true)

class FeatureAccessBoundaryTest {
    private val owner=OwnerContext(UUID.randomUUID(),0,1,UUID.randomUUID())
    private val content=ContentRef(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),ListeningMode.ORIGINAL_FULL)
    private val task=PracticeContentRef(content,UUID.randomUUID(),UUID.randomUUID())
    private val lesson=UUID.randomUUID()
    private fun access()=AccessState(SafetyState(VehiclePosition.PARKED,true,true,true),WindowState(true,true),
        PowerAvailability.UNKNOWN,true,true,true,true,true).withSyntheticFeatureRoutes()
    private fun caps()=VoiceCapabilities(outputCompletion=Capability.Verified("fixture:actual-end"),
        exactOutputContent=Capability.Verified("fixture:exact"),inputActivity=Capability.Verified("fixture:input"),
        microphoneArbitration=Capability.Verified("fixture:arbitration"))
    private suspend fun question()=SessionCoordinator().also{it.activate(owner,task,lesson,DialogueProgress.newLesson());it.updateAccess(access());it.configureGleo(caps())}
    private data class Listening(val coordinator:SessionCoordinator,val key:NewsPlaybackKey)
    private suspend fun listening():Listening {
        val c=SessionCoordinator();c.activate(owner,null);c.updateAccess(access());c.setMediaConnected(true)
        val work=ListeningWorkRef(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),content,ListeningStage.MAIN,UUID.randomUUID(),60_000,UUID.randomUUID(),1)
        val ticket=requireNotNull(c.beginOperation(SessionOperationKind.SELECTION,owner));assertTrue(c.admitListening(RepositoryCompletion(ticket,work),0,true))
        val key=NewsPlaybackKey(owner,work.selectionId,work.checkpointId,content,content.listeningScriptId,requireNotNull(work.assetId),60_000,work.runId,"main")
        return Listening(c,key)
    }
    @Test fun rawUnknownAllowsOnlyTheFeaturesWhoseOwnConditionsAreCurrent() {
        val a=access().copy(components=ComponentPolicies())
        assertEquals(PowerAvailability.UNKNOWN,a.power);assertTrue(a.detailsOpen);assertTrue(a.persistenceAllowed);assertTrue(a.noticeAllowed)
        assertFalse(a.mediaAllowed);assertFalse(a.canCapture)
        assertTrue(FeatureAccessReason.AUDIO_POLICY_UNKNOWN in FeatureAccessEvaluator.evaluate(FeatureScope.MEDIA,a).reasons)
        assertEquals(PowerAvailability.UNKNOWN,a.power)
    }
    @Test fun actualAwakeDoesNotManufactureParkUxrOwnerRouteOrComponentPermission() {
        val a=AccessState(power=PowerAvailability.ALLOWED)
        assertFalse(a.detailsOpen);assertFalse(a.mediaReady);assertFalse(a.canCapture);assertFalse(a.gleoReady);assertFalse(a.persistenceAllowed)
        assertTrue(FeatureAccessReason.OWNER_UNAVAILABLE in FeatureAccessEvaluator.evaluate(FeatureScope.PERSISTENCE,a).reasons)
    }
    @Test fun componentMustBeKnownAndFromTheCurrentSourceAndDenialIsNotUnknown() {
        val a=access();assertTrue(a.mediaAllowed)
        val stale=a.copy(components=a.components.copy(audio=ComponentAccess(true,true,4,1)))
        assertFalse(stale.mediaReady);assertTrue(FeatureAccessReason.AUDIO_POLICY_UNKNOWN in FeatureAccessEvaluator.evaluate(FeatureScope.MEDIA,stale).reasons)
        val denied=a.copy(components=a.components.copy(audio=ComponentAccess(true,false,5,2)))
        assertFalse(denied.mediaReady);assertTrue(FeatureAccessReason.AUDIO_POLICY_DENIED in FeatureAccessEvaluator.evaluate(FeatureScope.MEDIA,denied).reasons)
        assertTrue(denied.detailsOpen);assertTrue(denied.persistenceAllowed)
    }
    @Test fun hiddenDetailsAndMicrophoneDenialCannotKillVerifiedBackgroundMedia()=runBlocking {
        val (c,key)=listening();assertTrue(c.requestMediaIntent(owner,PlaybackIntent.PLAY))
        val a=access().copy(window=WindowState(false,false,2),safety=SafetyState(VehiclePosition.MOVING_OR_REVERSE_NEUTRAL,true,false,false),
            components=access().components.copy(microphone=ComponentAccess(true,false,5,2)))
        c.updateAccess(a)
        assertFalse(a.detailsOpen);assertFalse(a.canCapture);assertTrue(a.mediaAllowed);assertTrue(c.mediaPermission(key))
        assertEquals(PlaybackIntent.PLAY,c.snapshot().intent)
        c.updateAccess(a.copy(backgroundMediaAllowed=false))
        assertFalse(c.mediaPermission(key));assertTrue(c.snapshot().requiresExplicitMediaResume)
    }
    @Test fun microphoneGrantGenerationPreventsOldFinalAfterAnUnobservedDenyAllowRoundTrip() {
        val input=InputLease();val a=access();val lease=requireNotNull(input.start(owner,task,lesson,a));assertTrue(input.finish(lease.captureId))
        val changed=a.copy(components=a.components.copy(microphone=a.components.microphone.copy(permissionGeneration=3)))
        assertTrue(changed.canCapture)
        assertNull(input.finalText(lease.captureId,"A city opened a library.",changed,owner,task,Capture.unverified(CapturePath.PARKED_STT)))
        assertNotNull(input.start(owner,task,lesson,changed)) // Only a new explicit start gets the new permission.
    }
    @Test fun actualSuspendStopsIntentAndLateMediaObservationCannotUndoTheExplicitResumeFence()=runBlocking {
        val (c,key)=listening();assertTrue(c.requestMediaIntent(owner,PlaybackIntent.PLAY));c.observeMedia(key,100,true,true,"none")
        c.updateAccess(access().copy(power=PowerAvailability.SUSPENDING));assertEquals(PlaybackIntent.STOPPED,c.snapshot().intent)
        c.updateAccess(access());assertTrue(c.snapshot().requiresExplicitMediaResume)
        c.observeMedia(key,110,true,true,"none");assertEquals(PlaybackIntent.STOPPED,c.snapshot().intent);assertFalse(c.snapshot().mediaPlaying)
        assertTrue(c.mediaAdmissionPermission(key));assertFalse(c.mediaPermission(key))
        assertTrue(c.requestMediaIntent(owner,PlaybackIntent.PLAY));assertTrue(c.mediaPermission(key))
    }
    @Test fun transientMediaFocusLossKeepsOnlyOriginalPlayIntentAndNeedsAnActualFreshFocusGrant()=runBlocking {
        val (c,key)=listening();assertTrue(c.requestMediaIntent(owner,PlaybackIntent.PLAY));val generation=c.snapshot().intentGeneration
        c.updateAccess(access().copy(mediaFocusAllowed=false));assertEquals(PlaybackIntent.PLAY,c.snapshot().intent)
        assertEquals(generation,c.snapshot().intentGeneration);assertFalse(c.snapshot().requiresExplicitMediaResume)
        assertTrue(c.mediaAdmissionPermission(key));assertFalse(c.mediaPermission(key))
        var focus=false
        assertFalse(c.mediaFocusedPermission(key,c.snapshot().navigationGeneration,generation){focus})
        focus=true;assertTrue(c.mediaFocusedPermission(key,c.snapshot().navigationGeneration,generation){focus})
        assertFalse(c.snapshot().access.mediaFocusAllowed) // A local actual lease is not a synthesized global observation.
        assertNull(c.tick(1_000_000))
    }
    @Test fun relevantPermissionRoundTripInvalidatesMediaButUnrelatedComponentChangeDoesNot()=runBlocking {
        val (c,key)=listening();assertTrue(c.requestMediaIntent(owner,PlaybackIntent.PLAY));val generation=c.snapshot().intentGeneration
        c.updateAccess(access().copy(components=access().components.copy(microphone=ComponentAccess(true,false,5,2))))
        assertTrue(c.mediaPermission(key));assertEquals(generation,c.snapshot().intentGeneration)
        c.updateAccess(access().copy(components=access().components.copy(audio=ComponentAccess(true,true,5,3))))
        assertTrue(c.snapshot().requiresExplicitMediaResume);assertTrue(c.snapshot().intentGeneration>generation)
        assertFalse(c.mediaFocusedPermission(key,c.snapshot().navigationGeneration,generation){true})
    }
    @Test fun lateActualHintFactSurvivesSuspendButNeverRestartsTheOldClock()=runBlocking {
        val c=question();val output=OutputExpectation(UUID.randomUUID(),WaitKind.HINT_METHOD,owner,task,lesson,OutputRoute.GLEO)
        assertTrue(c.expectOutput(output));c.updateAccess(access().copy(power=PowerAvailability.SUSPENDED))
        var recorded=0
        assertFalse(c.outputCompleted(output.outputId,owner,100,"fixture:matching-actual-end"){_,_->recorded++})
        assertEquals(1,recorded);assertTrue(requireNotNull(c.snapshot().dialogueProgress).hintMethodAnnounced)
        c.updateAccess(access());assertNull(c.tick(1_000_000));assertNull(c.snapshot().expectedOutput)
        assertFalse(c.outputCompleted(output.outputId,owner,200,"fixture:duplicate-end"){_,_->fail()})
    }
    @Test fun gleoUsesItsOwnRouteAndGenerationRatherThanAppMicrophoneOrHiddenWindow()=runBlocking {
        val c=question();val a=access().copy(window=WindowState(false,false,5),safety=SafetyState(VehiclePosition.MOVING_OR_REVERSE_NEUTRAL,true,false,false),
            components=ComponentPolicies(),microphoneGranted=false,directProviderVerified=false,audioAllowsReply=false)
        c.updateAccess(a);assertTrue(a.gleoReady);assertFalse(a.canCapture)
        val lease=requireNotNull(c.startGleoAnswer(owner,lesson))
        c.updateAccess(a.copy(gleoPermissionGeneration=2))
        assertNull(c.acceptGleoFinal(lease,"A city opened a library.",Capture.unverified(CapturePath.GLEO)){fail()})
        assertEquals(InputPhase.IDLE,c.snapshot().inputPhase)
    }
    @Test fun actualSuspendDiscardsUnsubmittedInputButKeepsAnAlreadyAcceptedAnswerForPersistence()=runBlocking {
        val first=question();val lease=requireNotNull(first.startAnswer());first.finishAnswer(lease.captureId)
        first.updateAccess(access().copy(power=PowerAvailability.SUSPENDED))
        first.updateAccess(access());assertNull(first.acceptFinal(lease.captureId,"Late",Capture.unverified(CapturePath.PARKED_STT)){fail()})
        val second=question();val accepted=requireNotNull(second.startAnswer());second.finishAnswer(accepted.captureId)
        val answer=second.acceptFinal(accepted.captureId,"A city opened a library.",Capture.unverified(CapturePath.PARKED_STT)){}
        second.updateAccess(access().copy(power=PowerAvailability.SUSPENDED))
        assertEquals(answer,second.snapshot().pendingAnswer);assertTrue(second.snapshot().access.persistenceAllowed)
        assertFalse(second.snapshot().access.canCapture);assertNull(second.tick(1_000_000))
    }
    @Test fun oneReplyRouteFocusCannotAuthorizeAnotherRouteOrStartItsClock()=runBlocking {
        val direct=access().copy(audioAllowsReply=true,directReplyFocusAllowed=false,gleoReplyFocusAllowed=true)
        assertTrue(direct.gleoAllowed);assertFalse(direct.parkedReplyAllowed);assertFalse(direct.canCapture)
        val gleo=access().copy(audioAllowsReply=true,directReplyFocusAllowed=true,gleoReplyFocusAllowed=false)
        assertTrue(gleo.parkedReplyAllowed);assertTrue(gleo.canCapture);assertTrue(gleo.gleoReady);assertFalse(gleo.gleoAllowed)
        val c=question();c.updateAccess(gleo)
        val output=OutputExpectation(UUID.randomUUID(),WaitKind.HINT_METHOD,owner,task,lesson,OutputRoute.GLEO)
        assertTrue(c.expectOutput(output)) // Focus may be requested after readiness admission.
        assertFalse(c.outputCompleted(output.outputId,owner,100,"fixture:actual-gleo-end"){_,_->})
        assertNull(c.tick(1_000_000))
    }

    @Test fun originalGenerationEndPreservesOnlyItsIssuedFactAfterRouteLossAndCannotGrantNewClock()=runBlocking {
        val c=question();val original=access().gleoPermissionGeneration
        val hint=OutputExpectation(UUID.randomUUID(),WaitKind.HINT_METHOD,owner,task,lesson,OutputRoute.GLEO)
        assertTrue(c.expectOutput(hint))
        c.updateAccess(access().copy(gleoPermissionGeneration=original+2))
        assertFalse(c.acceptsOutputObservation(hint.outputId,owner,null))
        assertFalse(c.acceptsOutputObservation(hint.outputId,owner,original+2))
        assertFalse(c.acceptsOutputObservation(UUID.randomUUID(),owner,original))
        assertFalse(c.acceptsOutputObservation(hint.outputId,owner.copy(privacyEpoch=1),original))
        assertTrue(c.acceptsOutputObservation(hint.outputId,owner,original))
        c.configureGleo(VoiceCapabilities()) // The old issued capability, not a new route, authenticated that output.
        var facts=0
        assertFalse(c.outputCompleted(hint.outputId,owner,100,"fixture:foreign-generation",original+2){_,_->fail()})
        assertFalse(c.outputCompleted(hint.outputId,owner,101,"fixture:original-generation",original){_,_->facts++})
        assertEquals(1,facts);assertTrue(requireNotNull(c.snapshot().dialogueProgress).hintMethodAnnounced)
        assertNull(c.snapshot().expectedOutput);assertNull(c.tick(1_000_000))
        assertFalse(c.acceptsOutputObservation(hint.outputId,owner,original))
        c.configureGleo(caps())
        val other=OutputExpectation(UUID.randomUUID(),WaitKind.HINT_METHOD,owner,task,lesson,OutputRoute.GLEO)
        assertTrue(c.expectOutput(other));assertTrue(c.fenceOwner(owner))
        assertFalse(c.acceptsOutputObservation(other.outputId,owner,original+2))
    }

}
