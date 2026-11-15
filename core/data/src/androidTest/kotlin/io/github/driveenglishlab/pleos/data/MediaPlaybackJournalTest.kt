package io.github.driveenglishlab.pleos.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaPlaybackJournalTest {
    private lateinit var db: LearningDatabase
    private lateinit var journal: MediaPlaybackJournal
    private lateinit var point: WirePersonalCheckpointInput
    private val owner = OwnerContext(UUID.randomUUID(), 0, 1, UUID.randomUUID())
    private val run = UUID.randomUUID()
    @Before fun setup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, LearningDatabase::class.java).build()
        db.learning().putOwner(LocalOwner(profileId = owner.profileId.toString(), privacyEpoch = 0, revision = 1, status = "active"))
        journal = MediaPlaybackJournal(context, db)
        val value = contractFixture("NextArticleResponse") { it["status"] == JsonPrimitive("selected") }
            .getValue("checkpoint").jsonObject.getValue("payload").jsonObject
        point = learningJson.decodeFromJsonElement(WirePersonalCheckpointInput.serializer(),
            JsonObject(value.filterKeys { it !in setOf("active_run_id", "main_coverage_status") }))
    }
    @After fun close() { db.close() }

    @Test fun durableObservationsHaveOneSequenceAndDoNotInventServerCheckpointCas() = runBlocking {
        journal.persist(owner, 1, point.copy(positionMs = 500), run, PlayedInterval(0, 500))
        journal.persist(owner, 1, point.copy(positionMs = 1_000), run, PlayedInterval(500, 1_000))
        val rows = db.learning().pendingSync(owner.profileId.toString(), 0)
        assertEquals(2, rows.size)
        val values = rows.map { learningJson.parseToJsonElement(it.payloadJson).jsonObject }
        assertTrue(values.all { it["kind"] == JsonPrimitive("playback_observation") })
        assertEquals(listOf(1L, 2L), values.map { it.getValue("payload").jsonObject.getValue("sequence_no").jsonPrimitive.long })
        assertTrue(values.all { it.getValue("payload").jsonObject["status"] == JsonPrimitive("observed") })
        assertEquals(1_000L, journal.position(owner, point.checkpointId)!!.positionMs)
        assertNull(db.learning().record(owner.profileId.toString(), 0, stableRecordId("server-record:${point.checkpointId}").toString()))
    }

    @Test fun failureRollsBackObservationSequenceAndPositionTogether() = runBlocking {
        try {
            journal.persist(owner, 1, point.copy(positionMs = requireNotNull(point.durationMs) + 1), run, PlayedInterval(0, 500))
            fail("Out-of-range checkpoint must fail.")
        } catch (_: IllegalArgumentException) { }
        assertTrue(db.learning().pendingSync(owner.profileId.toString(), 0).isEmpty())
        assertNull(journal.position(owner, point.checkpointId))
        journal.persist(owner, 1, point.copy(positionMs = 500), run, PlayedInterval(0, 500))
        val row = db.learning().pendingSync(owner.profileId.toString(), 0).single()
        assertEquals(1L, learningJson.parseToJsonElement(row.payloadJson).jsonObject.getValue("payload").jsonObject.getValue("sequence_no").jsonPrimitive.long)
    }

    @Test fun changedOwnerCannotWriteOldLearningOrCopyItToNewProfile() = runBlocking {
        db.learning().putOwner(LocalOwner(profileId = UUID.randomUUID().toString(), privacyEpoch = 0, revision = 2, status = "active"))
        try { journal.persist(owner, 1, point, run, null); fail("Old media owner must be rejected.") }
        catch (_: IllegalArgumentException) { }
        assertNull(journal.position(owner, point.checkpointId))
        assertTrue(db.learning().pendingSync(owner.profileId.toString(), 0).isEmpty())
    }
    private data class SegmentFixture(val repository:SegmentPlaybackRepository,val program:SegmentProgram,val snapshot:SegmentPlaybackSnapshot,
        val sourceJson:String,val source:WireSource6ContentRef6,val codecs:IncrementalContractJson)
    private suspend fun segmentFixture(count:Int):SegmentFixture {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val codecs=IncrementalContractJson(context);val parts=(0 until count).map{"Synthetic segment $it. "};val script=parts.joinToString("")
        var start=0
        val segments=parts.mapIndexed{i,text->val end=start+text.codePointCount(0,text.length);SpeechSegment(UUID.randomUUID(),i,UUID.randomUUID(),start,end,segmentSha256(text)).also{start=end}}
        val raw=SegmentManifest(UUID.randomUUID(),UUID.randomUUID(),segmentSha256(script),1,"0".repeat(64),SegmentLanguage.EN,segments)
        val manifest=raw.copy(manifestHash=segmentSha256(raw.canonicalPayload()))
        val content=PreparedContentRef(UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),UUID.randomUUID(),UUID.randomUUID(),manifest.scriptId,manifest.scriptHash,ListeningMode.ORIGINAL_FULL)
        val source=content.source6Wire();val sourceJson=codecs.encode("source6","ContentRef6",source,WireSource6ContentRef6.serializer())
        val port=object:SegmentCheckpointPort {
            override suspend fun cursor(write:SegmentCursorWrite):WireSource6PersonalReadCheckpoint?=throw AssertionError("No server call in local storage fixture")
            override suspend fun start(owner:OwnerContext,request:WireSource6PersonalPlaybackRunStart):WireSource6PersonalPlaybackRunReceipt?=throw AssertionError("No server call in local storage fixture")
            override suspend fun observation(owner:OwnerContext,eventId:UUID,value:WireSource6SegmentObservationInput6):Boolean=throw AssertionError("No server call in local storage fixture")
        }
        val repository=SegmentPlaybackRepository(context,db.segments(),port){scope->db.learning().currentOwner()?.let{it.profileId==scope.profileId.toString() && it.privacyEpoch==scope.privacyEpoch && it.revision==scope.revision && it.status=="active"}==true}
        val program=repository.program(owner,UUID.randomUUID(),sourceJson,codecs.encode("source6","SegmentManifest",manifest.toWireSegmentManifest(),WireSource6SegmentManifest.serializer()),script,SegmentPhase.MAIN,SegmentPurpose.MAIN)
        val cursor=PlaybackCursor6.Segment(manifest.scriptId,manifest.scriptHash,manifest.manifestId,1,manifest.manifestHash,SegmentPhase.MAIN,segments.first().segmentId,0,true,PauseReason.NONE)
        val run=SegmentRun(UUID.randomUUID(),1,UUID.randomUUID(),owner,program.selectionId,program.source,program.phase,program.purpose,manifest.scriptId,manifest.scriptHash,
            manifest.manifestId,1,manifest.manifestHash,segments.first().segmentId,0,segments.first().textHash,UUID.randomUUID())
        val snapshot=SegmentPlaybackSnapshot(program,cursor,run.runId,1,SequentialCompletion.start(program,run.runId,0),run,true)
        repository.journal.save(snapshot)
        // Explicit test-owned authoritative receipt; not a real server or synthesis success.
        val checkpointId=UUID.randomUUID().toString()
        val request=WireSource6PersonalPlaybackRunStart("6",run.requestId.toString(),owner.source6Profile(),checkpointId,1,null)
        val wireRun=WireSource6SegmentRunRecord6("segment",run.runId.toString(),run.generation,run.requestId.toString(),owner.profileId.toString(),owner.privacyEpoch,source,
            "main",manifest.scriptId.toString(),manifest.scriptHash,manifest.manifestId.toString(),1,manifest.manifestHash,run.segmentId.toString(),0,"main",run.outputLeaseId.toString())
        val checkpoint=WireSource6PersonalReadCheckpoint(checkpointId,"checkpoint",2,WireSource6PersonalCheckpointRecord(checkpointId,source,UUID.randomUUID().toString(),program.selectionId.toString(),run.runId.toString(),"unknown",cursor.toWireSegmentCursor()))
        val receipt=WireSource6PersonalPlaybackRunReceipt("6",run.requestId.toString(),owner.source6Profile(),wireRun,checkpoint)
        val input=codecs.encode("source6","PersonalPlaybackRunStart",request,WireSource6PersonalPlaybackRunStart.serializer())
        val operation=LocalSegmentOperation(run.requestId.toString(),owner.profileId.toString(),0,1,program.selectionId.toString(),run.runId.toString(),"start",input,LearningJson.hash(input),"pending",false,null)
        db.segments().enqueue(owner,operation)
        db.segments().acknowledge(owner,operation,codecs.encode("source6","PersonalPlaybackRunReceipt",receipt,WireSource6PersonalPlaybackRunReceipt.serializer()))
        return SegmentFixture(repository,program,snapshot,sourceJson,source,codecs)
    }
    @Test fun validDoneWithCleanupHoldStoresWrapperAndOriginalRunThenRestoresWithoutRetaggingOrChangingPublicProof()=runBlocking {
        for(count in listOf(1,2)) {
            val f=segmentFixture(count);val original=requireNotNull(f.snapshot.activeRequest)
            val event=SegmentSpeechEvent(UUID.randomUUID(),original,SegmentSpeechEventKind.COMPLETED,"synthetic-actual-instance-end",OutputContinuationState.BLOCKED)
            val completed=f.snapshot.ledger.completed(event);val english=completed.complete
            val ledger=completed.copy(englishMainCompletionRecorded=english)
            val next=f.program.manifest.segments.getOrNull(1)
            val continuation=CompletionContinuation1(event.eventId,original,next?.order,next==null,OutputContinuationState.BLOCKED,"synthetic-release-failed",true)
            val saved=f.snapshot.copy(activeRequest=null,bindingAcknowledged=false,ledger=ledger,continuation=continuation,
                cursor=next?.let{f.snapshot.cursor.copy(segmentId=it.segmentId,segmentIndex=it.order,pauseReason=PauseReason.SYSTEM)}?:f.snapshot.cursor.copy(pauseReason=PauseReason.SYSTEM))
            f.repository.journal.observed(event,saved,english)
            val row=db.segments().state(owner.profileId.toString(),0,f.program.selectionId.toString(),"main","main","segment")!!
            val wrapper=learningJson.parseToJsonElement(row.ledgerJson!!).jsonObject
            assertEquals(setOf("local_state_version","ledger","continuation"),wrapper.keys);assertEquals(JsonPrimitive(2),wrapper["local_state_version"])
            assertEquals(17,wrapper.getValue("continuation").jsonObject.getValue("completed_run").jsonObject.size)
            val originalEvent=db.segments().operation(event.eventId.toString())!!
            assertFalse(originalEvent.payloadJson.contains("output_continuation"));assertFalse(originalEvent.payloadJson.contains("local_state_version"))
            val fresh=owner.copy(presentationToken=UUID.randomUUID())
            val program=f.repository.program(fresh,f.program.selectionId,f.sourceJson,f.codecs.encode("source6","SegmentManifest",f.program.manifest.toWireSegmentManifest(),WireSource6SegmentManifest.serializer()),f.program.script,SegmentPhase.MAIN,SegmentPurpose.MAIN)
            val restored=requireNotNull(f.repository.restore(program))
            assertEquals(original,restored.continuation!!.completedRun);assertEquals(owner.presentationToken,restored.continuation!!.completedRun.owner.presentationToken)
            assertNotEquals(fresh.presentationToken,restored.continuation!!.completedRun.owner.presentationToken)
            assertEquals(1,restored.ledger.nextRequiredIndex);assertEquals(OutputContinuationState.BLOCKED,restored.continuation!!.state)
            assertEquals(row,db.segments().state(owner.profileId.toString(),0,f.program.selectionId.toString(),"main","main","segment"))
            if(english) {
                val proof=db.segments().completion(owner.profileId.toString(),0,original.runId.toString())!!
                assertEquals(encodeSegmentLedger(ledger),proof.proofJson);assertFalse(proof.proofJson.contains("local_state_version"))
                assertTrue(f.repository.journal.phaseAcknowledged(restored,event.eventId)) // explicit synthetic checkpoint-ACK admission
                val reduced=db.segments().state(owner.profileId.toString(),0,f.program.selectionId.toString(),"main","main","segment")!!
                assertFalse(decodeLocalSegmentState(reduced.ledgerJson!!,program).second!!.phaseEndPending)
                assertFalse(db.segments().acknowledgePhaseContinuation(fresh,row,event.eventId.toString(),reduced.ledgerJson!!)) // old localVersion cannot clear another state
                assertEquals(proof,db.segments().completion(owner.profileId.toString(),0,original.runId.toString()))
                assertEquals(originalEvent,db.segments().operation(event.eventId.toString()))
                val explicitlyMoved=restored.copy(ledger=restored.ledger.manuallyMoved(),
                    continuation=restored.continuation!!.copy(nextSegmentIndex=0,phaseEndPending=false,requiresExplicitResume=true))
                f.repository.journal.save(explicitlyMoved)
                val moved=requireNotNull(f.repository.restore(program))
                assertEquals(0,moved.continuation!!.nextSegmentIndex);assertFalse(moved.continuation!!.phaseEndPending)
                assertEquals(original,moved.continuation!!.completedRun);assertEquals(OutputContinuationState.BLOCKED,moved.continuation!!.state)
                assertTrue(moved.ledger.englishMainCompletionRecorded);assertEquals(proof,db.segments().completion(owner.profileId.toString(),0,original.runId.toString()))
            }
        }
    }
    @Test fun coldPhaseAckReconciliationReducesOnlyExactNewerCheckpointWithoutClearingCleanupHoldOrReplayingCompletion()=runBlocking {
        val f=segmentFixture(1);val run=f.snapshot.activeRequest!!
        val event=SegmentSpeechEvent(UUID.randomUUID(),run,SegmentSpeechEventKind.COMPLETED,"synthetic-done-before-crash",OutputContinuationState.UNKNOWN)
        val ledger=f.snapshot.ledger.completed(event).copy(englishMainCompletionRecorded=true)
        f.repository.journal.observed(event,f.snapshot.copy(activeRequest=null,bindingAcknowledged=false,ledger=ledger,
            continuation=CompletionContinuation1(event.eventId,run,null,true,OutputContinuationState.UNKNOWN,"synthetic-cleanup-unknown",true)),true)
        val operation=db.segments().operation(run.requestId.toString())!!
        val start=f.codecs.decodeSource6("PersonalPlaybackRunReceipt",operation.receiptJson!!,WireSource6PersonalPlaybackRunReceipt.serializer())
        val original=start.checkpoint
        val cursor=(original.payload.cursor as WireSource6SegmentCursor6).copy(phase="article_offer",segmentId=null,segmentIndex=null,playIntent=false)
        val next=original.copy(version=original.version+1,payload=original.payload.copy(cursor=cursor))
        assertFalse(f.repository.acknowledgeRestoredPhase(f.program,next.copy(recordId=UUID.randomUUID().toString())))
        assertFalse(f.repository.acknowledgeRestoredPhase(f.program,next.copy(version=original.version)))
        val proof=db.segments().completion(owner.profileId.toString(),0,run.runId.toString())!!
        val originalEvent=db.segments().operation(event.eventId.toString())!!
        assertTrue(f.repository.acknowledgeRestoredPhase(f.program,next))
        val restored=f.repository.restore(f.program)!!
        assertFalse(restored.continuation!!.phaseEndPending);assertEquals(OutputContinuationState.UNKNOWN,restored.continuation!!.state)
        assertTrue(restored.continuation!!.requiresExplicitResume);assertEquals(run,restored.continuation!!.completedRun)
        assertEquals(proof,db.segments().completion(owner.profileId.toString(),0,run.runId.toString()))
        assertEquals(originalEvent,db.segments().operation(event.eventId.toString()))
        assertFalse(f.repository.acknowledgeRestoredPhase(f.program,next))
    }
    @Test fun bareLegacyLedgerReadsWithoutRewriteAndMissingOrIsolatedEvidenceCannotRestoreNewContinuation()=runBlocking {
        val f=segmentFixture(1)
        val row=db.segments().state(owner.profileId.toString(),0,f.program.selectionId.toString(),"main","main","segment")!!
        val bare=encodeSegmentLedger(f.snapshot.ledger)
        db.segments().commit(owner,row.copy(ledgerJson=bare,localVersion=row.localVersion+1),emptyList())
        assertNull(f.repository.restore(f.program)!!.continuation)
        assertEquals(bare,db.segments().state(owner.profileId.toString(),0,f.program.selectionId.toString(),"main","main","segment")!!.ledgerJson)
        val run=f.snapshot.activeRequest!!;val event=SegmentSpeechEvent(UUID.randomUUID(),run,SegmentSpeechEventKind.COMPLETED,"synthetic-done",OutputContinuationState.UNKNOWN)
        val ledger=f.snapshot.ledger.completed(event).copy(englishMainCompletionRecorded=true)
        val saved=f.snapshot.copy(activeRequest=null,bindingAcknowledged=false,ledger=ledger,continuation=CompletionContinuation1(event.eventId,run,null,true,OutputContinuationState.UNKNOWN,"synthetic-cleanup",true))
        f.repository.journal.observed(event,saved,true);db.segments().isolate(owner.profileId.toString())
        try{f.repository.restore(f.program);fail("Isolated original completion cannot become a current continuation")}catch(_:IllegalArgumentException){}
        assertNotNull(db.segments().completion(owner.profileId.toString(),0,run.runId.toString()))
    }

}
