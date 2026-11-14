package io.github.driveenglishlab.pleos.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Room ACK/proof persistence and strict codecs with deterministic GET fixtures.
 * No format mutation is sent; no audio, output grant or live server success is inferred. */
@RunWith(AndroidJUnit4::class)
class AutoProgressFormatHeadRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val owner = OwnerContext(UUID.randomUUID(),0,1,UUID.randomUUID())
    private val scope = LearningCallScope(owner,LearningSession(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"format-proof-fixture"))
    private val logical = UUID.randomUUID()
    private val nativeStream = UUID.randomUUID()
    private val originalSelection = UUID.randomUUID()
    private val originalCheckpoint = UUID.randomUUID()
    private val accepted = Instant.parse("2026-11-15T01:00:00Z")
    private lateinit var db:LearningDatabase
    private lateinit var codecs:IncrementalContractJson
    private lateinit var api:IncrementalLearningApi
    private lateinit var baseline:JsonObject
    private var live:LearningCallScope? = scope
    private val contexts = mutableMapOf<String,JsonObject>()
    private val gets = mutableListOf<String>()
    private var beforeGet:suspend ()->Unit = {}
    private data class Format(val operation:LocalReviewOperation,val receipt:String,val selectionId:String)
    private fun recovery() = AutoProgressHeadRecoveryRepository(api,codecs,db.serviceIntents(),db.review())

    @Before fun setup() = runBlocking {
        db=Room.inMemoryDatabaseBuilder(context,LearningDatabase::class.java).build()
        db.learning().putOwner(LocalOwner(profileId=owner.profileId.toString(),privacyEpoch=0,revision=1,status="active"))
        codecs=IncrementalContractJson(context)
        baseline=contractFixture("NextArticleResponse") { it["status"]==JsonPrimitive("selected") }.getValue("selection").jsonObject
        api=IncrementalLearningApi(LearningSessionSource { live },codecs,object:LearningHttpTransport {
            override suspend fun exchange(method:String,path:String,query:Map<String,String>,headers:Map<String,String>,body:ByteArray?):LearningHttpResponse {
                assertEquals("get",method.lowercase());assertNull(body)
                assertEquals(mapOf("review_contract_version" to "2"),query)
                gets+=path;beforeGet()
                return jsonResponse(requireNotNull(contexts[path.substringAfterLast('/')]))
            }
        })
        seedConfirmedNext()
    }
    @After fun close(){db.close()}
    private fun tagged(mode:String)=buildJsonObject {
        put("source_kind","legacy_bundle")
        put("legacy_ref",baseline.getValue("content_ref").jsonObject.changed("listening_mode" to JsonPrimitive(mode),
            "listening_script_id" to if(mode=="original_full") baseline.getValue("content_ref").jsonObject.getValue("listening_script_id") else JsonPrimitive(UUID.randomUUID().toString())))
    }
    private fun selection(id:String,ref:JsonObject,source:String?=null)=baseline.changed(
        "selection_id" to JsonPrimitive(id),"stream_id" to JsonPrimitive(nativeStream.toString()),"content_ref" to ref,
        "previous_selection_id" to (source?.let(::JsonPrimitive)?:JsonNull),"ordinal" to JsonPrimitive(if(source==null)1 else 2),
        "settings_snapshot" to baseline.getValue("settings_snapshot").jsonObject.changed("listening_mode" to ref.getValue("legacy_ref").jsonObject.getValue("listening_mode")),
        "selection_reason" to JsonPrimitive(if(source==null)"automatic_unheard" else "explicit_format_change"),
        "format_change_source" to if(source==null)JsonNull else buildJsonObject {put("selection_id",source);put("checkpoint_id",originalCheckpoint.toString())})
    private fun checkpoint(id:String,selection:String,ref:JsonObject)=buildJsonObject {
        put("record_id",id);put("kind","checkpoint");put("version",1)
        putJsonObject("payload") {
            put("checkpoint_id",id);put("stream_id",nativeStream.toString());put("selection_id",selection);put("content_ref",ref)
            put("active_run_id",JsonNull);put("main_coverage_status","unknown")
            putJsonObject("cursor") {put("kind","legacy_time");put("original_checkpoint_id",id);put("original_position_ms",0);put("original_duration_ms",100_000);put("phase","main")}
        }
    }
    private fun profile()=buildJsonObject {put("profile_id",owner.profileId.toString());put("privacy_epoch",0)}
    private fun contextFor(selection:String,version:Long,progress:UUID=logical)=buildJsonObject {
        put("review_contract_version",2);put("profile_id",owner.profileId.toString());put("learning_epoch",0)
        putJsonObject("context") {
            put("selection_id",selection);put("profile_id",owner.profileId.toString());put("learning_epoch",0);put("version",1)
            put("origin","regular");put("end_policy","regular_auto")
            listOf("offer_id","cancelled_offer_id","source_selection_id","previous_resume_ref","return_snapshot","practice_entry").forEach {put(it,JsonNull)}
            putJsonObject("auto_progress_ref") {put("auto_progress_id",progress.toString());put("version",version)}
        }
    }
    private suspend fun seedConfirmedNext() {
        val op=UUID.randomUUID();val ref=tagged("original_full")
        val request=buildJsonObject {
            put("schema_version","6");put("review_contract_version",2);put("operation_id",op.toString());put("profile_context",profile())
            put("stream_id",logical.toString());put("expected_stream_version",1);put("previous_selection_id",JsonNull);put("expected_settings_version",1)
            put("checkpoint_id",originalCheckpoint.toString());put("selection_kind","automatic_unheard");put("requested_article_id",JsonNull)
            put("trigger","explicit");put("expected_resume_version",0);put("intent_kind","explicit_start");put("origin_view","interests")
            put("requested_source_snapshot_id",JsonNull);put("request_channel","app")
        }
        codecs.validateDefinition("review2","ReviewNextRequest2",request)
        val bytes=incrementalCanonical(request);val hash=LearningJson.hash(bytes)
        val admission=buildJsonObject {put("operation_id",op.toString());put("request_hash",hash);put("accepted_at",accepted.toString());put("intent_kind","explicit_start")
            put("selection_intent_id",op.toString());put("activation_deadline_at",accepted.plusSeconds(10).toString())}
        val outcome=buildJsonObject {
            put("version",1);put("observed_at",accepted.toString());put("state","resumed");put("stream_version",2)
            put("selection",selection(originalSelection.toString(),ref));put("checkpoint",checkpoint(originalCheckpoint.toString(),originalSelection.toString(),ref))
            put("context",contextFor(originalSelection.toString(),2).getValue("context"));put("no_candidate_reason",JsonNull)
            putJsonArray("source_status") {add(buildJsonObject {
                put("key","a".repeat(64));put("kind","main_original");put("status","ready");put("component_id",UUID.randomUUID().toString())
                put("payload_hash","b".repeat(64));put("validation_revision","synthetic-proof");put("reason",JsonNull);put("retryable",false);put("retry_after",JsonNull)
            })}
        }
        codecs.validateDefinition("review2","ReviewNextResponse2",buildJsonObject {
            put("schema_version","6");put("review_contract_version",2);put("operation_id",op.toString());put("profile_context",profile());put("stream_id",logical.toString())
            put("admission_receipt",admission);put("outcome_projection",outcome)
        })
        val binding=PlaybackRequestBinding(owner,op,UUID.randomUUID(),UUID.randomUUID(),PlaybackRequestKind.EXPLICIT_START,
            PlaybackRequestChannel.APP,null,null,ListeningMode.ORIGINAL_FULL,0,10_000)
        val original=LocalPlaybackRequest(op.toString(),owner.profileId.toString(),0,1,binding.contextToken.toString(),binding.processToken.toString(),
            encodePlaybackBinding(binding,logical.toString()),hash,"active",0,null,null,0,null,false)
        val command=LocalReviewOperation(op.toString(),owner.profileId.toString(),0,1,"review2_next",logical.toString(),bytes,hash)
        db.serviceIntents().register(owner,original);db.review().queue(owner,command)
        db.review().acknowledge(owner,command,admission.toString())
        assertTrue(db.serviceIntents().advance(owner,original,original.copy(state="expired",polls=1,admissionJson=admission.toString(),outcomeJson=outcome.toString(),outcomeVersion=1)))
    }
    private suspend fun format(acknowledged:Boolean=true,source:UUID=originalSelection,progress:UUID=logical):Format {
        val op=UUID.randomUUID();val next=UUID.randomUUID();val point=UUID.randomUUID();val ref=tagged("briefing")
        val request=buildJsonObject {
            put("schema_version","6");put("operation_id",op.toString());put("profile_context",profile());put("stream_id",nativeStream.toString())
            put("expected_stream_version",7);put("expected_head_selection_id",originalSelection.toString());put("source_selection_id",source.toString())
            put("source_checkpoint_id",originalCheckpoint.toString());put("expected_checkpoint_version",1);put("checkpoint_id",point.toString());put("target_listening_mode","briefing")
        }
        val chosen=selection(next.toString(),ref,source.toString()).changed("previous_selection_id" to JsonPrimitive(originalSelection.toString()))
        val response=buildJsonObject {
            put("schema_version","6");put("operation_id",op.toString());put("profile_context",profile());put("stream_id",nativeStream.toString());put("stream_version",8)
            put("selection",chosen);put("checkpoint",checkpoint(point.toString(),next.toString(),ref))
        }
        codecs.validateDefinition("source6","PersonalFormatChangeRequest",request);codecs.validateDefinition("source6","PersonalFormatChangeResponse",response)
        val bytes=incrementalCanonical(request)
        val row=LocalReviewOperation(op.toString(),owner.profileId.toString(),0,1,"source6_format",source.toString(),bytes,LearningJson.hash(bytes))
        db.review().queue(owner,row)
        if(acknowledged)db.review().acknowledge(owner,row,response.toString())
        contexts[next.toString()]=contextFor(next.toString(),3,progress)
        return Format(row,response.toString(),next.toString())
    }
    private fun missing(value:AutoProgressHeadRecovery)=(value as AutoProgressHeadRecovery.Unavailable).reason

    @Test fun formatAckAndFreshContextExtendLogicalProofAcrossRestartWithoutNativeVersionGuessing()=runBlocking {
        val value=format();val repo=recovery()
        val first=repo.recordConfirmedFormatHead(scope,UUID.fromString(value.operation.operationId)) as AutoProgressHeadRecovery.Confirmed
        assertEquals(logical,first.head.autoProgressId);assertEquals(3L,first.head.version)
        assertEquals(nativeStream,first.head.nativeStreamId);assertEquals(UUID.fromString(value.selectionId),first.head.selectionId)
        val stored=db.review().source6Record(owner.profileId.toString(),0,"confirmed_auto_format_head",value.operation.operationId)!!
        assertEquals(1L,stored.version)
        val replay=repo.recordConfirmedFormatHead(scope,UUID.fromString(value.operation.operationId)) as AutoProgressHeadRecovery.Confirmed
        assertEquals(first.head,replay.head);assertEquals(stored,db.review().source6Record(owner.profileId.toString(),0,"confirmed_auto_format_head",value.operation.operationId))
        val count=gets.size
        assertEquals(first.head,(recovery().recover(scope,logical,3) as AutoProgressHeadRecovery.Confirmed).head)
        assertEquals(count,gets.size) // Durable recovery has no GET, format command or output side effect.
        assertEquals("CONFIRMED_LOGICAL_HEAD_REQUIRED",missing(recovery().recover(scope,nativeStream,8)))
    }
    @Test fun missingAckWrongPreviousOrProgressAndContradictoryFormatHeadsNeverBecomeAUsableHead()=runBlocking {
        val pending=format(acknowledged=false)
        assertEquals("INVALID_FORMAT_HEAD_PROOF",missing(recovery().recordConfirmedFormatHead(scope,UUID.fromString(pending.operation.operationId))))
        assertTrue(gets.isEmpty())
        val unrelated=format(source=UUID.randomUUID())
        assertEquals("INVALID_FORMAT_HEAD_PROOF",missing(recovery().recordConfirmedFormatHead(scope,UUID.fromString(unrelated.operation.operationId))))
        val foreignProgress=format(progress=UUID.randomUUID())
        assertEquals("CONFIRMED_LOGICAL_HEAD_REQUIRED",missing(recovery().recordConfirmedFormatHead(scope,UUID.fromString(foreignProgress.operation.operationId))))
        assertTrue(db.review().source6Records(owner.profileId.toString(),0,"confirmed_auto_format_head").isEmpty())
        val first=format();assertTrue(recovery().recordConfirmedFormatHead(scope,UUID.fromString(first.operation.operationId)) is AutoProgressHeadRecovery.Confirmed)
        val contradictory=format()
        assertEquals("CONTRADICTORY_LOGICAL_HEAD",missing(recovery().recordConfirmedFormatHead(scope,UUID.fromString(contradictory.operation.operationId))))
        assertEquals("CONTRADICTORY_LOGICAL_HEAD",missing(recovery().recover(scope,logical,3)))
    }
    @Test fun epochChangeDuringFreshContextReadCannotPersistOrRecoverOldFormatProof()=runBlocking {
        val value=format();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        beforeGet={entered.complete(Unit);release.await()}
        val call=async { recovery().recordConfirmedFormatHead(scope,UUID.fromString(value.operation.operationId)) }
        withTimeout(3_000){entered.await()}
        live=scope.copy(owner=owner.copy(privacyEpoch=1,revision=2))
        db.learning().putOwner(LocalOwner(profileId=owner.profileId.toString(),privacyEpoch=1,revision=2,status="active"))
        release.complete(Unit)
        assertEquals("SESSION_CHANGED",missing(call.await()))
        assertNull(db.review().source6Record(owner.profileId.toString(),0,"confirmed_auto_format_head",value.operation.operationId))
        assertEquals("SESSION_CHANGED",missing(recovery().recover(scope,logical,3)))
    }
    @Test fun legacySchemaFiveRequiresItsActualOriginalReceiptAndSurvivesRestartWithoutFabricatedWireSixAck()=runBlocking {
        val op=UUID.randomUUID();val next=UUID.randomUUID();val point=UUID.randomUUID()
        val legacyRef=tagged("briefing").getValue("legacy_ref").jsonObject
        val request=contractFixture("FormatChangeRequest").changed(
            "operation_id" to JsonPrimitive(op.toString()),"profile_context" to profile(),"stream_id" to JsonPrimitive(nativeStream.toString()),
            "expected_stream_version" to JsonPrimitive(7),"expected_head_selection_id" to JsonPrimitive(originalSelection.toString()),
            "source_selection_id" to JsonPrimitive(originalSelection.toString()),"source_checkpoint_id" to JsonPrimitive(originalCheckpoint.toString()),
            "expected_checkpoint_version" to JsonPrimitive(1),"checkpoint_id" to JsonPrimitive(point.toString()))
        val template=contractFixture("FormatChangeResponse")
        val chosen=template.getValue("selection").jsonObject.changed("selection_id" to JsonPrimitive(next.toString()),"stream_id" to JsonPrimitive(nativeStream.toString()),
            "previous_selection_id" to JsonPrimitive(originalSelection.toString()),"ordinal" to JsonPrimitive(2),"content_ref" to legacyRef,
            "format_change_source" to buildJsonObject {put("selection_id",originalSelection.toString());put("checkpoint_id",originalCheckpoint.toString())})
        val originalPoint=template.getValue("checkpoint").jsonObject
        val checkpoint=originalPoint.changed("record_id" to JsonPrimitive(point.toString()),"payload" to originalPoint.getValue("payload").jsonObject.changed(
            "checkpoint_id" to JsonPrimitive(point.toString()),"stream_id" to JsonPrimitive(nativeStream.toString()),"selection_id" to JsonPrimitive(next.toString()),"content_ref" to legacyRef))
        val receipt=template.changed("operation_id" to JsonPrimitive(op.toString()),"profile_context" to profile(),"stream_id" to JsonPrimitive(nativeStream.toString()),
            "stream_version" to JsonPrimitive(8),"selection" to chosen,"checkpoint" to checkpoint)
        val contracts=LearningContractJson(context)
        for((name,value) in listOf("FormatChangeRequest" to request,"FormatChangeResponse" to receipt))
            contracts.validate("personal-data.openapi.json",contracts.schema("personal-data.openapi.json","#/components/schemas/$name"),value)
        val requestJson=request.toString();val receiptJson=receipt.toString()
        val requestId=stableRecordId("request:$op");val receiptId=stableRecordId("receipt:$op")
        db.learning().saveRead(owner,readRecord(owner,requestId,"format_change_request",requestJson,1))
        contexts[next.toString()]=contextFor(next.toString(),3)
        fun legacyRecovery()=AutoProgressHeadRecoveryRepository(api,codecs,db.serviceIntents(),db.review(),db.learning())
        assertTrue(legacyRecovery().recordConfirmedFormatHead(scope,op) is AutoProgressHeadRecovery.Unavailable)
        assertTrue(gets.isEmpty());assertNull(db.review().source6Record(owner.profileId.toString(),0,"confirmed_auto_format_head",op.toString()))
        db.learning().saveRead(owner,readRecord(owner,receiptId,"format_change_receipt",receiptJson,1))
        val result=legacyRecovery().recordConfirmedFormatHead(scope,op) as AutoProgressHeadRecovery.Confirmed
        assertEquals(logical,result.head.autoProgressId);assertEquals(3L,result.head.version);assertEquals(next,result.head.selectionId)
        val count=gets.size
        assertEquals(result.head,(legacyRecovery().recover(scope,logical,3) as AutoProgressHeadRecovery.Confirmed).head)
        assertEquals(count,gets.size)
        assertEquals(requestJson,db.learning().record(owner.profileId.toString(),0,requestId.toString())!!.payloadJson)
        assertEquals(receiptJson,db.learning().record(owner.profileId.toString(),0,receiptId.toString())!!.payloadJson)
        assertNull(db.review().operation(op.toString())) // No synthetic source6_format/wire6 ACK is created.
        val stored=db.review().source6Record(owner.profileId.toString(),0,"confirmed_auto_format_head",op.toString())!!
        val proof=learningJson.parseToJsonElement(stored.payloadJson).jsonObject
        assertEquals(JsonPrimitive(requestJson),proof["legacy_request"])
        assertEquals(JsonPrimitive(receiptJson),proof["legacy_receipt"])
        assertEquals(JsonPrimitive(LearningJson.hash(requestJson)),proof["request_hash"])
        assertEquals(JsonPrimitive(LearningJson.hash(receiptJson)),proof["receipt_hash"])
    }

}
