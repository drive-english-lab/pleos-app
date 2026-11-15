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

/** Public-read fixtures only. No actual source approval, selection or TTS output. */
@RunWith(AndroidJUnit4::class)
class PendingOriginalMainReadTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val owner=OwnerContext(UUID.randomUUID(),0,1,UUID.randomUUID())
    private val scope=LearningCallScope(owner,LearningSession(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"synthetic-read-session"))
    private val article=UUID.randomUUID();private val snapshot=UUID.randomUUID();private val component=UUID.randomUUID();private val script=UUID.randomUUID()
    private val text="The synthetic original has two facts."
    private val hash=LearningJson.hash(text);private val key="a".repeat(64)
    private var live:LearningCallScope?=scope
    private lateinit var db:LearningDatabase
    private lateinit var repository:PreparedCatalogRepository
    private lateinit var target:WireReview3PendingTarget3
    private lateinit var row:JsonObject
    private var rows:List<JsonObject> = emptyList()
    private var gets=0;private var commands=0;private var afterGet:()->Unit={}

    @Before fun setup(){
        db=Room.inMemoryDatabaseBuilder(context,LearningDatabase::class.java).build()
        val codecs=IncrementalContractJson(context)
        val segment=SpeechSegment(UUID.randomUUID(),0,UUID.randomUUID(),0,text.codePointCount(0,text.length),hash)
        val provisional=SegmentManifest(UUID.randomUUID(),script,hash,1,"0".repeat(64),SegmentLanguage.EN,listOf(segment))
        val manifest=provisional.copy(manifestHash=LearningJson.hash(provisional.canonicalPayload()))
        val payload=buildJsonObject{
            put("script_id",script.toString());put("script_text",text);put("text_hash",hash);put("language","en");put("format","original_full")
            put("segment_manifest",learningJson.encodeToJsonElement(WireSource6SegmentManifest.serializer(),manifest.toWireSegmentManifest()))
        }
        val payloadHash=LearningJson.hash(incrementalCanonical(payload))
        row=buildJsonObject{
            put("component_id",component.toString());put("article_id",article.toString());put("dependency_key",key)
            put("source_snapshot_id",snapshot.toString());put("source_hash",hash);put("kind","main_original")
            put("payload_hash",payloadHash);put("rights_revision","synthetic-public-rights");put("payload",payload)
        };rows=listOf(row)
        target=WireReview3PendingTarget3(article.toString(),snapshot.toString(),hash,"ready",
            WireReview3ComponentState(key,"main_original","ready",component.toString(),payloadHash,null,false,null,"synthetic-validation"),
            WireReview3PendingProgressHead3(null,null,0,1))
        val public=CatalogRepository(LearningContractJson(context),object:CatalogHttpTransport{
            override suspend fun get(table:String,query:Map<String,String>):LearningHttpResponse {
                gets++;assertEquals("news_material_components",table)
                assertEquals("eq.$article",query["article_id"]);assertEquals("eq.$snapshot",query["source_snapshot_id"])
                assertEquals("eq.$hash",query["source_hash"]);assertEquals("eq.$component",query["component_id"])
                afterGet()
                return LearningHttpResponse(200,mapOf("Content-Type" to "application/json"),JsonArray(rows).toString().toByteArray())
            }
            override suspend fun getAuthenticated(table:String,query:Map<String,String>,authorization:String):LearningHttpResponse = error("Unselected B has no binding read")
        },"https://example.invalid")
        val api=IncrementalLearningApi(LearningSessionSource{live},codecs,object:LearningHttpTransport{
            override suspend fun exchange(method:String,path:String,query:Map<String,String>,headers:Map<String,String>,body:ByteArray?):LearningHttpResponse {
                commands++;error("Inspect must not prepare/select/generate/submit")
            }
        })
        repository=PreparedCatalogRepository(codecs,public,api,db.review())
    }
    @After fun close(){db.close()}
    private fun rowWith(key:String,value:JsonElement)=JsonObject(row+mapOf(key to value))
    private fun payloadWith(key:String,value:JsonElement)=rowWith("payload",JsonObject(row.getValue("payload").jsonObject+mapOf(key to value)))

    @Test fun exactBReturnsOnlyVerifiedOriginalEvidenceWithoutBindingOrCommand()=runBlocking{
        val result=repository.inspectPendingOriginal(scope,target) as CatalogRead.Stored<PendingOriginalMainEvidence>
        val proof=result.value
        assertEquals(article,proof.articleId);assertEquals(snapshot,proof.sourceSnapshotId);assertEquals(component,proof.componentId)
        assertEquals(script,proof.scriptId);assertEquals(hash,proof.scriptHash);assertEquals("en",proof.language)
        assertEquals(1,gets);assertEquals(0,commands);assertFalse(proof.toString().contains(text))
    }
    @Test fun noReadyOriginalDoesNotIssueAnyContentRead()=runBlocking{
        val unavailable=repository.inspectPendingOriginal(scope,target.copy(originalAvailability="unknown",originalComponent=null)) as CatalogRead.Unavailable
        assertEquals("ORIGINAL_NOT_READY",unavailable.code);assertEquals(0,gets);assertEquals(0,commands)
    }
    @Test fun absentOrRevokedPublicComponentDoesNotFallBackToCurrentA()=runBlocking{
        rows=emptyList()
        val result=repository.inspectPendingOriginal(scope,target) as CatalogRead.Unavailable
        assertEquals("MATERIAL_NOT_CURRENT",result.code);assertEquals(0,commands)
    }
    @Test fun wrongArticleSnapshotOrSourceHashCannotSatisfyB()=runBlocking{
        for((field,value) in listOf("article_id" to UUID.randomUUID().toString(),"source_snapshot_id" to UUID.randomUUID().toString(),"source_hash" to "b".repeat(64))){
            rows=listOf(rowWith(field,JsonPrimitive(value)))
            assertTrue(repository.inspectPendingOriginal(scope,target) is CatalogRead.Unavailable)
        };assertEquals(0,commands)
    }
    @Test fun wrongPinnedDependencyOrPayloadHashCannotSatisfyB()=runBlocking{
        for(field in listOf("dependency_key","payload_hash")){
            rows=listOf(rowWith(field,JsonPrimitive("c".repeat(64))))
            assertTrue(repository.inspectPendingOriginal(scope,target) is CatalogRead.Unavailable)
        };assertEquals(0,commands)
    }
    @Test fun changedTextWrongLanguageAndWrongManifestAreRejected()=runBlocking{
        rows=listOf(payloadWith("script_text",JsonPrimitive("Changed original text.")))
        assertTrue(repository.inspectPendingOriginal(scope,target) is CatalogRead.Unavailable)
        rows=listOf(payloadWith("language",JsonPrimitive("ko")))
        assertTrue(repository.inspectPendingOriginal(scope,target) is CatalogRead.Unavailable)
        val manifest=row.getValue("payload").jsonObject.getValue("segment_manifest").jsonObject
        rows=listOf(payloadWith("segment_manifest",JsonObject(manifest+mapOf("manifest_hash" to JsonPrimitive("d".repeat(64))))))
        assertTrue(repository.inspectPendingOriginal(scope,target) is CatalogRead.Unavailable)
        assertEquals(0,commands)
    }
    @Test fun ownerChangeDuringPublicReadDoesNotPublishReadinessEvidence()=runBlocking{
        afterGet={live=null}
        val result=repository.inspectPendingOriginal(scope,target) as CatalogRead.Unavailable
        assertEquals("SESSION_CHANGED",result.code);assertEquals(0,commands)
    }
}
