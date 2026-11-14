package io.github.driveenglishlab.pleos.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic strict HTTP/Room boundaries; these fixtures do not grant production capability/TTS. */
@RunWith(AndroidJUnit4::class)
class ReviewNext3RepositoryTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private class Fixture(val context:android.content.Context):AutoCloseable {
        val db=Room.inMemoryDatabaseBuilder(context,LearningDatabase::class.java).build()
        val codecs=IncrementalContractJson(context)
        var elapsed=1_000L;val started=Instant.parse("2026-11-15T00:00:00Z");var now=started
        lateinit var call:LearningCallScope
        val requests=mutableListOf<String>();var originalReady=false;var loseFirst=false;var observedState="pending"
        var capabilities:Set<String> = setOf("pending_original_v1");var continueState="pending";var alternativeResumed=false;var alternativeIsParent=false
        var dropChildBeforeAdmission=false;var missingCode="review_operation_not_admitted"
        val observations=mutableListOf<Boolean>()
        val readQueries=mutableListOf<Map<String,String>>()
        var origin:String?="https://next-observation.fixture"
        var mutateRead:(LearningHttpResponse)->LearningHttpResponse={it}
        var beforeReadReply:suspend()->Unit={}
        fun readHeaders(result:String="found")=mapOf("Content-Type" to "application/json",
            "X-Pleos-Observation-Version" to "1","X-Pleos-Observation-Backend-Revision" to "next-observation1:fixture",
            "X-Pleos-Observation-Result" to result,"X-Pleos-Review-Capabilities" to capabilities.joinToString(","))
        var beforeReply:suspend()->Unit={}
        private val admissions=mutableMapOf<String,WireReview3NextAdmissionReceipt3>()
        private val held=mutableSetOf<String>()
        private val originalComponentId=UUID.randomUUID().toString()
        val targetArticle=UUID.randomUUID().toString();val targetSource=UUID.randomUUID().toString()
        val transport=object:LearningHttpTransport {
            override val configuredOrigin:String? get()=origin
            override suspend fun exchange(method:String,path:String,query:Map<String,String>,headers:Map<String,String>,body:ByteArray?):LearningHttpResponse {
                val observation=method=="GET"
                observations+=observation
                val text=if(observation) {
                    check(path.startsWith("/v2/listening/next/observations/") && body==null)
                    check("Idempotency-Key" !in headers && "X-Pleos-Observation-Only" !in headers)
                    check(query.keys==setOf("profile_id","privacy_epoch","review_contract_version","request_hash"))
                    check(query["profile_id"]==call.owner.profileId.toString() && query["privacy_epoch"]==call.owner.privacyEpoch.toString())
                    readQueries+=query
                    val operation=requireNotNull(db.review().operation(path.substringAfterLast('/')))
                    check(query["request_hash"]==operation.payloadHash)
                    val expectedVersion=if(operation.kind=="review3_next")"3" else "2"
                    check(query["review_contract_version"]==expectedVersion)
                    if(expectedVersion=="2") {
                        val row=requireNotNull(db.serviceIntents().intent(operation.operationId))
                        val response=buildJsonObject {
                            val input=codecs.parse("review2",operation.payloadJson).jsonObject
                            put("schema_version","6");put("review_contract_version",2);put("operation_id",operation.operationId)
                            put("profile_context",input.getValue("profile_context"));put("stream_id",input.getValue("stream_id"))
                            put("admission_receipt",Json.parseToJsonElement(requireNotNull(row.admissionJson)))
                            put("outcome_projection",Json.parseToJsonElement(requireNotNull(row.outcomeJson)))
                        }
                        beforeReadReply()
                        return mutateRead(LearningHttpResponse(200,readHeaders(),response.toString().toByteArray()))
                    }
                    operation.payloadJson
                } else {
                    check(path=="/v2/listening/next" && method.equals("post",true) && query.isEmpty())
                    check("X-Pleos-Observation-Only" !in headers)
                    requireNotNull(body).toString(Charsets.UTF_8).also{requests+=it}
                }
                val request=codecs.decode("review3","ReviewNextRequest3",text,WireReview3ReviewNextRequest3.serializer())
                val child=request.selectionOverride!=null
                if(observation && request.operationId !in admissions) {
                    val error=codecs.encode("review3","ErrorEnvelope",WireReview3ErrorEnvelope(WireReview3ErrorEnvelopeError(missingCode,false,null)),WireReview3ErrorEnvelope.serializer())
                    beforeReadReply()
                    return mutateRead(LearningHttpResponse(404,readHeaders(if(missingCode=="review_operation_not_admitted")"not_admitted" else "found"),error.toByteArray()))
                }
                if(child && !observation && dropChildBeforeAdmission)throw java.io.IOException("Fixture never admitted")
                val kind=request.selectionOverride?.kind?.let(PendingChoiceKind::fromWire)
                val terminal=child && kind!=PendingChoiceKind.CONTINUE && !(kind==PendingChoiceKind.READY_ALTERNATIVE && alternativeResumed)
                val admission=admissions.getOrPut(request.operationId) {
                    request.selectionOverride?.let{held+=it.pendingOperationId}
                    WireReview3NextAdmissionReceipt3(request.operationId,LearningJson.hash(incrementalCanonical(codecs.parse("review3",text))),now.toString(),
                        request.intentKind,if(terminal)null else UUID.randomUUID().toString(),if(terminal)null else now.plusSeconds(10).toString())
                }
                val target=if(terminal || kind==PendingChoiceKind.READY_ALTERNATIVE)null else WireReview3PendingTarget3(targetArticle,targetSource,"a".repeat(64),if(originalReady)"ready" else "unknown",
                    if(originalReady)WireReview3ComponentState("b".repeat(64),"main_original","ready",originalComponentId,"c".repeat(64),null,false,null,"fixture-reviewed") else null,
                    WireReview3PendingProgressHead3(null,null,0,1))
                val state=when { terminal->"no_candidate";kind==PendingChoiceKind.READY_ALTERNATIVE->"resumed";kind==PendingChoiceKind.CONTINUE->continueState
                    request.operationId in held->"expired_waiting_explicit";else->observedState }
                val status=if(kind==PendingChoiceKind.CONTINUE && state=="expired_waiting_explicit")listOf(
                    WireReview3ComponentState("b".repeat(64),"main_briefing","failed",null,null,"fixture_terminal_job",false,null,null)) else emptyList()
                val outcome=if(state=="resumed")alternative(request) else WireReview3NextOutcome3(if(request.operationId in held || kind==PendingChoiceKind.CONTINUE && state!="pending")2 else 1,
                    now.toString(),state,1,null,null,null,status,if(terminal)if(kind==PendingChoiceKind.READY_ALTERNATIVE)"ready_alternative_unavailable" else "original_unavailable" else null)
                val response=WireReview3ReviewNextResponse3("6",3,request.operationId,request.profileContext,request.streamId,admission,outcome,target)
                if(observation)beforeReadReply() else beforeReply()
                if(!observation && loseFirst && requests.size==1)throw java.io.IOException("Fixture lost response")
                val encoded=codecs.encode("review3","ReviewNextResponse3",response,WireReview3ReviewNextResponse3.serializer())
                val httpReply=LearningHttpResponse(200,if(observation)readHeaders() else mapOf("Content-Type" to "application/json","X-Pleos-Review-Capabilities" to capabilities.joinToString(",")),encoded.toByteArray())
                return if(observation)mutateRead(httpReply) else httpReply
            }
        }
        private fun alternative(request:WireReview3ReviewNextRequest3):WireReview3NextOutcome3 {
            val ref=WireReview3Source6PreparedContentRef6("prepared_components",if(alternativeIsParent)targetArticle else UUID.randomUUID().toString(),UUID.randomUUID().toString(),"d".repeat(64),
                UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),"e".repeat(64),"briefing")
            val native=UUID.randomUUID().toString();val selection=UUID.randomUUID().toString();val point=UUID.randomUUID().toString()
            val selected=WireReview3Source6PersonalSelectionRecord(selection,native,2,UUID.randomUUID().toString(),ref,
                WireReview3Source6CommonSettings(listOf("science"),"briefing","none",listOf("topics","listening_mode","followup_policy"),true,1),started.toString(),"automatic_unheard",null)
            val cursor=WireReview3Source6SegmentCursor6("segment",ref.mainScriptId,ref.mainTextHash,UUID.randomUUID().toString(),1,"f".repeat(64),"main",UUID.randomUUID().toString(),7,"start",false,"user")
            val checkpoint=WireReview3Source6PersonalReadCheckpoint(point,"checkpoint",4,WireReview3Source6PersonalCheckpointRecord(point,ref,native,selection,null,"partial",cursor))
            val context=WireReview3ListeningContext2(selection,request.profileContext.profileId,0,1,"regular","regular_auto",null,null,null,null,null,null,WireReview3AutoProgressRef(request.streamId,1))
            return WireReview3NextOutcome3(1,now.toString(),"resumed",1,selected,checkpoint,context,
                listOf(WireReview3ComponentState("b".repeat(64),"main_briefing","ready",ref.mainComponentId,"c".repeat(64),null,false,null,"fixture-reviewed")),null)
        }
        suspend fun initialize() {
            val owner=db.learning().provisionalOwner()
            call=LearningCallScope(OwnerContext(UUID.fromString(owner.profileId),owner.privacyEpoch,owner.revision,UUID.randomUUID()),
                LearningSession(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"synthetic-session"))
        }
        fun repo(capability:PendingOriginalCapabilityPort?=null)=ReviewNext3ListeningRepository(
            IncrementalLearningApi(LearningSessionSource{call},codecs,transport),codecs,db,db.serviceIntents(),db.review(),ReviewNext3StatePort{_,_,_->false},capability,
            elapsedMs={elapsed},utcNow={now})
        fun request(id:UUID,stream:UUID=UUID.randomUUID(),selectionOverride:WireReview3SelectionOverride3?=null)=WireReview3ReviewNextRequest3("6",id.toString(),
            WireReview3Source6CommonProfileContext(call.owner.profileId.toString(),call.owner.privacyEpoch),stream.toString(),1,null,1,UUID.randomUUID().toString(),
            "automatic_unheard",null,"explicit",0,3,if(selectionOverride?.kind==PendingChoiceKind.READY_ALTERNATIVE.wire)"ready_alternative" else "explicit_start",null,null,"app",selectionOverride)
        fun binding(repo:ReviewNext3ListeningRepository,request:WireReview3ReviewNextRequest3)=repo.newBinding(call.owner,UUID.fromString(request.operationId),
            UUID.randomUUID(),PlaybackRequestKind.valueOf(request.intentKind.uppercase()),PlaybackRequestChannel.APP,null,null,
            if(request.selectionOverride?.kind==PendingChoiceKind.ORIGINAL.wire)ListeningMode.ORIGINAL_FULL else ListeningMode.BRIEFING,
            pendingOriginalParentOperationId=request.selectionOverride?.pendingOperationId?.let(UUID::fromString),pendingChoiceKind=request.selectionOverride?.kind?.let(PendingChoiceKind::fromWire))
        fun guard(repo:ReviewNext3ListeningRepository,binding:PlaybackRequestBinding)=PlaybackActivationGuard(call.owner,binding.contextToken,repo.processToken,elapsed,now,true)
        override fun close(){db.close()}
    }

    @Test fun sameVersionActualTargetReadAndExplicitChildPreserveParentBytesAndNeverReviveFailedOriginal()=runBlocking {
        Fixture(context).use { f ->
            f.initialize();val repo=f.repo();val request=f.request(UUID.randomUUID());val binding=f.binding(repo,request)
            assertFalse(repo.supportsPendingOriginal(f.call))
            assertTrue(repo.submit(f.call,request,binding,f.guard(repo,binding)) is LearningReply.Stored)
            assertTrue(repo.supportsPendingOriginal(f.call))
            val before=requireNotNull(f.db.serviceIntents().intent(request.operationId));val original=requireNotNull(f.db.review().operation(request.operationId))
            f.elapsed+=100;f.now=f.now.plusSeconds(1);f.originalReady=true
            assertTrue(repo.observe(f.call,UUID.fromString(request.operationId),f.guard(repo,binding)) is LearningReply.Stored)
            assertEquals("ready",repo.state.value.response?.pendingTarget?.originalAvailability)
            assertEquals(1L,repo.state.value.intent?.outcomeVersion);assertEquals(f.requests[0],f.requests[1])
            val child=UUID.randomUUID();val anchor=requireNotNull(repo.beginOriginalInput(f.call,UUID.fromString(request.operationId),child))
            assertEquals("cancelled",f.db.serviceIntents().intent(request.operationId)?.state)
            assertEquals(original,f.db.review().operation(request.operationId))
            assertEquals(before.admissionJson,f.db.serviceIntents().intent(request.operationId)?.admissionJson)
            val explicit=f.request(child,anchor.targetStreamId,WireReview3SelectionOverride3("original_of_pending",request.operationId,
                anchor.selectionIntentId.toString(),anchor.requestHash,anchor.outcomeVersion,null))
            val childBinding=f.binding(repo,explicit)
            assertTrue(repo.submit(f.call,explicit,childBinding,f.guard(repo,childBinding)) is LearningReply.Stored)
            assertEquals("original_unavailable",repo.state.value.response?.outcomeProjection?.noCandidateReason)
            assertNull(repo.claimActivation(f.call,child,f.guard(repo,childBinding)))
            val count=f.requests.size
            repo.observe(f.call,child,f.guard(repo,childBinding))
            assertEquals(count,f.requests.size) // Same failed operation does not start preparation or choose briefing.
            assertEquals("cancelled",f.db.serviceIntents().intent(request.operationId)?.state)
        }
    }

    @Test fun stopDuringHttpWinsLateProjectionAndCancelledRequestIsNotRestored()=runBlocking {
        Fixture(context).use { f ->
            f.initialize();val repo=f.repo();val request=f.request(UUID.randomUUID());val binding=f.binding(repo,request)
            f.beforeReply={assertTrue(repo.cancel(f.call,UUID.fromString(request.operationId)))}
            assertTrue(repo.submit(f.call,request,binding,f.guard(repo,binding)) is LearningReply.Stored)
            assertEquals(PlaybackRequestState.CANCELLED,repo.state.value.intent?.state)
            assertNull(repo.claimActivation(f.call,UUID.fromString(request.operationId),f.guard(repo,binding)))
            assertNull(repo.latestPendingOperation(f.call))
        }
    }

    @Test fun processRestoreAndLostReplyObserveOnlyOriginalOperationWithoutNewDeadlineOrGrant()=runBlocking {
        Fixture(context).use { f ->
            f.initialize();val repo=f.repo();val request=f.request(UUID.randomUUID());val binding=f.binding(repo,request);f.loseFirst=true
            assertTrue(repo.submit(f.call,request,binding,f.guard(repo,binding)) is LearningReply.Failed)
            val savedBinding=requireNotNull(f.db.serviceIntents().intent(request.operationId)).bindingJson
            f.elapsed+=20_000;f.now=f.now.plusSeconds(20);f.observedState="expired_waiting_explicit"
            val restored=f.repo()
            assertEquals(RestorableNextOperation(UUID.fromString(request.operationId),3),restored.latestPendingOperation(f.call))
            assertTrue(restored.restoreLatestPending(f.call) is LearningReply.Stored)
            assertTrue(restored.observeStateOnly(f.call,UUID.fromString(request.operationId)) is LearningReply.Stored)
            assertEquals(1,f.requests.size);assertEquals(requireNotNull(f.db.review().operation(request.operationId)).payloadHash,f.readQueries.last()["request_hash"])
            assertEquals(savedBinding,f.db.serviceIntents().intent(request.operationId)?.bindingJson)
            assertEquals(PlaybackRequestState.EXPIRED,restored.state.value.intent?.state)
            assertTrue(restored.state.value.requiresExplicitResume)
            assertNull(restored.claimActivation(f.call,UUID.fromString(request.operationId),f.guard(restored,binding)))
        }
    }

    @Test fun oldVersionTwoParentIsOpaqueAndNeverReencodedOrGivenInventedOriginalAvailability()=runBlocking {
        Fixture(context).use { f ->
            f.initialize();val repo=f.repo(PendingOriginalCapabilityPort{true}) // Test-only actual-registry port fixture.
            val request=f.request(UUID.randomUUID());val binding=f.binding(repo,request)
            val input=JsonObject(f.codecs.parse("review3",f.codecs.encode("review3","ReviewNextRequest3",request,WireReview3ReviewNextRequest3.serializer())).jsonObject
                .filterKeys{it!="selection_override"}+("review_contract_version" to JsonPrimitive(2)))
            val bytes=incrementalCanonical(input)
            f.codecs.decode("review2","ReviewNextRequest2",bytes,WireReview2ReviewNextRequest2.serializer())
            val hash=LearningJson.hash(bytes);val parent=LocalPlaybackRequest(request.operationId,f.call.owner.profileId.toString(),0,1,
                binding.contextToken.toString(),binding.processToken.toString(),encodePlaybackBinding(binding,request.streamId),hash,"active",0,null,null,0,null,false)
            val original=LocalReviewOperation(request.operationId,parent.profileId,0,1,"review2_next",request.streamId,bytes,hash)
            f.db.review().queue(f.call.owner,original);f.db.serviceIntents().register(f.call.owner,parent)
            val receipt=f.codecs.encode("review2","NextAdmissionReceipt2",WireReview2NextAdmissionReceipt2(request.operationId,hash,f.started.toString(),"explicit_start",
                UUID.randomUUID().toString(),f.started.plusSeconds(10).toString()),WireReview2NextAdmissionReceipt2.serializer())
            val outcome=f.codecs.encode("review2","NextOutcome2",WireReview2NextOutcome2(1,f.started.toString(),"pending",1,null,null,null,emptyList(),null),WireReview2NextOutcome2.serializer())
            f.db.review().acknowledge(f.call.owner,original,receipt)
            val admitted=parent.copy(admissionJson=receipt,outcomeJson=outcome,outcomeVersion=1)
            assertTrue(f.db.serviceIntents().advance(f.call.owner,parent,admitted))
            val anchor=requireNotNull(repo.beginOriginalInput(f.call,UUID.fromString(request.operationId),UUID.randomUUID()))
            assertEquals(2,anchor.parentContractVersion);assertNull(anchor.pendingTarget)
            val saved=requireNotNull(f.db.serviceIntents().intent(request.operationId))
            assertEquals(receipt,saved.admissionJson);assertEquals(outcome,saved.outcomeJson);assertEquals(admitted.bindingJson,saved.bindingJson)
            assertEquals(bytes,f.db.review().operation(request.operationId)?.payloadJson);assertEquals(hash,f.db.review().operation(request.operationId)?.payloadHash)
            assertEquals("cancelled",saved.state);assertTrue(f.requests.isEmpty())
            assertTrue(repo.observePendingParentStateOnly(f.call,UUID.fromString(request.operationId)) is LearningReply.Stored)
            assertEquals("2",f.readQueries.single()["review_contract_version"])
            assertEquals(hash,f.readQueries.single()["request_hash"])
            assertEquals(bytes,f.db.review().operation(request.operationId)?.payloadJson)
            assertEquals(receipt,f.db.serviceIntents().intent(request.operationId)?.admissionJson)
            assertEquals(Json.parseToJsonElement(outcome),Json.parseToJsonElement(requireNotNull(f.db.serviceIntents().intent(request.operationId)?.outcomeJson)))
            assertNull(repo.state.value.response);assertNull(repo.state.value.retainedParentAnchor?.pendingTarget)
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test fun explicitStopAfterRestorePermanentlyExcludesBothProtocolVersionsFromNextProcessPendingRecovery()=runBlocking {
        Fixture(context).use { f ->
            f.initialize();val initial=f.repo();val request=f.request(UUID.randomUUID());val binding=f.binding(initial,request)
            assertTrue(initial.submit(f.call,request,binding,f.guard(initial,binding)) is LearningReply.Stored)
            val restored=f.repo()
            assertTrue(restored.restore(f.call,UUID.fromString(request.operationId)) is LearningReply.Stored)
            assertEquals("expired",f.db.serviceIntents().intent(request.operationId)?.state)
            assertTrue(restored.cancel(f.call,UUID.fromString(request.operationId)))
            assertEquals("cancelled",f.db.serviceIntents().intent(request.operationId)?.state)
            assertNull(f.repo().latestPendingOperation(f.call))

            // An actual old-protocol Room row is restored and cancelled by its own repository.
            val old=f.request(UUID.randomUUID());val oldBinding=f.binding(initial,old)
            val input=JsonObject(f.codecs.parse("review3",f.codecs.encode("review3","ReviewNextRequest3",old,WireReview3ReviewNextRequest3.serializer())).jsonObject
                .filterKeys{it!="selection_override"}+("review_contract_version" to JsonPrimitive(2)))
            val bytes=incrementalCanonical(input);val hash=LearningJson.hash(bytes)
            f.codecs.decode("review2","ReviewNextRequest2",bytes,WireReview2ReviewNextRequest2.serializer())
            val local=LocalPlaybackRequest(old.operationId,f.call.owner.profileId.toString(),0,1,oldBinding.contextToken.toString(),oldBinding.processToken.toString(),
                encodePlaybackBinding(oldBinding,old.streamId),hash,"active",0,null,null,0,null,false)
            val operation=LocalReviewOperation(old.operationId,local.profileId,0,1,"review2_next",old.streamId,bytes,hash)
            f.db.review().queue(f.call.owner,operation);f.db.serviceIntents().register(f.call.owner,local)
            val receipt=f.codecs.encode("review2","NextAdmissionReceipt2",WireReview2NextAdmissionReceipt2(old.operationId,hash,f.started.toString(),"explicit_start",
                UUID.randomUUID().toString(),f.started.plusSeconds(10).toString()),WireReview2NextAdmissionReceipt2.serializer())
            val outcome=f.codecs.encode("review2","NextOutcome2",WireReview2NextOutcome2(1,f.started.toString(),"pending",1,null,null,null,emptyList(),null),WireReview2NextOutcome2.serializer())
            f.db.review().acknowledge(f.call.owner,operation,receipt)
            assertTrue(f.db.serviceIntents().advance(f.call.owner,local,local.copy(admissionJson=receipt,outcomeJson=outcome,outcomeVersion=1)))
            val oldRepo=ReviewNext2ListeningRepository(IncrementalLearningApi(LearningSessionSource{f.call},f.codecs,f.transport),f.codecs,f.db,
                f.db.serviceIntents(),f.db.review(),ReviewNext2StatePort{_,_,_->false},elapsedMs={f.elapsed},utcNow={f.now})
            assertTrue(oldRepo.restore(f.call,UUID.fromString(old.operationId)) is LearningReply.Stored)
            assertEquals("expired",f.db.serviceIntents().intent(old.operationId)?.state)
            assertTrue(oldRepo.cancel(f.call,UUID.fromString(old.operationId)))
            assertEquals("cancelled",f.db.serviceIntents().intent(old.operationId)?.state)
            assertNull(f.repo().latestPendingOperation(f.call))
            assertEquals(bytes,f.db.review().operation(old.operationId)?.payloadJson)
            assertEquals(receipt,f.db.serviceIntents().intent(old.operationId)?.admissionJson)
            assertEquals(outcome,f.db.serviceIntents().intent(old.operationId)?.outcomeJson)
            assertEquals(1,f.requests.size) // Restoring/stopping either version never sends a new POST.
        }
    }

    @Test fun alternativeRequiresItsOwnActualHeaderAndPreservesExactCResumedCursorInsteadOfOriginalSegmentZero()=runBlocking {
        Fixture(context).use { f ->
            f.initialize();val repo=f.repo();val parent=f.request(UUID.randomUUID());val binding=f.binding(repo,parent)
            assertTrue(repo.submit(f.call,parent,binding,f.guard(repo,binding)) is LearningReply.Stored)
            assertEquals(setOf(PendingChoiceKind.ORIGINAL),repo.state.value.supportedChoices)
            assertNull(repo.beginPendingInput(f.call,UUID.fromString(parent.operationId),UUID.randomUUID(),PendingChoiceKind.READY_ALTERNATIVE))
            assertNull(repo.beginPendingInput(f.call,UUID.fromString(parent.operationId),UUID.randomUUID(),PendingChoiceKind.CONTINUE))
            assertEquals(1,f.requests.size)
            f.capabilities=PendingChoiceKind.entries.map{it.capability}.toSet()
            assertTrue(repo.observe(f.call,UUID.fromString(parent.operationId),f.guard(repo,binding)) is LearningReply.Stored)
            val child=UUID.randomUUID();val anchor=requireNotNull(repo.beginPendingInput(f.call,UUID.fromString(parent.operationId),child,PendingChoiceKind.READY_ALTERNATIVE))
            assertEquals(ListeningMode.BRIEFING,anchor.parentListeningMode)
            f.alternativeResumed=true
            val request=f.request(child,anchor.targetStreamId,WireReview3SelectionOverride3(PendingChoiceKind.READY_ALTERNATIVE.wire,parent.operationId,
                anchor.selectionIntentId.toString(),anchor.requestHash,anchor.outcomeVersion,null))
            val childBinding=f.binding(repo,request)
            assertEquals(PlaybackRequestKind.READY_ALTERNATIVE,childBinding.kind)
            assertEquals(ListeningMode.BRIEFING,childBinding.listeningMode)
            assertTrue(repo.submit(f.call,request,childBinding,f.guard(repo,childBinding)) is LearningReply.Stored)
            val outcome=requireNotNull(repo.state.value.response).outcomeProjection
            assertEquals("resumed",outcome.state)
            assertEquals(7L,(outcome.checkpoint?.payload?.cursor as? WireReview3Source6SegmentCursor6)?.segmentIndex)
            assertNotEquals(parent.streamId,outcome.selection?.streamId)
            assertNotEquals(f.targetArticle,(outcome.selection?.contentRef as? WireReview3Source6PreparedContentRef6)?.articleId)
            assertEquals("briefing",(outcome.selection?.contentRef as? WireReview3Source6PreparedContentRef6)?.listeningMode)
            assertNull(repo.state.value.retainedParentAnchor) // Actual successful C is not a pending B.
        }
        Fixture(context).use { f ->
            f.initialize();f.capabilities=PendingChoiceKind.entries.map{it.capability}.toSet();val repo=f.repo()
            val parent=f.request(UUID.randomUUID());val binding=f.binding(repo,parent)
            repo.submit(f.call,parent,binding,f.guard(repo,binding))
            val child=UUID.randomUUID();val anchor=requireNotNull(repo.beginPendingInput(f.call,UUID.fromString(parent.operationId),child,PendingChoiceKind.READY_ALTERNATIVE))
            f.alternativeResumed=true;f.alternativeIsParent=true
            val request=f.request(child,anchor.targetStreamId,WireReview3SelectionOverride3(PendingChoiceKind.READY_ALTERNATIVE.wire,parent.operationId,
                anchor.selectionIntentId.toString(),anchor.requestHash,anchor.outcomeVersion,null));val chosen=f.binding(repo,request)
            assertTrue(repo.submit(f.call,request,chosen,f.guard(repo,chosen)) is LearningReply.Failed)
            assertNull(repo.claimActivation(f.call,child,f.guard(repo,chosen))) // Even another snapshot of B cannot be C.
        }
    }

    @Test fun failedAlternativeRetainsOriginalBAndNewContinueKeepsModeAndJobStatusWithoutRevivingParentDeadline()=runBlocking {
        Fixture(context).use { f ->
            f.initialize();f.capabilities=PendingChoiceKind.entries.map{it.capability}.toSet();val repo=f.repo()
            val parent=f.request(UUID.randomUUID());val binding=f.binding(repo,parent)
            repo.submit(f.call,parent,binding,f.guard(repo,binding))
            val parentRow=requireNotNull(f.db.serviceIntents().intent(parent.operationId))
            val alternative=UUID.randomUUID();val anchor=requireNotNull(repo.beginPendingInput(f.call,UUID.fromString(parent.operationId),alternative,PendingChoiceKind.READY_ALTERNATIVE))
            val alternateRequest=f.request(alternative,anchor.targetStreamId,WireReview3SelectionOverride3(PendingChoiceKind.READY_ALTERNATIVE.wire,parent.operationId,
                anchor.selectionIntentId.toString(),anchor.requestHash,anchor.outcomeVersion,null));val alternateBinding=f.binding(repo,alternateRequest)
            assertTrue(repo.submit(f.call,alternateRequest,alternateBinding,f.guard(repo,alternateBinding)) is LearningReply.Stored)
            assertEquals("ready_alternative_unavailable",repo.state.value.response?.outcomeProjection?.noCandidateReason)
            assertNull(repo.state.value.response?.admissionReceipt?.selectionIntentId)
            assertEquals(anchor.parentOperationId,repo.state.value.retainedParentAnchor?.parentOperationId)
            assertEquals(PendingChoiceReconciliation.HELD_PARENT_REFRESH,repo.state.value.pendingChoiceRetention?.reconciliation)
            f.elapsed+=20_000;f.now=f.now.plusSeconds(20)
            val refreshed=repo.refreshRetainedPendingAnchor(f.call,alternative)
            assertTrue(refreshed is LearningReply.Stored)
            val fresh=(refreshed as LearningReply.Stored).value
            assertEquals(2L,fresh.outcomeVersion);assertEquals(anchor.requestHash,f.readQueries.last()["request_hash"]);assertEquals(2,f.requests.size);assertTrue(f.observations.last())
            assertEquals(parentRow.admissionJson,f.db.serviceIntents().intent(parent.operationId)?.admissionJson)
            val wait=UUID.randomUUID();val waitAnchor=requireNotNull(repo.beginPendingInput(f.call,fresh.parentOperationId,wait,PendingChoiceKind.CONTINUE))
            val waiting=f.request(wait,waitAnchor.targetStreamId,WireReview3SelectionOverride3(PendingChoiceKind.CONTINUE.wire,parent.operationId,
                waitAnchor.selectionIntentId.toString(),waitAnchor.requestHash,waitAnchor.outcomeVersion,null));val waitBinding=f.binding(repo,waiting)
            assertEquals(ListeningMode.BRIEFING,waitBinding.listeningMode)
            assertTrue(repo.submit(f.call,waiting,waitBinding,f.guard(repo,waitBinding)) is LearningReply.Stored)
            val receipt=requireNotNull(repo.state.value.response).admissionReceipt
            assertEquals("pending",repo.state.value.response?.outcomeProjection?.state)
            assertEquals(f.targetArticle,repo.state.value.response?.pendingTarget?.articleId)
            assertNotEquals(anchor.selectionIntentId.toString(),receipt.selectionIntentId)
            assertEquals(f.now.plusSeconds(10).toString(),receipt.activationDeadlineAt)
            val originalWaitBody=f.requests.last();f.continueState="expired_waiting_explicit";f.elapsed+=10_000;f.now=f.now.plusSeconds(10)
            assertTrue(repo.observeStateOnly(f.call,wait) is LearningReply.Stored)
            assertEquals(originalWaitBody,f.requests.last());assertTrue(f.observations.last())
            assertEquals("fixture_terminal_job",repo.state.value.response?.outcomeProjection?.sourceStatus?.singleOrNull()?.reason)
            assertEquals(receipt,repo.state.value.response?.admissionReceipt)
            assertNull(repo.claimActivation(f.call,wait,f.guard(repo,waitBinding)))
            val restored=f.repo();assertTrue(restored.restoreLatestPending(f.call) is LearningReply.Stored)
            assertTrue(restored.state.value.supportedChoices.isEmpty())
            assertTrue(restored.observeStateOnly(f.call,wait) is LearningReply.Stored)
            assertEquals(PendingChoiceKind.entries.toSet(),restored.state.value.supportedChoices)
            assertTrue(restored.cancel(f.call,wait))
            assertNull(restored.state.value.retainedParentAnchor);assertNull(f.repo().latestPendingOperation(f.call))
        }
    }

    @Test fun unsubmittedInputAndDefinitiveNoAdmissionKeepBWithoutInventingServerHoldOrLoopingForever()=runBlocking {
        Fixture(context).use { f ->
            f.initialize();f.capabilities=PendingChoiceKind.entries.map{it.capability}.toSet();val repo=f.repo()
            val parent=f.request(UUID.randomUUID());val binding=f.binding(repo,parent)
            repo.submit(f.call,parent,binding,f.guard(repo,binding))
            val child=UUID.randomUUID();val anchor=requireNotNull(repo.beginPendingInput(f.call,UUID.fromString(parent.operationId),child,PendingChoiceKind.READY_ALTERNATIVE))
            assertEquals(child,repo.state.value.pendingChoiceRetention?.childOperationId)
            assertEquals(PendingChoiceReconciliation.NONE,repo.state.value.pendingChoiceRetention?.reconciliation)
            assertFalse(requireNotNull(repo.state.value.pendingChoiceRetention).parentHoldConfirmed)
            assertTrue(repo.refreshRetainedPendingAnchor(f.call,anchor.parentOperationId) is LearningReply.Stored);assertEquals(1,f.requests.size)
            val request=f.request(child,anchor.targetStreamId,WireReview3SelectionOverride3(PendingChoiceKind.READY_ALTERNATIVE.wire,parent.operationId,
                anchor.selectionIntentId.toString(),anchor.requestHash,anchor.outcomeVersion,null));val chosen=f.binding(repo,request)
            f.dropChildBeforeAdmission=true
            assertTrue(repo.submit(f.call,request,chosen,f.guard(repo,chosen)) is LearningReply.Failed)
            assertEquals(PendingChoiceReconciliation.SAME_CHILD_REQUIRED,repo.state.value.pendingChoiceRetention?.reconciliation)
            assertEquals("PENDING_CHILD_RECEIPT_REQUIRED",(repo.refreshRetainedPendingAnchor(f.call,child) as LearningReply.Failed).code)
            f.missingCode="record_not_found"
            assertTrue(repo.observeStateOnly(f.call,child) is LearningReply.Failed)
            assertEquals(PendingChoiceReconciliation.SAME_CHILD_REQUIRED,repo.state.value.pendingChoiceRetention?.reconciliation)
            f.missingCode="review_operation_not_admitted"
            assertEquals("review_operation_not_admitted",(repo.observeStateOnly(f.call,child) as LearningReply.Failed).code)
            assertEquals(PendingChoiceReconciliation.NONE,repo.state.value.pendingChoiceRetention?.reconciliation)
            assertFalse(requireNotNull(repo.state.value.pendingChoiceRetention).parentHoldConfirmed)
            assertEquals(parent.operationId,repo.state.value.retainedParentAnchor?.parentOperationId.toString())
            assertTrue(repo.state.value.supportedChoices.isEmpty()) // A failed read cannot restore this process's support memo.
            val beforeUnsupported=f.requests.size
            assertEquals("PENDING_CHOICE_UNSUPPORTED",(repo.submit(f.call,request,chosen,f.guard(repo,chosen)) as LearningReply.Failed).code)
            assertEquals(beforeUnsupported,f.requests.size) // The still-current child is support-gated before any POST.
            val count=f.requests.size
            assertTrue(repo.refreshRetainedPendingAnchor(f.call,child) is LearningReply.Stored);assertEquals(count,f.requests.size)
            val restored=f.repo();assertTrue(restored.restoreLatestPending(f.call) is LearningReply.Stored)
            assertTrue(restored.observePendingParentStateOnly(f.call,UUID.fromString(parent.operationId)) is LearningReply.Stored)
            assertEquals(anchor.requestHash,f.readQueries.last()["request_hash"]);assertTrue(f.observations.last())
            assertEquals(PendingChoiceKind.entries.toSet(),restored.state.value.supportedChoices)
            assertEquals(1L,restored.state.value.retainedParentAnchor?.outcomeVersion)
            val latest=UUID.randomUUID()
            assertNotNull(restored.beginPendingInput(f.call,anchor.parentOperationId,latest,PendingChoiceKind.CONTINUE))
            assertEquals(latest,restored.state.value.pendingChoiceRetention?.childOperationId)
            assertNull(restored.beginPendingInput(f.call,anchor.parentOperationId,child,PendingChoiceKind.READY_ALTERNATIVE))
            val beforeStale=f.requests.size
            assertTrue(repo.state.value.supportedChoices.isEmpty())
            assertEquals("PENDING_INPUT_SUPERSEDED",(repo.submit(f.call,request,chosen,f.guard(repo,chosen)) as LearningReply.Failed).code)
            assertEquals(beforeStale,f.requests.size)
            assertTrue(restored.cancel(f.call,anchor.parentOperationId))
            assertNull(restored.state.value.retainedParentAnchor)
            assertNull(restored.beginPendingInput(f.call,anchor.parentOperationId,latest,PendingChoiceKind.CONTINUE))
        }
    }

    @Test fun everyReadRequiresFreshSingleMetadataAndNeverFallsBackToBusinessPost()=runBlocking {
        Fixture(context).use { f ->
            f.initialize();val repo=f.repo();val input=f.request(UUID.randomUUID());val binding=f.binding(repo,input)
            assertTrue(repo.submit(f.call,input,binding,f.guard(repo,binding)) is LearningReply.Stored)
            val hash=requireNotNull(f.db.review().operation(input.operationId)).payloadHash
            val reader=IncrementalLearningApi(LearningSessionSource{f.call},f.codecs,f.transport).nextObservations()
            val read=NextObservationRequest(UUID.fromString(input.operationId),3,hash)
            assertTrue(reader.read(f.call,read) is NextObservationReply.Found);assertNotNull(reader.lastFreshEvidence.value)
            val invalidHeaders=listOf<(Map<String,String>)->Map<String,String>>(
                {it-"X-Pleos-Observation-Version"},
                {it+("x-pleos-observation-version" to "1")},
                {it+("X-Pleos-Observation-Version" to "1,1")},
                {it+("X-Pleos-Observation-Version" to "2")},
                {it+("X-Pleos-Observation-Backend-Revision" to "invalid revision")},
                {it+("X-Pleos-Observation-Result" to "not_admitted")},
                {it.filterKeys{key->!key.startsWith("X-Pleos-Observation-")}}
            )
            for(change in invalidHeaders) {
                f.mutateRead={it.copy(headers=change(it.headers))}
                assertTrue(reader.read(f.call,read) is NextObservationReply.Unavailable)
                assertNull(reader.lastFreshEvidence.value)
            }
            for(status in listOf(302,404,405,503)) {
                f.mutateRead={it.copy(status=status,headers=mapOf("Content-Type" to "application/json"))}
                assertTrue(reader.read(f.call,read) is NextObservationReply.Unavailable)
                assertNull(reader.lastFreshEvidence.value)
            }
            f.mutateRead={it};assertTrue(reader.read(f.call,read) is NextObservationReply.Found)
            f.beforeReadReply={f.origin="https://changed.fixture"}
            assertTrue(reader.read(f.call,read) is NextObservationReply.Unavailable)
            assertNull(reader.lastFreshEvidence.value)
            assertEquals(1,f.requests.size) // Every retry above was GET with no body/admission/deadline extension.
        }
    }

    @Test fun errorClassificationRequiresFreshProofAndAnExistingReceiptCannotBecomeNotAdmitted()=runBlocking {
        Fixture(context).use { f ->
            f.initialize();val repo=f.repo();val input=f.request(UUID.randomUUID());val binding=f.binding(repo,input)
            assertTrue(repo.submit(f.call,input,binding,f.guard(repo,binding)) is LearningReply.Stored)
            val operation=requireNotNull(f.db.review().operation(input.operationId));val row=requireNotNull(f.db.serviceIntents().intent(input.operationId))
            val reader=IncrementalLearningApi(LearningSessionSource{f.call},f.codecs,f.transport).nextObservations()
            val request=NextObservationRequest(UUID.fromString(input.operationId),3,row.requestHash)
            fun error(code:String)=f.codecs.encode("review3","ErrorEnvelope",WireReview3ErrorEnvelope(WireReview3ErrorEnvelopeError(code,false,null)),WireReview3ErrorEnvelope.serializer()).toByteArray()
            f.mutateRead={LearningHttpResponse(404,f.readHeaders("found"),error("record_not_found"))}
            val stored=reader.read(f.call,request)
            assertTrue(stored is NextObservationReply.StoredError)
            assertEquals("record_not_found",(stored as NextObservationReply.StoredError).failure.code)
            for(status in listOf(401,403,405)) {
                f.mutateRead={LearningHttpResponse(status,f.readHeaders("found"),error("record_not_found"))}
                assertTrue(reader.read(f.call,request) is NextObservationReply.Unavailable)
            }
            f.mutateRead={LearningHttpResponse(404,f.readHeaders("found"),error("review_operation_not_admitted"))}
            assertTrue(reader.read(f.call,request) is NextObservationReply.Unavailable) // result/code mismatch.
            f.mutateRead={LearningHttpResponse(404,mapOf("Content-Type" to "application/json"),error("review_operation_not_admitted"))}
            assertTrue(reader.read(f.call,request) is NextObservationReply.Unavailable) // Old API/DB is uncertain.
            f.mutateRead={LearningHttpResponse(404,f.readHeaders("not_admitted"),error("review_operation_not_admitted"))}
            assertEquals("NEXT_OBSERVATION_RECEIPT_CONFLICT",(repo.observeStateOnly(f.call,UUID.fromString(input.operationId)) as LearningReply.Failed).code)
            assertEquals(operation,f.db.review().operation(input.operationId))
            val after=requireNotNull(f.db.serviceIntents().intent(input.operationId))
            assertEquals(row.admissionJson,after.admissionJson);assertEquals(row.outcomeJson,after.outcomeJson)
            assertFalse(f.db.serviceIntents().pendingChoiceNotAdmitted(f.call.owner,after))
            assertNull(repo.claimActivation(f.call,UUID.fromString(input.operationId),f.guard(repo,binding)))
            assertEquals(1,f.requests.size)
        }
    }

    @Test fun currentOwnerAndOriginalProtocolHashRemainBoundDuringEachObservation()=runBlocking {
        Fixture(context).use { f ->
            f.initialize();val repo=f.repo();val input=f.request(UUID.randomUUID());val binding=f.binding(repo,input)
            repo.submit(f.call,input,binding,f.guard(repo,binding))
            val hash=requireNotNull(f.db.review().operation(input.operationId)).payloadHash
            val reader=IncrementalLearningApi(LearningSessionSource{f.call},f.codecs,f.transport).nextObservations()
            val query=NextObservationRequest(UUID.fromString(input.operationId),3,hash)
            f.mutateRead={reply->
                val json=Json.parseToJsonElement(reply.body.toString(Charsets.UTF_8)).jsonObject
                val v2=JsonObject(json.filterKeys{it!="pending_target"}+("review_contract_version" to JsonPrimitive(2)))
                reply.copy(body=v2.toString().toByteArray())
            }
            assertTrue(reader.read(f.call,query) is NextObservationReply.Unavailable)
            f.mutateRead={reply->
                val json=Json.parseToJsonElement(reply.body.toString(Charsets.UTF_8)).jsonObject
                val receipt=json.getValue("admission_receipt").jsonObject
                reply.copy(body=JsonObject(json+("admission_receipt" to JsonObject(receipt+("request_hash" to JsonPrimitive("0".repeat(64)))))).toString().toByteArray())
            }
            assertTrue(reader.read(f.call,query) is NextObservationReply.Unavailable)
            f.mutateRead={it};val scope=f.call
            f.beforeReadReply={f.call=f.call.copy(session=LearningSession(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"synthetic-replacement"))}
            assertTrue(reader.read(scope,query) is NextObservationReply.Unavailable)
            assertNull(reader.lastFreshEvidence.value)
            assertEquals(1,f.requests.size)
        }
    }
}
