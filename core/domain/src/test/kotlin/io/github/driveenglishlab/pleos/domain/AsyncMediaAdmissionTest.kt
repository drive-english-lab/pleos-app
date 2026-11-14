package io.github.driveenglishlab.pleos.domain

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Reducer-level asynchronous admission regression. No real manifest, TTS, vehicle
 * permission or audio output is exercised; the held callback models that boundary. */
class AsyncMediaAdmissionTest {
    private val owner=OwnerContext(UUID.randomUUID(),0,1,UUID.randomUUID())
    private fun access()=AccessState(SafetyState(VehiclePosition.PARKED,true,true,true),WindowState(true,true),
        PowerAvailability.ALLOWED,true,true,true,true,true)
    private suspend fun coordinator()=SessionCoordinator().also {
        it.activate(owner,null);it.updateAccess(access());it.setMediaConnected(true)
    }
    private data class Admission(val ticket:SessionOperationTicket,val selectionId:UUID)
    private suspend fun admit(coordinator:SessionCoordinator,prepared:Boolean):Admission {
        val ticket=requireNotNull(coordinator.beginOperation(SessionOperationKind.SELECTION,owner))
        val selected=UUID.randomUUID()
        if(prepared) {
            val script=UUID.randomUUID();val hash="a".repeat(64)
            val ref=PreparedContentRef(UUID.randomUUID(),UUID.randomUUID(),"b".repeat(64),UUID.randomUUID(),UUID.randomUUID(),script,hash,ListeningMode.ORIGINAL_FULL)
            val cursor=PlaybackCursor6.Segment(script,hash,UUID.randomUUID(),1,"c".repeat(64),SegmentPhase.MAIN,UUID.randomUUID(),0,false,PauseReason.USER)
            val work=PreparedListeningWorkRef(UUID.randomUUID(),selected,UUID.randomUUID(),ref,cursor,1)
            assertTrue(coordinator.admitPreparedListening(RepositoryCompletion(ticket,work),true))
        }else {
            val ref=ContentRef(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),ListeningMode.ORIGINAL_FULL)
            val work=ListeningWorkRef(UUID.randomUUID(),selected,UUID.randomUUID(),ref,ListeningStage.MAIN,UUID.randomUUID(),60_000,null,1)
            assertTrue(coordinator.admitListening(RepositoryCompletion(ticket,work),0,true))
        }
        return Admission(ticket,selected)
    }
    private suspend fun callback(coordinator:SessionCoordinator,admission:Admission)=coordinator.requestMediaIntent(owner,PlaybackIntent.PLAY,
        expectedNavigationGeneration=admission.ticket.navigationGeneration,expectedIntentGeneration=admission.ticket.intentGeneration,
        expectedSelectionId=admission.selectionId)

    @Test fun originalAdmissionMayStartFromStoppedButStopPauseNavigationAndNewSelectionBeatDelayedPlay()=runBlocking {
        for(prepared in listOf(false,true)) {
            val first=coordinator();val firstAdmission=admit(first,prepared)
            assertEquals(PlaybackIntent.STOPPED,first.snapshot().intent)
            assertTrue("Matching first-selection ticket must be allowed from STOPPED; prepared=$prepared",callback(first,firstAdmission))
            assertEquals(PlaybackIntent.PLAY,first.snapshot().intent)

            for(interruption in listOf("stop","pause","navigation","selection","pending_choice")) {
                val current=coordinator();val admission=admit(current,prepared)
                val entered=CompletableDeferred<Unit>();val manifestReady=CompletableDeferred<Unit>()
                val pending=async { entered.complete(Unit);manifestReady.await();callback(current,admission) }
                entered.await()
                when(interruption) {
                    // STOP starts from STOPPED on purpose: checking only the intent value,
                    // instead of its generation, would incorrectly let this callback play.
                    "stop"->assertTrue(current.requestMediaIntent(owner,PlaybackIntent.STOPPED))
                    "pause"->assertTrue(current.requestMediaIntent(owner,PlaybackIntent.USER_PAUSED))
                    "navigation"->assertTrue(current.navigate(owner,SessionScreen.HISTORY))
                    "selection"->admit(current,prepared)
                    "pending_choice"->{
                        val previous=current.snapshot()
                        val obsolete=requireNotNull(current.beginOperation(SessionOperationKind.SELECTION,owner))
                        assertTrue(current.invalidateMediaActivation(owner))
                        val fenced=current.snapshot()
                        assertEquals(previous.intent,fenced.intent)
                        assertEquals(previous.navigationGeneration,fenced.navigationGeneration)
                        assertEquals(previous.listening,fenced.listening);assertEquals(previous.preparedListening,fenced.preparedListening)
                        assertEquals(previous.intentGeneration+1,fenced.intentGeneration)
                        assertFalse(fenced.activeOperations.contains(SessionOperationKind.SELECTION))
                        assertFalse(current.finishOperation(obsolete))
                    }
                }
                val before=current.snapshot()
                if(interruption=="selection") {
                    assertEquals(admission.ticket.navigationGeneration,before.navigationGeneration)
                    assertEquals(admission.ticket.intentGeneration,before.intentGeneration)
                    assertNotEquals(admission.selectionId,before.preparedListening?.selectionId?:before.listening?.selectionId)
                }
                manifestReady.complete(Unit)
                assertFalse("Old manifest callback must lose to $interruption; prepared=$prepared",pending.await())
                val after=current.snapshot()
                assertEquals(before.intent,after.intent)
                assertEquals(if(interruption=="pause")PlaybackIntent.USER_PAUSED else PlaybackIntent.STOPPED,after.intent)
                assertEquals(before.intentGeneration,after.intentGeneration)
                assertEquals(before.navigationGeneration,after.navigationGeneration)
                assertEquals(before.screen,after.screen)
                assertEquals(before.listening,after.listening);assertEquals(before.preparedListening,after.preparedListening)
                if(interruption=="pending_choice") {
                    // The old automatic grant is revoked before the new choice commits,
                    // while a later ticket from this explicit input can still start.
                    val fresh=admit(current,prepared)
                    assertTrue(fresh.ticket.intentGeneration>admission.ticket.intentGeneration)
                    assertTrue(callback(current,fresh))
                    assertEquals(PlaybackIntent.PLAY,current.snapshot().intent)
                }
            }
        }
    }
    private data class ObservedProgram(val admission:Admission,val snapshot:SegmentPlaybackSnapshot)
    private suspend fun observedProgram(current:SessionCoordinator):ObservedProgram {
        val script="A synthetic sentence."
        val scriptId=UUID.randomUUID();val scriptHash=segmentSha256(script)
        val segment=SpeechSegment(UUID.randomUUID(),0,UUID.randomUUID(),0,script.codePointCount(0,script.length),scriptHash)
        val initial=SegmentManifest(UUID.randomUUID(),scriptId,scriptHash,1,"0".repeat(64),SegmentLanguage.EN,listOf(segment))
        val manifest=initial.copy(manifestHash=segmentSha256(initial.canonicalPayload()))
        val ref=PreparedContentRef(UUID.randomUUID(),UUID.randomUUID(),"b".repeat(64),UUID.randomUUID(),UUID.randomUUID(),scriptId,scriptHash,ListeningMode.ORIGINAL_FULL)
        val selection=UUID.randomUUID();val program=SegmentProgram(owner,selection,ref.segmentSourceKey(),manifest,script,SegmentPhase.MAIN,SegmentPurpose.MAIN)
        val cursor=PlaybackCursor6.Segment(scriptId,scriptHash,manifest.manifestId,1,manifest.manifestHash,SegmentPhase.MAIN,segment.segmentId,0,true,PauseReason.NONE)
        val ticket=requireNotNull(current.beginOperation(SessionOperationKind.SELECTION,owner))
        val work=PreparedListeningWorkRef(UUID.randomUUID(),selection,UUID.randomUUID(),ref,cursor,1)
        assertTrue(current.admitPreparedListening(RepositoryCompletion(ticket,work),true))
        val run=UUID.randomUUID()
        val observation=SegmentPlaybackSnapshot(program,cursor,run,1,SequentialCompletion.start(program,run,0),bindingAcknowledged=true)
        return ObservedProgram(Admission(ticket,selection),observation)
    }
    @Test fun segmentObservationCannotCreateOrReplaceAnExplicitPlaybackIntent()=runBlocking {
        val current=coordinator();val fixture=observedProgram(current)
        val observation=fixture.snapshot;val run=observation.runId;val cursor=observation.cursor
        // Attaching a stored cursor is data observation, never a new real-time play grant.
        assertEquals(PlaybackIntent.STOPPED,current.snapshot().intent)
        assertTrue(current.observeSegment(observation,true))
        assertEquals(PlaybackIntent.STOPPED,current.snapshot().intent)
        assertFalse(current.snapshot().mediaPlaying)
        assertTrue(current.requestMediaIntent(owner,PlaybackIntent.PLAY))
        assertTrue(current.observeSegment(observation,true,run))
        assertEquals(PlaybackIntent.PLAY,current.snapshot().intent)
        for(intent in listOf(PlaybackIntent.USER_PAUSED,PlaybackIntent.STOPPED)) {
            assertTrue(current.requestMediaIntent(owner,intent))
            val before=current.snapshot()
            assertTrue(current.observeSegment(observation,true,run))
            val after=current.snapshot()
            assertEquals(intent,after.intent);assertEquals(before.intentGeneration,after.intentGeneration)
            assertFalse(after.mediaPlaying)
        }
        // An older paused runtime callback likewise cannot undo a newer explicit play.
        assertTrue(current.requestMediaIntent(owner,PlaybackIntent.PLAY))
        val generation=current.snapshot().intentGeneration
        val olderPaused=observation.copy(cursor=cursor.copy(playIntent=false,pauseReason=PauseReason.USER))
        assertTrue(current.observeSegment(olderPaused,false,run))
        assertEquals(PlaybackIntent.PLAY,current.snapshot().intent)
        assertEquals(generation,current.snapshot().intentGeneration)
    }

    @Test fun rightsCheckAfterAtomicPlayAdmissionCannotOutliveANewChoiceStopOrPause()=runBlocking {
        for(interruption in listOf("pending_choice","stop","pause")) {
            val current=coordinator();val fixture=observedProgram(current)
            // This is the real atomic Root-play boundary, before the subsequent rights GET.
            assertTrue(callback(current,fixture.admission))
            val capturedGeneration=current.snapshot().intentGeneration
            val entered=CompletableDeferred<Unit>();val verified=CompletableDeferred<Unit>()
            var verifications=0
            val permission=async {
                current.segmentOutputPermission(fixture.snapshot.program,capturedGeneration) {
                    verifications++;entered.complete(Unit);verified.await();true
                }
            }
            withTimeout(3_000){entered.await()}
            val before=current.snapshot()
            when(interruption) {
                "pending_choice"->assertTrue(current.invalidateMediaActivation(owner))
                "stop"->current.stop()
                "pause"->assertTrue(current.requestMediaIntent(owner,PlaybackIntent.USER_PAUSED))
            }
            val interrupted=current.snapshot()
            if(interruption=="pending_choice") {
                assertEquals(before.intent,interrupted.intent)
                assertEquals(before.navigationGeneration,interrupted.navigationGeneration)
                assertEquals(before.preparedListening,interrupted.preparedListening)
            }
            verified.complete(Unit)
            assertFalse("True rights result cannot revive a superseded output authority: $interruption",permission.await())
            assertEquals(1,verifications)
            assertEquals(interrupted.intent,current.snapshot().intent)
            assertEquals(interrupted.intentGeneration,current.snapshot().intentGeneration)
            // Reusing the old captured generation is denied even before another rights read.
            assertFalse(current.segmentOutputPermission(fixture.snapshot.program,capturedGeneration){verifications++;true})
            assertEquals(1,verifications)
            // A distinct, explicit play obtains new authority; it is not inherited from the GET.
            assertTrue(current.requestMediaIntent(owner,PlaybackIntent.PLAY))
            val fresh=current.snapshot().intentGeneration
            assertTrue(current.segmentOutputPermission(fixture.snapshot.program,fresh){verifications++;true})
            assertEquals(2,verifications)
        }
    }

}
