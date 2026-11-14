package io.github.driveenglishlab.pleos.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Strict source6 HTTP + real Room journal; synthetic ACKs are not live resume/vehicle evidence. */
@RunWith(AndroidJUnit4::class)
class Source6ResumeListeningRetryTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val owner=OwnerContext(UUID.randomUUID(),0,1,UUID.randomUUID())
    private val scope=LearningCallScope(owner,LearningSession(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"synthetic-resume-session"))
    private val target=UUID.randomUUID();private val operation=UUID.randomUUID()
    private val posts=mutableListOf<String>()
    private lateinit var db:LearningDatabase
    private lateinit var codecs:IncrementalContractJson
    private lateinit var repository:Source6ListeningRepository
    private lateinit var current:WireSource6PersonalProfileView
    private lateinit var point:WireSource6PersonalReadCheckpoint
    private var ack:WireSource6PersonalProfileView?=null
    private var dropFirstAck=false
    private var extraPoint:WireSource6PersonalReadCheckpoint?=null

    @Before fun setup()=runBlocking{
        db=Room.inMemoryDatabaseBuilder(context,LearningDatabase::class.java).build()
        db.learning().putOwner(LocalOwner(profileId=owner.profileId.toString(),privacyEpoch=0,revision=1,status="active"))
        codecs=IncrementalContractJson(context)
        current=WireSource6PersonalProfileView("6",WireSource6CommonProfileContext(owner.profileId.toString(),0),
            WireSource6CommonSettings(listOf("world"),"original_full","none",emptyList(),true,1),null,1,1,"anonymous",
            WireSource6CommonHistoryCoverage("known_new_profile",1,false))
        val script=UUID.randomUUID().toString();val hash="a".repeat(64)
        val ref=WireSource6PreparedContentRef6("prepared_components",UUID.randomUUID().toString(),UUID.randomUUID().toString(),hash,
            UUID.randomUUID().toString(),UUID.randomUUID().toString(),script,hash,"original_full")
        point=WireSource6PersonalReadCheckpoint(target.toString(),"checkpoint",1,WireSource6PersonalCheckpointRecord(target.toString(),ref,
            UUID.randomUUID().toString(),UUID.randomUUID().toString(),null,"unknown",WireSource6SegmentCursor6("segment",script,hash,
                UUID.randomUUID().toString(),1,"b".repeat(64),"main",UUID.randomUUID().toString(),0,"start",false,"user")))
        val api=IncrementalLearningApi(LearningSessionSource{scope},codecs,object:LearningHttpTransport{
            override suspend fun exchange(method:String,path:String,query:Map<String,String>,headers:Map<String,String>,body:ByteArray?):LearningHttpResponse {
                return when(path){
                    "/v2/profile/sync"->{
                        assertEquals("get",method.lowercase());assertNull(body)
                        val value=WireSource6PersonalSyncPage(current.profileContext,false,listOfNotNull(point,extraPoint),null,current,"6")
                        response("PersonalSyncPage",value,WireSource6PersonalSyncPage.serializer())
                    }
                    "/v2/profile/resume"->{
                        assertEquals("patch",method.lowercase())
                        val bytes=requireNotNull(body).toString(Charsets.UTF_8);posts+=bytes
                        val original=codecs.decodeSource6("PersonalResumeSelection",bytes,WireSource6PersonalResumeSelection.serializer())
                        assertEquals(operation.toString(),headers["Idempotency-Key"])
                        if(ack==null){
                            assertEquals(current.resumeVersion,original.expectedResumeVersion)
                            current=current.copy(activeResume=original.activeResume,version=current.version+1,resumeVersion=current.resumeVersion+1)
                            ack=current
                            if(dropFirstAck)throw IOException("Synthetic response lost after server commit")
                        }else assertEquals(posts.first(),bytes)
                        response("PersonalProfileView",requireNotNull(ack),WireSource6PersonalProfileView.serializer())
                    }
                    else->error("No new source, format, preparation or selection endpoint")
                }
            }
        })
        repository=Source6ListeningRepository(api,db.review(),codecs)
    }
    @After fun close(){db.close()}
    private fun <T> response(name:String,value:T,serializer:kotlinx.serialization.SerializationStrategy<T>):LearningHttpResponse =
        LearningHttpResponse(200,mapOf("Content-Type" to "application/json"),codecs.encode("source6",name,value,serializer).toByteArray())

    @Test fun lostAckRetryKeepsOriginalResumeCasAndReturnsOnlyActualSameTargetGet()=runBlocking{
        dropFirstAck=true
        val lost=repository.resumeListening(scope,operation,target) as LearningReply.Failed
        assertEquals("UPSTREAM_UNAVAILABLE",lost.code)
        assertEquals(1,posts.size)
        val savedPoint=requireNotNull(db.review().source6Record(owner.profileId.toString(),0,"checkpoint",target.toString()))
        val pointWire=codecs.encode("source6","PersonalReadRecord",point,WireSource6PersonalReadRecord.serializer())
        assertEquals(incrementalCanonical(codecs.parse("source6",pointWire)),savedPoint.payloadJson)
        assertEquals(LearningJson.hash(savedPoint.payloadJson),savedPoint.payloadHash)
        val pending=requireNotNull(db.review().operation(operation.toString()))
        assertEquals("pending",pending.status);assertNull(pending.receiptJson)
        val original=codecs.decodeSource6("PersonalResumeSelection",pending.payloadJson,WireSource6PersonalResumeSelection.serializer())
        assertEquals(1L,original.expectedResumeVersion)
        // A real later profile read has advanced beyond the archived ACK.
        current=current.copy(version=current.version+1,resumeVersion=current.resumeVersion+1)
        assertTrue(repository.snapshot(scope) is LearningReply.Stored)
        val retried=repository.resumeListening(scope,operation,target) as LearningReply.Stored<WireSource6PersonalProfileView>
        assertEquals(current,retried.value);assertEquals(3L,retried.value.resumeVersion)
        assertEquals(WireSource6PersonalResumeRef("listening",target.toString()),retried.value.activeResume)
        assertEquals(2,posts.size);assertEquals(posts[0],posts[1]);assertEquals(pending.payloadJson,posts[1])
        val confirmed=requireNotNull(db.review().operation(operation.toString()))
        assertEquals("acknowledged",confirmed.status);assertEquals(pending.payloadJson,confirmed.payloadJson);assertEquals(pending.payloadHash,confirmed.payloadHash)
        val originalAck=codecs.decodeSource6("PersonalProfileView",requireNotNull(confirmed.receiptJson),WireSource6PersonalProfileView.serializer())
        assertEquals(2L,originalAck.resumeVersion)
    }

    @Test fun acknowledgedRetryCannotPretendCurrentDifferentTargetIsResumed()=runBlocking{
        val selected=repository.resumeListening(scope,operation,target)
        assertTrue("Unexpected resume failure: ${(selected as? LearningReply.Failed)?.code}",selected is LearningReply.Stored)
        val original=requireNotNull(db.review().operation(operation.toString()))
        val other=UUID.randomUUID()
        extraPoint=point.copy(recordId=other.toString(),payload=point.payload.copy(checkpointId=other.toString()))
        current=current.copy(version=current.version+1,resumeVersion=current.resumeVersion+1,activeResume=WireSource6PersonalResumeRef("listening",other.toString()))
        assertTrue(repository.snapshot(scope) is LearningReply.Stored)
        val stale=repository.resumeListening(scope,operation,target) as LearningReply.Failed
        assertEquals("STALE_RESPONSE",stale.code);assertEquals(1,posts.size)
        assertEquals(original,db.review().operation(operation.toString()))
        val wrongTarget=repository.resumeListening(scope,operation,other) as LearningReply.Failed
        assertEquals("IMMUTABLE_OPERATION_CONFLICT",wrongTarget.code);assertEquals(1,posts.size)
    }
}
