package io.github.driveenglishlab.pleos.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic SDK events/server ACK/permission/cleanup proofs only. Exercises the real runtime,
 * never an actual SDK instance, news rights, output grant, audio focus, vehicle, Auth or DB. */
@RunWith(AndroidJUnit4::class)
class PleosTtsContinuationTest {
    private fun program(count:Int):SegmentProgram {
        val owner=OwnerContext(UUID.randomUUID(),0,1,UUID.randomUUID());val chunks=(0 until count).map{"Sentence $it. "};val text=chunks.joinToString("")
        var start=0
        val segments=chunks.mapIndexed{i,t->val end=start+t.codePointCount(0,t.length);SpeechSegment(UUID.randomUUID(),i,UUID.randomUUID(),start,end,segmentSha256(t)).also{start=end}}
        val raw=SegmentManifest(UUID.randomUUID(),UUID.randomUUID(),segmentSha256(text),1,"0".repeat(64),SegmentLanguage.EN,segments)
        val manifest=raw.copy(manifestHash=segmentSha256(raw.canonicalPayload()))
        return SegmentProgram(owner,UUID.randomUUID(),SegmentSourceKey("prepared_components","a".repeat(64)),manifest,text,SegmentPhase.MAIN,SegmentPurpose.MAIN)
    }
    private class Speech:SegmentSpeechPort {
        override val events=MutableSharedFlow<SegmentSpeechEvent>(extraBufferCapacity=8)
        val requests=mutableListOf<SegmentRun>();val stopped=mutableListOf<SegmentRun>()
        override suspend fun request(run:SegmentRun,text:String,language:SegmentLanguage):SegmentSpeechAdmission {requests+=run;return SegmentSpeechAdmission.Preparing}
        override suspend fun stop(run:SegmentRun){stopped+=run}
    }
    private class Journal:SegmentJournalPort {
        var last:SegmentPlaybackSnapshot?=null
        val observed=mutableListOf<SegmentSpeechEvent>();var english=0;var phaseAcks=0
        override suspend fun save(snapshot:SegmentPlaybackSnapshot){last=snapshot}
        override suspend fun prepareOutput(snapshot:SegmentPlaybackSnapshot)=snapshot.activeRequest // explicitly synthetic authoritative ACK
        override suspend fun observed(event:SegmentSpeechEvent,snapshot:SegmentPlaybackSnapshot,recordEnglishCompletion:Boolean){observed+=event;last=snapshot;if(recordEnglishCompletion)english++}
        override suspend fun phaseAcknowledged(snapshot:SegmentPlaybackSnapshot,eventId:UUID):Boolean {phaseAcks++;return snapshot.continuation?.completedEventId==eventId}
    }
    private suspend fun await(runtime:SegmentPlaybackCoordinator,predicate:(SegmentRuntimeState)->Boolean)=withTimeout(3_000){runtime.state.first(predicate)}

    @Test fun nonfinalDoneIsDurableWhileCleanupBlocksAndExplicitRecoveryStartsOnlyNextUnfinishedSegment()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);val speech=Speech();val journal=Journal();val p=program(2)
        val runtime=withContext(Dispatchers.Main.immediate){SegmentPlaybackCoordinator(speech,journal,SegmentOutputLease{_,_->true},scope){true}}
        var recovery=OutputContinuationState.UNKNOWN
        runtime.configureCompletion({e->CompletionDisposition1(true,UUID.randomUUID(),0,e.outputContinuation?:OutputContinuationState.UNKNOWN,"synthetic-completion-proof")},
            {recovery},{SegmentContinuationAuthority(it.owner,it.selectionId,1)})
        try {
            assertTrue(runtime.attach(p));assertTrue(runtime.play());val first=speech.requests.single()
            val done=SegmentSpeechEvent(UUID.randomUUID(),first,SegmentSpeechEventKind.COMPLETED,"synthetic-native-done",OutputContinuationState.BLOCKED)
            speech.events.emit(done);val held=await(runtime){it.reason=="synthetic-completion-proof"}.snapshot!!
            assertEquals(1,held.ledger.nextRequiredIndex);assertEquals(1,held.cursor.segmentIndex)
            assertEquals(first,held.continuation!!.completedRun);assertTrue(held.continuation!!.requiresExplicitResume)
            assertEquals(SegmentSpeechEventKind.COMPLETED,journal.observed.single().kind);assertEquals(0,journal.english)
            assertFalse(runtime.play());assertEquals(1,speech.requests.size)
            recovery=OutputContinuationState.READY
            assertTrue(runtime.play());assertEquals(2,speech.requests.size);assertEquals(1,speech.requests.last().segmentIndex)
            assertEquals(1,runtime.state.value.snapshot!!.ledger.nextRequiredIndex)
            speech.events.emit(done);delay(30);assertEquals(1,journal.observed.size) // duplicate original event never retags the new request
        } finally {runtime.close();scope.cancel()}
    }
    @Test fun finalDoneAndEnglishCompletionSurviveUnknownCleanupAndPhaseAckReducesOnlyAfterExplicitRecovery()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);val speech=Speech();val journal=Journal();val p=program(1)
        val runtime=withContext(Dispatchers.Main.immediate){SegmentPlaybackCoordinator(speech,journal,SegmentOutputLease{_,_->true},scope){true}}
        var recovery=OutputContinuationState.UNKNOWN;var intent=1L
        runtime.configureCompletion({e->CompletionDisposition1(true,UUID.randomUUID(),0,e.outputContinuation?:OutputContinuationState.UNKNOWN,"synthetic-cleanup-unknown")},
            {recovery},{SegmentContinuationAuthority(it.owner,it.selectionId,intent)})
        val phases=java.util.concurrent.CopyOnWriteArrayList<SegmentRuntimeEvent.PhaseCompleted>()
        val listener=scope.launch{runtime.events.collect{if(it is SegmentRuntimeEvent.PhaseCompleted)phases+=it}}
        try {
            assertTrue(runtime.attach(p));assertTrue(runtime.play());val run=speech.requests.single()
            speech.events.emit(SegmentSpeechEvent(UUID.randomUUID(),run,SegmentSpeechEventKind.COMPLETED,"synthetic-done",OutputContinuationState.UNKNOWN))
            val held=await(runtime){it.snapshot?.continuation?.phaseEndPending==true}.snapshot!!
            assertTrue(held.ledger.complete);assertTrue(held.ledger.englishMainCompletionRecorded);assertEquals(1,journal.english)
            assertTrue(phases.isEmpty());assertFalse(runtime.play());assertEquals(1,speech.requests.size)
            // A different process has no public cleanup proof. Fresh runtime/lease readiness is not recovery.
            val coldSpeech=Speech();val coldJournal=Journal()
            val cold=withContext(Dispatchers.Main.immediate){SegmentPlaybackCoordinator(coldSpeech,coldJournal,SegmentOutputLease{_,_->true},scope){true}}
            cold.configureCompletion({CompletionDisposition1.unavailable("synthetic-cold-no-binding")},{OutputContinuationState.UNKNOWN},{null})
            assertTrue(cold.attach(p,saved=held.cursor,retained=held));assertFalse(cold.play());assertTrue(coldSpeech.requests.isEmpty());cold.close()
            recovery=OutputContinuationState.READY;intent=2
            assertTrue(runtime.play());withTimeout(3_000){while(phases.isEmpty())delay(5)}
            assertEquals(run,phases.single().run);assertEquals(2L,phases.single().authority!!.intentGeneration)
            assertEquals(1,speech.requests.size);assertEquals(1,journal.english)
            assertTrue(runtime.phaseAcknowledged(run));assertEquals(1,journal.phaseAcks)
            assertFalse(runtime.state.value.snapshot!!.continuation!!.phaseEndPending);assertFalse(runtime.play())
        } finally {listener.cancel();runtime.close();scope.cancel()}
    }
    @Test fun explicitPreviousAfterFinalDonePreservesCleanupHoldButDoesNotReissuePhaseEnd()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);val speech=Speech();val journal=Journal();val p=program(2)
        val runtime=withContext(Dispatchers.Main.immediate){SegmentPlaybackCoordinator(speech,journal,SegmentOutputLease{_,_->true},scope){true}}
        var recovery=OutputContinuationState.UNKNOWN
        runtime.configureCompletion({e->CompletionDisposition1(true,UUID.randomUUID(),0,e.outputContinuation?:OutputContinuationState.UNKNOWN,"synthetic-cleanup-hold")},
            {recovery},{SegmentContinuationAuthority(it.owner,it.selectionId,2)})
        val phases=java.util.concurrent.CopyOnWriteArrayList<SegmentRuntimeEvent.PhaseCompleted>()
        val listener=scope.launch{runtime.events.collect{if(it is SegmentRuntimeEvent.PhaseCompleted)phases+=it}}
        try {
            assertTrue(runtime.attach(p));assertTrue(runtime.play());val first=speech.requests.single()
            speech.events.emit(SegmentSpeechEvent(UUID.randomUUID(),first,SegmentSpeechEventKind.COMPLETED,"synthetic-first-done",OutputContinuationState.READY))
            withTimeout(3_000){while(speech.requests.size<2)delay(5)}
            val last=speech.requests.last()
            speech.events.emit(SegmentSpeechEvent(UUID.randomUUID(),last,SegmentSpeechEventKind.COMPLETED,"synthetic-final-done",OutputContinuationState.BLOCKED))
            await(runtime){it.snapshot?.continuation?.phaseEndPending==true}
            assertTrue(runtime.move(-1));val moved=runtime.state.value.snapshot!!
            assertEquals(0,moved.cursor.segmentIndex);assertFalse(moved.continuation!!.phaseEndPending)
            assertEquals(last,moved.continuation!!.completedRun);assertTrue(moved.ledger.englishMainCompletionRecorded)
            assertFalse(runtime.play());assertEquals(2,speech.requests.size)
            recovery=OutputContinuationState.READY
            assertTrue(runtime.play());assertEquals(0,speech.requests.last().segmentIndex)
            assertEquals(3,speech.requests.size);assertTrue(phases.isEmpty());assertEquals(1,journal.english)
        } finally {listener.cancel();runtime.close();scope.cancel()}
    }
    @Test fun missingProductCompletionGuardNeverTurnsAnSdkClaimIntoCompletionOrEnglishCoverage()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);val speech=Speech();val journal=Journal();val p=program(1)
        val runtime=withContext(Dispatchers.Main.immediate){SegmentPlaybackCoordinator(speech,journal,SegmentOutputLease{_,_->true},scope){true}}
        try {
            assertTrue(runtime.attach(p));assertTrue(runtime.play());val run=speech.requests.single()
            speech.events.emit(SegmentSpeechEvent(UUID.randomUUID(),run,SegmentSpeechEventKind.COMPLETED,"synthetic-unverified-end",OutputContinuationState.READY))
            await(runtime){it.status==SegmentRuntimeStatus.UNAVAILABLE}
            assertEquals(0,journal.english);assertEquals(0,runtime.state.value.snapshot!!.ledger.nextRequiredIndex)
            assertEquals(SegmentSpeechEventKind.UNKNOWN,journal.observed.single().kind)
            withTimeout(3_000){while(!speech.stopped.contains(run))delay(5)}
        } finally {runtime.close();scope.cancel()}
    }
}
