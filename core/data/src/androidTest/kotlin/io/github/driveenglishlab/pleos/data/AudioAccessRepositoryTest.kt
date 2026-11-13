package io.github.driveenglishlab.pleos.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioAccessRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = LearningCallScope(OwnerContext(UUID.randomUUID(),0,1,UUID.randomUUID()),
        LearningSession(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"synthetic-user-token"))
    private val script = PublishedScript(UUID.randomUUID(),UUID.randomUUID(),CatalogScriptRole.ORIGINAL_FULL,null,
        "Synthetic.",LearningJson.hash("Synthetic."),UUID.randomUUID(),1000)
    private var elapsed = 100L
    private var current: LearningCallScope? = scope
    private var fail = false
    private var gate: CompletableDeferred<Unit>? = null
    private var bothEntered: CompletableDeferred<Unit>? = null
    private val operations = mutableListOf<String>()
    private fun repository(): AudioAccessRepository {
        val sessions=LearningSessionSource { current }
        val api=LearningApi(sessions,LearningContractJson(context),object:LearningHttpTransport {
            override suspend fun exchange(method:String,path:String,query:Map<String,String>,headers:Map<String,String>,body:ByteArray?):LearningHttpResponse {
                val input=learningJson.parseToJsonElement(body!!.toString(Charsets.UTF_8)).jsonObject
                val operation=input.getValue("operation_id").jsonPrimitive.content;operations+=operation
                if(operations.size==2)bothEntered?.complete(Unit)
                gate?.await()
                if(fail)return jsonResponse(buildJsonObject { put("schema_version","5");put("operation_id",operation)
                    putJsonObject("error") { put("code","UPSTREAM_UNAVAILABLE");put("retryable",true);put("message_ko","잠시 후 다시 시도해 주세요.") } },503)
                return jsonResponse(buildJsonObject { put("schema_version","5");put("operation_id",operation)
                    put("bundle_id",script.bundleId.toString());put("audio_asset_id",script.audioAssetId.toString());put("asset_hash","0".repeat(64))
                    put("content_type","audio/wav");put("duration_ms",1000);put("signed_url","https://synthetic.supabase.co/storage/v1/object/sign/news-audio/a.wav?token=synthetic")
                    put("expires_at","2026-11-14T00:00:00Z") })
            }
        })
        val catalog=CatalogRepository(LearningContractJson(context),object:CatalogHttpTransport {
            override suspend fun get(table:String,query:Map<String,String>):LearningHttpResponse=throw AssertionError("Do not re-resolve pinned news on URL renewal.")
        },"https://synthetic.supabase.co")
        return AudioAccessRepository(catalog,api,sessions) { elapsed }
    }

    @Test fun longPlaybackHintAndSeekRenewCredentialsWithoutSelectingAnotherArticle() = runBlocking {
        val repository=repository()
        val first=repository.load(scope,script) as LearningReply.Stored
        assertTrue(repository.load(scope,script) is LearningReply.Stored);assertEquals(1,operations.size)
        elapsed+=240_001
        assertTrue(repository.load(scope,script) is LearningReply.Stored);assertEquals(2,operations.size)
        assertNotEquals(operations[0],operations[1])
        assertTrue(repository.load(scope,script,forceRefresh=true) is LearningReply.Stored);assertEquals(3,operations.size)
        assertNotEquals(operations[1],operations[2]);assertEquals(script.audioAssetId,first.value.audioAssetId)
        assertFalse(first.value.toString().contains("token="))
    }

    @Test fun networkRetryKeepsOriginalOperationAndOwnerChangeCannotReturnCachedUrls() = runBlocking {
        val repository=repository();fail=true
        assertTrue(repository.load(scope,script) is LearningReply.Failed)
        fail=false;assertTrue(repository.load(scope,script) is LearningReply.Stored)
        assertEquals(operations[0],operations[1])
        current=scope.copy(owner=scope.owner.copy(profileId=UUID.randomUUID(),revision=2))
        assertEquals(LearningReply.Failed("SESSION_CHANGED",false),repository.load(scope,script))
        assertEquals(2,operations.size)
    }

    @Test fun simultaneousLoadsShareOneCredentialOperationAndOriginalReceipt() = runBlocking {
        val repository=repository();gate=CompletableDeferred();bothEntered=CompletableDeferred()
        val first=async { repository.load(scope,script) };val second=async { repository.load(scope,script) }
        withTimeout(3000) { bothEntered!!.await() };gate!!.complete(Unit)
        assertTrue(first.await() is LearningReply.Stored);assertTrue(second.await() is LearningReply.Stored)
        assertEquals(operations[0],operations[1])
    }

    @Test fun anExpiredCredentialOperationGetsANewIdWithoutChangingItsAsset() = runBlocking {
        val repository=repository();fail=true;assertTrue(repository.load(scope,script) is LearningReply.Failed)
        elapsed+=308_001;fail=false
        val reply=repository.load(scope,script) as LearningReply.Stored
        assertNotEquals(operations[0],operations[1]);assertEquals(script.audioAssetId,reply.value.audioAssetId)
    }
}
