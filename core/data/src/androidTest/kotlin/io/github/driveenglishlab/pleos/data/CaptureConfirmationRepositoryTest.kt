package io.github.driveenglishlab.pleos.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real in-memory Room + strict HTTP fixture. No Auth, microphone, SDK, server or model is invoked. */
@RunWith(AndroidJUnit4::class)
class CaptureConfirmationRepositoryTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db:LearningDatabase
    private lateinit var codecs:IncrementalContractJson
    private lateinit var api:IncrementalLearningApi
    private lateinit var repository:CaptureConfirmationRepository
    private val owner=OwnerContext(UUID.randomUUID(),0,1,UUID.randomUUID())
    private val call=LearningCallScope(owner,LearningSession(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"synthetic-session"))
    private val task=PracticeContentRef(ContentRef(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),ListeningMode.ORIGINAL_FULL),UUID.randomUUID(),UUID.randomUUID())
    private val original=Capture.unverified(CapturePath.PARKED_STT)
    private val text="An original answer with emoji 🛰️ and a quote \"yes\".\nNext line \\ remains."
    private val token=UUID.randomUUID()
    private var current=true
    private lateinit var transport:Transport
    @Before fun setup()=runBlocking {
        db=Room.inMemoryDatabaseBuilder(context,LearningDatabase::class.java).build()
        db.learning().putOwner(LocalOwner(profileId=owner.profileId.toString(),privacyEpoch=0,revision=1,status="active"))
        codecs=IncrementalContractJson(context);transport=Transport()
        api=IncrementalLearningApi(LearningSessionSource { if(current)call else null },codecs,transport)
        repository=CaptureConfirmationRepository(context,api,db)
    }
    @After fun close(){db.close()}
    private fun newLease(ref:PracticeRef=task)=CaptureLease(UUID.randomUUID(),UUID.randomUUID(),owner,ref,UUID.randomUUID(),2,3,4)
    private suspend fun prepare(lease:CaptureLease,protocol:CaptureConfirmationProtocol=CaptureConfirmationProtocol.LEGACY5)=
        repository.prepare(call,lease,original,text,token,protocol)
    private suspend fun ready(lease:CaptureLease,protocol:CaptureConfirmationProtocol=CaptureConfirmationProtocol.LEGACY5):CaptureConfirmationReceipt {
        assertTrue(prepare(lease,protocol) is LearningReply.Stored)
        return (repository.issue(call,lease.captureId,token){true} as LearningReply.Stored).value
    }
    private fun answer(lease:CaptureLease,ref:String,value:String=text)=AcceptedAnswer(lease,value,
        original.copy(spokenLanguage=SpokenLanguage.EN,englishEvidence=EnglishEvidence.SPECIFIC_CONFIRMATION,languageEvidenceRef=ref),HelpSnapshot())
    private suspend fun fails(block:suspend()->Unit){try{block();fail("Must reject and preserve original transaction")}catch(_:IllegalArgumentException){}}
    private inner class Transport:LearningHttpTransport {
        val requests=mutableListOf<String>()
        var fail=false
        var wrongHash=false
        var entered:CompletableDeferred<Unit>?=null
        var release:CompletableDeferred<Unit>?=null
        override suspend fun exchange(method:String,path:String,query:Map<String,String>,headers:Map<String,String>,body:ByteArray?):LearningHttpResponse {
            assertEquals("post",method.lowercase());assertEquals("/v1/capture-language-confirmations",path);assertTrue(query.isEmpty())
            val json=requireNotNull(body).toString(Charsets.UTF_8);requests+=json
            val request=codecs.decode("capture-confirmation","ConfirmationRequest",json,CaptureConfirmationRequest.serializer())
            assertEquals(request.captureId,headers["Idempotency-Key"]);assertEquals(call.session.installationId.toString(),headers["X-Installation-ID"])
            assertEquals(11,learningJson.parseToJsonElement(json).jsonObject.size)
            assertFalse(json.contains("answer_text"));assertFalse(json.contains(text))
            entered?.complete(Unit);release?.await()
            if(fail)return response(503,buildJsonObject{put("schema_version","5");put("operation_id",request.operationId);putJsonObject("error"){
                put("code","UPSTREAM_UNAVAILABLE");put("message_ko","확인 연결을 다시 시도해 주세요");put("retryable",true)}})
            val receipt=CaptureConfirmationReceipt(1,request.profileContext,request.operationId,"clc1:${request.captureId}",
                if(wrongHash)"0".repeat(64) else CaptureConfirmationHashes.binding(learningJson.parseToJsonElement(json).jsonObject))
            return response(200,learningJson.encodeToJsonElement(CaptureConfirmationReceipt.serializer(),receipt))
        }
    }
    private fun response(status:Int,value:JsonElement)=LearningHttpResponse(status,mapOf("Content-Type" to "application/json"),value.toString().toByteArray())

    @Test fun intentStoresOnlyExactMetadataWrapperAndExplicitRetryUsesOriginalRequest()=runBlocking {
        val lease=newLease();assertTrue(prepare(lease) is LearningReply.Stored)
        assertTrue(transport.requests.isEmpty())
        val before=requireNotNull(db.review().operation(lease.captureId.toString()))
        assertEquals(setOf("request","confirmation_token"),learningJson.parseToJsonElement(before.payloadJson).jsonObject.keys)
        assertFalse(before.payloadJson.contains("answer_text"));assertFalse(before.payloadJson.contains(text));assertEquals("pending",before.status)
        assertTrue(db.review().pending(owner.profileId.toString(),0,1).isEmpty()) // generic automatic flush excludes this kind.
        transport.fail=true;assertTrue(repository.issue(call,lease.captureId,token){true} is LearningReply.Failed)
        assertEquals(before,db.review().operation(before.operationId))
        transport.fail=false;assertTrue(repository.issue(call,lease.captureId,token){true} is LearningReply.Stored)
        assertEquals(2,transport.requests.size);assertEquals(transport.requests.first(),transport.requests.last())
        assertEquals("receipt_ready",db.review().operation(before.operationId)!!.status)
        assertEquals(before.payloadJson,db.review().operation(before.operationId)!!.payloadJson)
        assertNull(db.learning().record(owner.profileId.toString(),0,lease.attemptId.toString()))
    }
    @Test fun missingIntentWrongTokenOrWrongBindingNeverSendsOrMakesPositiveReceipt()=runBlocking {
        val lease=newLease()
        assertTrue(repository.issue(call,lease.captureId,token){true} is LearningReply.Failed);assertTrue(transport.requests.isEmpty())
        assertTrue(prepare(lease) is LearningReply.Stored)
        assertTrue(repository.issue(call,lease.captureId,UUID.randomUUID()){true} is LearningReply.Failed);assertTrue(transport.requests.isEmpty())
        transport.wrongHash=true
        assertTrue(repository.issue(call,lease.captureId,token){true} is LearningReply.Failed)
        assertNull(db.review().operation(lease.captureId.toString())!!.receiptJson)
        assertTrue(repository.prepare(call,lease,original,text+"changed",token,CaptureConfirmationProtocol.LEGACY5) is LearningReply.Failed)
        assertEquals(1,transport.requests.size)
    }
    @Test fun cancellationWinsOverActualLateReceiptAndProcessRestoreOrphansWithoutHttpOrAcceptance()=runBlocking {
        val lease=newLease();assertTrue(prepare(lease) is LearningReply.Stored)
        transport.entered=CompletableDeferred();transport.release=CompletableDeferred()
        val pending=async { repository.issue(call,lease.captureId,token){true} }
        withTimeout(3_000){transport.entered!!.await()}
        repository.cancel(owner,lease.captureId,token)
        transport.release!!.complete(Unit);assertTrue(pending.await() is LearningReply.Failed)
        val cancelled=db.review().operation(lease.captureId.toString())!!
        assertEquals("cancelled",cancelled.status);assertNotNull(cancelled.receiptJson)
        assertTrue(repository.issue(call,lease.captureId,token){true} is LearningReply.Failed)
        val other=newLease();assertTrue(prepare(other) is LearningReply.Stored)
        val restored=CaptureConfirmationRepository(context,api,db);restored.orphanUnaccepted(owner)
        assertEquals("orphaned",db.review().operation(other.captureId.toString())!!.status)
        assertTrue(restored.issue(call,other.captureId,token){true} is LearningReply.Failed)
        assertEquals(1,transport.requests.size);assertTrue(db.learning().pending(owner.profileId.toString(),0).isEmpty())
    }
    @Test fun legacyAnswerOutboxAndAcceptedMarkerAreOneTransactionAndImmutableOnRedelivery()=runBlocking {
        val lease=newLease();val receipt=ready(lease);val store=LearningStore(db)
        fails { store.acceptAnswer(answer(lease,receipt.confirmationRef,"Different answer"),"first",null) }
        assertNull(db.learning().record(owner.profileId.toString(),0,lease.attemptId.toString()))
        assertTrue(db.learning().pending(owner.profileId.toString(),0).isEmpty())
        assertEquals("receipt_ready",db.review().operation(lease.captureId.toString())!!.status)
        assertTrue(store.acceptAnswer(answer(lease,receipt.confirmationRef),"first",null))
        assertEquals("accepted",db.review().operation(lease.captureId.toString())!!.status)
        val preserved=db.learning().record(owner.profileId.toString(),0,lease.attemptId.toString())!!
        assertEquals(1,db.learning().pending(owner.profileId.toString(),0).size)
        assertFalse(store.acceptAnswer(answer(lease,receipt.confirmationRef),"first",null))
        assertEquals(preserved,db.learning().record(owner.profileId.toString(),0,lease.attemptId.toString()))
        repository.orphanUnaccepted(owner);assertEquals("accepted",db.review().operation(lease.captureId.toString())!!.status)
    }
    @Test fun source6AnswerFenceAndConfirmationCommitTogetherWithoutChangingNativeReference()=runBlocking {
        val ref=PreparedPracticeRef(PreparedContentRef(UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"b".repeat(64),ListeningMode.BRIEFING),UUID.randomUUID(),UUID.randomUUID(),"fixture-task-v1")
        val lease=newLease(ref);val receipt=ready(lease,CaptureConfirmationProtocol.SOURCE6)
        val review=ReviewRepository(api,db.review(),ReviewJournal(db.review(),codecs),codecs)
        val practice=Source6PracticeRepository(api,db.review(),codecs,review,{_,_,_->true})
        val lesson=LessonInput(lease.lessonId,ref,LessonInputPhase.QUESTION_WAIT,HelpSnapshot(),null,DialogueProgress(false))
        val session=PracticeSession(owner,lesson,"What changed?",PracticeProgress(1,null,null,null,null,"question_wait",null,null,null))
        val stream=UUID.randomUUID()
        fails { practice.persistAccepted(answer(lease,receipt.confirmationRef,"Changed"),session,stream,0) }
        assertNull(db.review().operation(lease.attemptId.toString()))
        assertNull(db.review().operation(stableRecordId("source6-assistance-fence:${lease.attemptId}").toString()))
        assertEquals("receipt_ready",db.review().operation(lease.captureId.toString())!!.status)
        practice.persistAccepted(answer(lease,receipt.confirmationRef),session,stream,0)
        val attempt=db.review().operation(lease.attemptId.toString())!!
        assertEquals("accepted",db.review().operation(lease.captureId.toString())!!.status)
        assertNotNull(db.review().operation(stableRecordId("source6-assistance-fence:${lease.attemptId}").toString()))
        assertEquals(CaptureConfirmationWireRules.ref(ref,CaptureConfirmationProtocol.SOURCE6),learningJson.parseToJsonElement(attempt.payloadJson).jsonObject.getValue("content_ref"))
    }
    @Test fun epochDeletionAndOwnerIsolationCannotReviveAReceiptOrAConfirmedAnswer()=runBlocking {
        val lease=newLease();val receipt=ready(lease)
        db.review().isolateLearning(owner.profileId.toString())
        assertEquals("isolated",db.review().operation(lease.captureId.toString())!!.status)
        fails { LearningStore(db).acceptAnswer(answer(lease,receipt.confirmationRef),"first",null) }
        assertNull(db.learning().record(owner.profileId.toString(),0,lease.attemptId.toString()))
        db.review().deleteConfirmedEpoch(owner.profileId.toString(),0)
        db.learning().putOwner(LocalOwner(profileId=owner.profileId.toString(),privacyEpoch=1,revision=2,status="active"))
        assertTrue(repository.issue(call,lease.captureId,token){true} is LearningReply.Failed)
        assertNull(db.review().operation(lease.captureId.toString()));assertEquals(1,transport.requests.size)
    }
    @Test fun independentPackRejectsExtraMissingNullWrongProtocolAndSemanticIdentity()=runBlocking {
        val lease=newLease();val request=(prepare(lease) as LearningReply.Stored).value.request
        val json=learningJson.encodeToJsonElement(CaptureConfirmationRequest.serializer(),request).jsonObject
        assertEquals(11,json.size)
        suspend fun rejected(value:JsonObject)=fails{codecs.decode("capture-confirmation","ConfirmationRequest",value.toString(),CaptureConfirmationRequest.serializer())}
        rejected(JsonObject(json + ("answer_text" to JsonPrimitive("must not be sent"))))
        rejected(JsonObject(json - "answer_sha256"))
        rejected(JsonObject(json + ("answer_sha256" to JsonNull)))
        rejected(JsonObject(json + ("confirmed_language" to JsonPrimitive("ko"))))
        rejected(JsonObject(json + ("source_protocol" to JsonPrimitive("source6"))))
        rejected(JsonObject(json + ("operation_id" to JsonPrimitive(UUID.randomUUID().toString()))))
        val raised=JsonObject(json.getValue("original_capture").jsonObject + ("form_fidelity" to JsonPrimitive("confirmed_form")))
        rejected(JsonObject(json + ("original_capture" to raised)))
        repository.cancel(owner,UUID.randomUUID(),token) // Closing ASKING before metadata exists is an idempotent no-op.
    }

    @Test fun pythonCanonicalParityPreservesUnicodeLfQuotesArraysAndAllThreeNativeReferenceShapes() {
        val source=InstrumentationRegistry.getInstrumentation().context.assets.open("capture-confirmation-parity.json").bufferedReader().use{it.readText()}
        assertEquals("b1bb1d8fed1518952f3e49bbfbd8708f403c4032d4a7ac951bce8541498d7366",LearningJson.hash(source))
        val fixture=learningJson.parseToJsonElement(source).jsonObject
        assertEquals("capture-confirmation-parity-v1",fixture.getValue("format").jsonPrimitive.content)
        val vectors=fixture.getValue("vectors").jsonArray;assertEquals(3,vectors.size)
        for(vector in vectors) {
            val item=vector.jsonObject;val request=item.getValue("request").jsonObject
            val decoded=codecs.decode("capture-confirmation","ConfirmationRequest",request.toString(),CaptureConfirmationRequest.serializer())
            assertEquals(item.getValue("answer_sha256").jsonPrimitive.content,CaptureConfirmationHashes.answer(item.getValue("normalized_answer").jsonPrimitive.content))
            assertEquals(item.getValue("binding_hash").jsonPrimitive.content,CaptureConfirmationHashes.binding(request))
            assertEquals(item.getValue("canonical_request_utf8_hex").jsonPrimitive.content,
                CaptureConfirmationHashes.canonical(request).toByteArray(Charsets.UTF_8).joinToString(""){"%02x".format(it)})
            val receipt=codecs.decode("capture-confirmation","ConfirmationReceipt",item.getValue("receipt").toString(),CaptureConfirmationReceipt.serializer())
            CaptureConfirmationWireRules.receipt(decoded,receipt)
            assertEquals(request.getValue("content_ref"),learningJson.encodeToJsonElement(CaptureConfirmationRequest.serializer(),decoded).jsonObject.getValue("content_ref"))
        }
    }

}
