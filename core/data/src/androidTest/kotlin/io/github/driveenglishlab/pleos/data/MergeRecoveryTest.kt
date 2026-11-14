package io.github.driveenglishlab.pleos.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual strict v5 HTTP + Room/protected stores, synthetic provider/server only. */
@RunWith(AndroidJUnit4::class)
class MergeRecoveryTest {
    private val base get()=InstrumentationRegistry.getInstrumentation().targetContext
    private class Fixture(base:Context):AutoCloseable {
        val directory=File(base.cacheDir,"merge-fixture-${UUID.randomUUID()}").apply{mkdirs()}
        val packageId="${base.packageName}.mergefixture.${UUID.randomUUID()}"
        val context=object:ContextWrapper(base){override fun getNoBackupFilesDir()=directory;override fun getPackageName()=packageId}
        val db=Room.inMemoryDatabaseBuilder(base,LearningDatabase::class.java).build()
        val contracts=LearningContractJson(base)
        lateinit var source:LocalOwner
        lateinit var initial:ProtectedMerge
        lateinit var originalSource:ByteArray
        val sourceSubject=UUID.randomUUID();val targetSubject=UUID.randomUUID();val installation=UUID.randomUUID()
        val sourceSession=LearningSession(sourceSubject,UUID.randomUUID(),installation,"synthetic-source")
        val targetSession=LearningSession(targetSubject,UUID.randomUUID(),installation,"synthetic-target")
        val targetProfile=UUID.randomUUID()
        var sourceKind="anonymous";var sourceEpoch=0L;var targetEpoch=7L;var sourceAllowed=true;var permitted=true
        var completeError:String?="MERGE_EXPIRED";var loseComplete=false;var losePrepare=false;var targetReadError=false
        var beforePrepare:suspend()->Unit={};var afterPrepare:suspend()->Unit={}
        var afterReceipt:suspend()->Unit={}
        var sourceAuthCalls=0;var sourceReads=0;var signIns=0
        val completes=mutableListOf<String>();val prepares=mutableListOf<String>()
        val intents=mutableMapOf<String,WirePersonalMergeIntent>();val receipts=mutableMapOf<String,WirePersonalMergeReceipt>()
        val sourceAuth=object:ProfileAuth {
            override suspend fun ensureSession(allowCreate:Boolean,beforeCreate:suspend()->Unit):LearningSession{check(!allowCreate);return sourceSession}
            override suspend fun currentSession()=sourceSession
            override suspend fun bootstrapOperation(profileId:UUID)=UUID.randomUUID()
        }
        val profiles by lazy {ProfileRepository(db,sourceAuth){sessions->api(sessions)}}
        private fun profile(id:String,epoch:Long,kind:String)=WirePersonalProfileView(
            "5",WireCommonProfileContext(id,epoch),WireCommonSettings(emptyList(),"original_full",null,emptyList(),false,1),null,1,1,kind,
            WireCommonHistoryCoverage("known_new_profile",1,false))
        private fun encodedProfile(id:String,epoch:Long,kind:String)=learningJson.encodeToString(WirePersonalProfileView.serializer(),profile(id,epoch,kind))
        private fun json(body:String,status:Int=200)=LearningHttpResponse(status,mapOf("Content-Type" to "application/json"),body.toByteArray())
        private fun error(code:String,operation:String,status:Int=409)=json(buildJsonObject{
            put("schema_version","5");put("operation_id",operation);putJsonObject("error"){put("code",code);put("retryable",status>=500);put("message_ko","합성 검증 오류")}
        }.toString(),status)
        private val transport=object:LearningHttpTransport {
            override val configuredOrigin="https://merge-recovery.fixture"
            override suspend fun exchange(method:String,path:String,query:Map<String,String>,headers:Map<String,String>,body:ByteArray?):LearningHttpResponse {
                val isSource=headers["Authorization"]=="Bearer synthetic-source"
                if(path=="/v1/profile/bootstrap") {
                    val operation=Json.parseToJsonElement(requireNotNull(body).toString(Charsets.UTF_8)).jsonObject.getValue("operation_id").jsonPrimitive.content
                    if(targetReadError)return error("UPSTREAM_UNAVAILABLE",operation,503)
                    return json(encodedProfile(targetProfile.toString(),targetEpoch,"linked"))
                }
                if(path=="/v1/profile" && method=="get") {
                    if(isSource){sourceReads++;check(db.learning().currentOwner()?.status=="transition");return json(encodedProfile(source.profileId,sourceEpoch,sourceKind))}
                    return json(encodedProfile(targetProfile.toString(),targetEpoch,"linked"))
                }
                if(path=="/v1/profile/sync" && method=="get") {
                    check(isSource && db.learning().currentOwner()?.status=="transition")
                    val page=WirePersonalSyncPage(WireCommonProfileContext(source.profileId,sourceEpoch),false,emptyList(),"synthetic-source-cursor",profile(source.profileId,sourceEpoch,sourceKind),"5")
                    return json(learningJson.encodeToString(WirePersonalSyncPage.serializer(),page))
                }
                if(path=="/v1/profile/merges/prepare") {
                    check(isSource && db.learning().currentOwner()?.status=="transition")
                    val text=requireNotNull(body).toString(Charsets.UTF_8);prepares+=text
                    val request=learningJson.decodeFromString(WirePersonalMergePrepare.serializer(),text)
                    check(headers["Idempotency-Key"]==request.operationId)
                    beforePrepare()
                    val value=intents.getOrPut(request.operationId){WirePersonalMergeIntent(UUID.randomUUID().toString(),source.profileId,sourceEpoch,"synthetic-new-proof-token-"+"n".repeat(40),"2099-01-01T00:00:00Z","5")}
                    if(losePrepare){losePrepare=false;throw java.io.IOException("Synthetic lost prepare reply")}
                    afterPrepare()
                    return json(learningJson.encodeToString(WirePersonalMergeIntent.serializer(),value))
                }
                if(path=="/v1/profile/merges/complete") {
                    check(!isSource && db.learning().currentOwner()?.status=="transition")
                    val text=requireNotNull(body).toString(Charsets.UTF_8);completes+=text
                    val request=learningJson.decodeFromString(WirePersonalMergeComplete.serializer(),text)
                    check(headers["Idempotency-Key"]==request.operationId)
                    if(request.operationId==initial.completeOperation && completeError!=null)return error(requireNotNull(completeError),request.operationId,if(completeError=="UPSTREAM_UNAVAILABLE")503 else 409)
                    // Model the real old-epoch-before-receipt SQL gate; do not invent receipt lookup compatibility.
                    if(request.profileContext.privacyEpoch!=targetEpoch)return error("STALE_EPOCH",request.operationId)
                    val receipt=receipts.getOrPut(request.operationId){WirePersonalMergeReceipt(request.operationId,source.profileId,profile(targetProfile.toString(),targetEpoch,"linked"),"synthetic-source-cursor",1,0,true,"5")}
                    if(loseComplete){loseComplete=false;throw java.io.IOException("Synthetic lost complete reply")}
                    afterReceipt()
                    return json(learningJson.encodeToString(WirePersonalMergeReceipt.serializer(),receipt))
                }
                throw AssertionError("Only existing profile/sync/prepare/complete routes authorized")
            }
        }
        fun api(sessions:LearningSessionSource)=LearningApi(sessions,contracts,transport)
        fun account()=AccountRepository(context,db,profiles,"https://merge-recovery.fixture").also{account->
            account.mergeApiFactory={sessions->api(sessions)}
            account.mergeSourceAuthFactory={store->auth(store,true)}
            account.mergeTargetAuthFactory={store->auth(store,false)}
        }
        private fun auth(store:AuthSessionStore,isSource:Boolean)=object:LinkedTargetAuth {
            override suspend fun signInGoogle(idToken:String,nonce:String?){signIns++;throw AssertionError("Protected-session retry must not request credentials")}
            override suspend fun ensureSession(allowCreate:Boolean,beforeCreate:suspend()->Unit):LearningSession {
                check(!allowCreate && store.readExistingIdentity()?.session!=null)
                if(isSource){sourceAuthCalls++;check(sourceAllowed);return sourceSession}
                return targetSession
            }
            override suspend fun currentSession()=if(isSource)sourceSession else targetSession
            override suspend fun bootstrapOperation(profileId:UUID)=UUID.randomUUID()
            override suspend fun close(){}
        }
        suspend fun initialize(legacyMissingBody:Boolean=false,freshSource:Boolean=false) {
            source=db.learning().provisionalOwner().copy(status="active")
            db.learning().putOwner(source)
            val intent=WirePersonalMergeIntent(UUID.randomUUID().toString(),source.profileId,0,"synthetic-original-proof-token-"+"o".repeat(40),"2099-01-01T00:00:00Z","5")
            val operation=UUID.randomUUID().toString()
            val request=WirePersonalMergeComplete(operation,WireCommonProfileContext(targetProfile.toString(),7),intent.intentId,intent.proofToken,"5")
            val body=learningJson.encodeToString(WirePersonalMergeComplete.serializer(),request)
            // Fixture preconditions use the same closed wire schema as the real HTTP boundary.
            contracts.validate("personal-data.openapi.json",contracts.schema("personal-data.openapi.json","#/components/schemas/MergeIntent"),learningJson.encodeToJsonElement(WirePersonalMergeIntent.serializer(),intent))
            contracts.validate("personal-data.openapi.json",contracts.schema("personal-data.openapi.json","#/components/schemas/MergeComplete"),learningJson.encodeToJsonElement(WirePersonalMergeComplete.serializer(),request))
            initial=ProtectedMerge(intent,operation,source.profileId,0,source.revision,sourceSubject.toString(),
                sourceReference="learning-auth-v1",sourceInstallation=installation.toString(),targetSubject=targetSubject.toString(),targetInstallation=installation.toString(),targetSession=targetSession.sessionId.toString(),
                completeJson=if(legacyMissingBody)null else body,completeHash=if(legacyMissingBody)null else LearningJson.hash(body),completeDispatched=!legacyMissingBody)
            fun session(id:UUID,token:String)=UserSession(token,"synthetic-refresh",expiresIn=3600,tokenType="bearer",user=UserInfo(aud="authenticated",id=id.toString()))
            AuthSessionStore(context).initializeIsolated(StoredIdentity(installationId=installation.toString(),creationStarted=true,session=session(sourceSubject,"synthetic-source-access"),accountState=learningJson.encodeToString(ProtectedMerge.serializer(),initial)))
            targetStore(initial).initializeIsolated(StoredIdentity(installationId=installation.toString(),creationStarted=true,session=session(targetSubject,"synthetic-target-access"),accountState=learningJson.encodeToString(ProtectedMerge.serializer(),initial)))
            originalSource=File(directory,"learning-auth-v1.enc").readBytes()
            if(!freshSource)db.learning().fenceOwner(OwnerContext(UUID.fromString(source.profileId),0,source.revision,UUID.randomUUID()),UUID.fromString(operation),"merge","learning-merge-target-${intent.intentId}")
        }
        fun targetStore(value:ProtectedMerge)=AuthSessionStore(context,"learning-merge-target-${value.intent.intentId}")
        fun saved(value:ProtectedMerge=initial)=learningJson.decodeFromString(ProtectedMerge.serializer(),requireNotNull(targetStore(value).readExistingIdentity()?.accountState))
        fun assertSourcePreserved(){assertArrayEquals(originalSource,File(directory,"learning-auth-v1.enc").readBytes())}
        fun journalStatus(op:String)=db.openHelper.writableDatabase.query("SELECT status FROM owner_journal WHERE operationId=?",arrayOf<Any>(op)).use{check(it.moveToFirst());it.getString(0)}
        override fun close(){db.close();directory.deleteRecursively();val keys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};keys.aliases().toList().filter{it.startsWith("$packageId.")}.forEach(keys::deleteEntry)}
    }

    @Test fun definiteCompleteRejectionRequiresExplicitSourceVerificationAndDurablyLinksNewMerge()=runBlocking {
        Fixture(base).use{f->
            f.initialize();val accounts=f.account()
            assertEquals("MERGE_EXPIRED",(accounts.recoverMerge() as LearningReply.Failed).code)
            assertEquals(MergeRecoveryPhase.REPREPARE_REQUIRED,accounts.pendingMergeRecovery()?.phase)
            assertEquals("reprepare_required",f.journalStatus(f.initial.completeOperation));f.assertSourcePreserved()
            assertEquals("MERGE_REPREPARE_REQUIRED",(accounts.recoverMerge() as LearningReply.Failed).code)
            assertTrue(f.prepares.isEmpty());assertEquals(1,f.completes.size)
            assertTrue(accounts.recoverMerge(true){true} is LearningReply.Stored)
            assertEquals(1,f.prepares.size);assertEquals(2,f.completes.size);assertEquals(0,f.signIns)
            assertEquals(f.targetProfile.toString(),f.db.learning().currentOwner()?.profileId)
            val old=f.saved();assertEquals(f.initial.completeJson,old.completeJson);assertEquals(f.initial.completeHash,old.completeHash)
            val replacement=requireNotNull(old.replacement);val child=f.saved(ProtectedMerge(requireNotNull(replacement.intent),replacement.completeOperation,old.sourceProfile,old.sourceEpoch,old.sourceRevision,old.sourceSubject))
            assertEquals(old.completeOperation,child.predecessorOperation)
            assertEquals("reprepared",f.journalStatus(old.completeOperation));assertEquals("complete",f.journalStatus(child.completeOperation))
            assertFalse(accounts.hasPendingAccountTransition())
            assertTrue(f.db.learning().pendingOwnerTransitions().isEmpty()) // Terminal parent lineage cannot block a later account switch.
            val next=accounts.prepareLinkedTarget(AccountTargetPurpose.SWITCH_LINKED)
            assertTrue(next is AccountTargetPreparation.Ready)
            assertTrue(accounts.cancelLinkedTarget((next as AccountTargetPreparation.Ready).attempt.operationId))
        }
    }

    @Test fun lostCompleteReplyReusesExactOriginalAndAdoptsOnlyStoredReceiptWithLatestRead()=runBlocking {
        Fixture(base).use{f->
            f.initialize();f.completeError=null;f.loseComplete=true
            val accounts=f.account();assertTrue(accounts.recoverMerge() is LearningReply.Failed)
            val saved=f.saved();assertEquals(f.initial.completeJson,saved.completeJson)
            f.afterReceipt={f.targetEpoch=8} // A newer deletion happens after the valid receipt response, before the latest-profile read.
            assertTrue(f.account().recoverMerge() is LearningReply.Stored)
            assertEquals(2,f.completes.size);assertEquals(f.completes.first(),f.completes.last())
            assertTrue(f.prepares.isEmpty());assertEquals(0,f.sourceReads);assertEquals(0,f.sourceAuthCalls)
            assertEquals(8L,f.db.learning().currentOwner()?.privacyEpoch)
        }
    }

    @Test fun oldServerConflictEpochAndLegacyUnknownBodyNeverGrantReprepareOrRewriteTargetEpoch()=runBlocking {
        for(code in listOf("RECORD_CONFLICT","STALE_EPOCH","IDEMPOTENCY_CONFLICT","UPSTREAM_UNAVAILABLE"))Fixture(base).use{f->
            f.initialize();f.completeError=code
            val accounts=f.account();assertTrue(accounts.recoverMerge() is LearningReply.Failed)
            assertEquals(MergeRecoveryPhase.RECONCILING,accounts.pendingMergeRecovery()?.phase)
            f.targetEpoch=8
            assertTrue(f.account().recoverMerge(true){true} is LearningReply.Failed)
            assertTrue(f.prepares.isEmpty());assertEquals(0,f.sourceReads);assertEquals(0,f.sourceAuthCalls)
            assertEquals(2,f.completes.size);assertEquals(f.completes[0],f.completes[1])
            assertEquals(f.initial.completeJson,f.saved().completeJson);assertEquals(f.initial.completeHash,f.saved().completeHash)
            assertEquals("transition",f.db.learning().currentOwner()?.status);f.assertSourcePreserved()
        }
        Fixture(base).use{f->
            f.initialize(legacyMissingBody=true)
            assertEquals("MERGE_ORIGINAL_REQUEST_UNAVAILABLE",(f.account().recoverMerge(true){true} as LearningReply.Failed).code)
            assertTrue(f.prepares.isEmpty());assertTrue(f.completes.isEmpty());f.assertSourcePreserved()
        }
    }

    @Test fun lostPrepareWithoutAnyChildCompleteCanBeReissuedOnlyOnExplicitRetryAndOldLineageIsKept()=runBlocking {
        Fixture(base).use{f->
            f.initialize();val accounts=f.account();accounts.recoverMerge();f.losePrepare=true
            assertTrue(accounts.recoverMerge(true){true} is LearningReply.Failed)
            val first=requireNotNull(f.saved().replacement);assertTrue(first.prepareDispatched);assertNull(first.intent);assertNull(first.childCompleteJson)
            assertEquals(1,f.prepares.size);assertEquals(1,f.completes.size)
            assertEquals("MERGE_REPREPARE_REQUIRED",(f.account().recoverMerge() as LearningReply.Failed).code)
            assertEquals(1,f.prepares.size)
            assertTrue(f.account().recoverMerge(true){true} is LearningReply.Stored)
            val old=f.saved();assertEquals(listOf(first),old.abandonedPreparations)
            assertNotEquals(first.prepareOperation,old.replacement?.prepareOperation)
            assertEquals(2,f.prepares.size);assertEquals(2,f.completes.size);assertEquals(0,f.signIns)
        }
    }

    @Test fun sourceProofEpochAndLateOwnerChangesPreserveFencesAndCannotStartOrAdoptAnotherMerge()=runBlocking {
        Fixture(base).use{f->
            f.initialize();val accounts=f.account();accounts.recoverMerge()
            f.sourceKind="linked"
            assertEquals("MERGE_SOURCE_OWNERSHIP_CHANGED",(accounts.recoverMerge(true){true} as LearningReply.Failed).code)
            f.sourceKind="anonymous";f.sourceEpoch=1
            assertEquals("MERGE_SOURCE_EPOCH_CHANGED",(accounts.recoverMerge(true){true} as LearningReply.Failed).code)
            f.sourceEpoch=0;f.sourceAllowed=false
            assertTrue(accounts.recoverMerge(true){true} is LearningReply.Failed)
            assertTrue(f.prepares.isEmpty());assertEquals(0L,f.db.learning().currentOwner()?.privacyEpoch);assertEquals("transition",f.db.learning().currentOwner()?.status)
            f.sourceAllowed=true
            val other=LocalOwner(profileId=UUID.randomUUID().toString(),privacyEpoch=0,revision=20,status="provisional")
            f.beforePrepare={f.db.learning().putOwner(other)}
            assertTrue(accounts.recoverMerge(true){true} is LearningReply.Failed)
            assertEquals(other,f.db.learning().currentOwner());assertNull(accounts.pendingMergeRecovery())
            assertEquals(1,f.completes.size);f.assertSourcePreserved()
        }
    }

    @Test fun firstTargetReadFailureAndPreFenceInterruptionRemainRetryableWithoutAnotherCredential()=runBlocking {
        Fixture(base).use{f->
            f.initialize(legacyMissingBody=true,freshSource=true);f.targetReadError=true
            val accounts=f.account()
            assertEquals("UPSTREAM_UNAVAILABLE",(accounts.signInExistingGoogle("",null){true} as LearningReply.Failed).code)
            assertEquals(f.source,f.db.learning().currentOwner());assertTrue(f.db.learning().pendingOwnerTransitions().isEmpty());f.assertSourcePreserved()
            assertTrue(accounts.hasPendingAccountTransition())
            assertEquals("MERGE_CONFIRMATION_REQUIRED",(accounts.recoverMerge() as LearningReply.Failed).code)
            f.targetReadError=false
            assertEquals("ACCOUNT_ACTION_CONTEXT_CHANGED",(accounts.recoverMerge(true){f.saved().completeJson==null} as LearningReply.Failed).code)
            val frozen=f.saved();assertNotNull(frozen.completeJson);assertFalse(frozen.completeDispatched)
            assertEquals(f.source,f.db.learning().currentOwner());assertTrue(f.db.learning().pendingOwnerTransitions().isEmpty());f.assertSourcePreserved()
            assertEquals("MERGE_EXPIRED",(f.account().recoverMerge(true){true} as LearningReply.Failed).code)
            assertEquals(frozen.completeJson,f.saved().completeJson);assertEquals(0,f.signIns)
            assertEquals("transition",f.db.learning().currentOwner()?.status)
        }
    }
}
