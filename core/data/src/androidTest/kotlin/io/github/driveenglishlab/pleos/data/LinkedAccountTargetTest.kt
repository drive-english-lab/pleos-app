package io.github.driveenglishlab.pleos.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import io.github.jan.supabase.auth.user.UserSession
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic provider/server ports, actual protected files/Room/adoption. No production credentials. */
@RunWith(AndroidJUnit4::class)
class LinkedAccountTargetTest {
    private val base get()=InstrumentationRegistry.getInstrumentation().targetContext
    private class Fixture(base:Context):AutoCloseable {
        val directory=File(base.cacheDir,"account-fixture-${UUID.randomUUID()}").apply{mkdirs()}
        val packageId="${base.packageName}.accountfixture.${UUID.randomUUID()}"
        val context=object:ContextWrapper(base){
            override fun getNoBackupFilesDir()=directory
            override fun getPackageName()=packageId
        }
        val db=Room.inMemoryDatabaseBuilder(base,LearningDatabase::class.java).build()
        lateinit var source:LocalOwner
        lateinit var originalBytes:ByteArray
        lateinit var outbox:OutboxRecord
        var sourceAvailable=false;var sourceKind="linked";var sourceCreates=0;var sourceHttp=0
        var providerCalls=0;var providerFailure=false;var readFailure=false;var targetEpoch=9L
        var afterProvider:suspend()->Unit={};var beforeAdopt:suspend()->Unit={}
        var permitted=true
        val targetProfile=UUID.randomUUID();val targetSubject=UUID.randomUUID();val targetSession=UUID.randomUUID()
        val sourceSession=LearningSession(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"synthetic-source")
        val bootOperations=mutableListOf<UUID>()
        val sourceAuth=object:ProfileAuth {
            override suspend fun ensureSession(allowCreate:Boolean,beforeCreate:suspend()->Unit):LearningSession {
                if(!sourceAvailable){if(allowCreate){sourceCreates++;beforeCreate()};error("Synthetic source unavailable")}
                return requireNotNull(currentSession())
            }
            override suspend fun currentSession()=if(sourceAvailable)sourceSession else null
            override suspend fun bootstrapOperation(profileId:UUID)=UUID.randomUUID()
        }
        private fun profile(id:String,epoch:Long,kind:String)=buildJsonObject {
            put("schema_version","5");putJsonObject("profile_context"){put("profile_id",id);put("privacy_epoch",epoch)}
            putJsonObject("settings"){
                put("topics",JsonArray(emptyList()));put("listening_mode","original_full");put("followup_policy",JsonNull)
                put("explicit_fields",JsonArray(emptyList()));put("onboarding_complete",false);put("version",1)
            }
            put("active_resume",JsonNull);put("version",1);put("resume_version",1);put("profile_kind",kind)
            putJsonObject("history_coverage"){put("origin","known_new_profile");put("version",1);put("history_gap",false)}
        }
        val profiles=ProfileRepository(db,sourceAuth){sessions->LearningApi(sessions,LearningContractJson(base),object:LearningHttpTransport{
            override suspend fun exchange(method:String,path:String,query:Map<String,String>,headers:Map<String,String>,body:ByteArray?):LearningHttpResponse {
                sourceHttp++;check(method.equals("get",true) && path=="/v1/profile" && body==null)
                return LearningHttpResponse(200,mapOf("Content-Type" to "application/json"),profile(source.profileId,source.privacyEpoch,sourceKind).toString().toByteArray())
            }
        })}
        val accounts=AccountRepository(context,db,profiles,"https://account.fixture")
        fun coordinator()=LinkedAccountTargetCoordinator(context,db,LinkedTargetProfileReader{session,operation->
            bootOperations+=operation
            if(readFailure)LearningReply.Failed("SYNTHETIC_PROFILE_UNAVAILABLE",true)
            else {
                val value=learningJson.decodeFromString(WirePersonalProfileView.serializer(),profile(targetProfile.toString(),targetEpoch,"linked").toString())
                val call=LearningCallScope(OwnerContext(targetProfile,targetEpoch,1,UUID.randomUUID()),session)
                LearningReply.Stored(call,value)
            }
        },LinkedTargetAdopter{auth,profile,revision,operation,mayAdopt->
            beforeAdopt()
            profiles.adoptIdentity(auth,profile,revision,transitionOperation=operation,mayAdopt=mayAdopt)
        },authFactory={store->object:LinkedTargetAuth{
            override suspend fun signInGoogle(idToken:String,nonce:String?){
                providerCalls++;check(idToken=="synthetic-provider-token" && nonce=="synthetic-nonce")
                if(providerFailure)throw java.io.IOException("Synthetic provider failure")
                store.saveSession(UserSession("synthetic-target-access","synthetic-target-refresh",expiresIn=3600,tokenType="bearer"))
                afterProvider()
            }
            override suspend fun ensureSession(allowCreate:Boolean,beforeCreate:suspend()->Unit):LearningSession {
                check(!allowCreate && store.readExistingIdentity()?.session!=null)
                return LearningSession(targetSubject,targetSession,UUID.fromString(store.identity().installationId),"synthetic-target")
            }
            override suspend fun currentSession()=ensureSession(false){error("No target signup")}
            override suspend fun bootstrapOperation(profileId:UUID)=UUID.randomUUID()
            override suspend fun close(){}
        }})
        suspend fun initialize(corrupt:Boolean=false) {
            source=db.learning().provisionalOwner()
            AuthSessionStore(context).beginCreation()
            val file=File(directory,"learning-auth-v1.enc")
            if(corrupt)file.writeBytes(file.readBytes().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()})
            originalBytes=file.readBytes()
            db.learning().insertJournal(OwnerJournal(UUID.randomUUID().toString(),source.profileId,source.revision,"auth_creation","started",null))
            outbox=OutboxRecord(UUID.randomUUID().toString(),source.profileId,source.privacyEpoch,source.revision,UUID.randomUUID().toString(),"immutable-source-payload","immutable-source-hash")
            db.learning().insertOutbox(outbox)
        }
        fun assertSourceFilePreserved(){assertArrayEquals(originalBytes,File(directory,"learning-auth-v1.enc").readBytes())}
        override fun close(){
            db.close();directory.deleteRecursively()
            val keys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
            keys.aliases().toList().filter{it.startsWith("$packageId.")}.forEach(keys::deleteEntry)
        }
    }

    @Test fun independentRestoreKeepsCreationFenceAndUnprovenOutboxWhileAdoptingOnlyLatestLinkedEpoch()=runBlocking {
        Fixture(base).use{f->
            f.initialize(corrupt=true)
            assertTrue(f.accounts.retryProtectedSession() is LearningReply.Failed)
            assertEquals(0,f.sourceCreates);assertEquals(0,f.sourceHttp);f.assertSourceFilePreserved()
            val prepared=f.accounts.prepareLinkedTarget(AccountTargetPurpose.RESTORE_LINKED) as AccountTargetPreparation.Ready
            assertEquals(f.source,f.db.learning().currentOwner());assertEquals(f.outbox,f.db.learning().outboxEvent(f.outbox.eventId))
            val coordinator=f.coordinator()
            val reply=coordinator.signIn(prepared.attempt.operationId,"synthetic-provider-token","synthetic-nonce"){true}
            assertTrue(reply is LearningReply.Stored)
            val current=requireNotNull(f.db.learning().currentOwner())
            assertEquals(f.targetProfile.toString(),current.profileId);assertEquals(9L,current.privacyEpoch);assertEquals("active",current.status)
            assertEquals(f.outbox.copy(isolated=true),f.db.learning().outboxEvent(f.outbox.eventId))
            assertNotNull(f.db.learning().authCreation(f.source.profileId));f.assertSourceFilePreserved()
            assertEquals("learning-account-target-${prepared.attempt.operationId}",AuthSessionStore.activeBinding(f.context).reference)
            assertNotNull(AuthSessionStore(f.context).identity().session)
            assertEquals(1,f.providerCalls);assertEquals(0,f.sourceCreates);assertEquals(0,f.sourceHttp)
        }
    }

    @Test fun cancellationAndLateProviderResultPreserveSourceAndRequireExplicitConfirmationBeforeAnyFence()=runBlocking {
        Fixture(base).use{f->
            f.initialize();val coordinator=f.coordinator()
            val first=(coordinator.prepare(AccountTargetPurpose.RESTORE_LINKED) as AccountTargetPreparation.Ready).attempt
            f.providerFailure=true
            assertTrue(coordinator.signIn(first.operationId,"synthetic-provider-token","synthetic-nonce"){true} is LearningReply.Failed)
            assertTrue(coordinator.cancel(first.operationId));assertNull(coordinator.pending())
            assertEquals(f.source,f.db.learning().currentOwner());assertEquals(f.outbox,f.db.learning().outboxEvent(f.outbox.eventId));f.assertSourceFilePreserved()
            val second=(coordinator.prepare(AccountTargetPurpose.RESTORE_LINKED) as AccountTargetPreparation.Ready).attempt
            f.providerFailure=false;f.afterProvider={f.permitted=false}
            assertEquals("ACCOUNT_ACTION_CONTEXT_CHANGED",(coordinator.signIn(second.operationId,"synthetic-provider-token","synthetic-nonce"){f.permitted} as LearningReply.Failed).code)
            assertFalse(coordinator.cancel(second.operationId)) // Root's finally must preserve actual authenticated recovery.
            assertEquals(f.source,f.db.learning().currentOwner());assertEquals(f.outbox,f.db.learning().outboxEvent(f.outbox.eventId))
            assertEquals("learning-auth-v1",AuthSessionStore.activeBinding(f.context).reference);f.assertSourceFilePreserved()
            val restored=f.coordinator()
            f.readFailure=true
            assertEquals("SYNTHETIC_PROFILE_UNAVAILABLE",(restored.recover{false} as LearningReply.Failed).code)
            assertEquals(f.source,f.db.learning().currentOwner());f.assertSourceFilePreserved()
            f.readFailure=false
            assertEquals("ACCOUNT_TARGET_CONFIRMATION_REQUIRED",(restored.recover{false} as LearningReply.Failed).code)
            assertEquals(f.source,f.db.learning().currentOwner())
            f.permitted=true;assertTrue(restored.recover{f.permitted} is LearningReply.Stored)
            assertEquals(f.targetProfile.toString(),f.db.learning().currentOwner()?.profileId)
            assertEquals(2,f.providerCalls);f.assertSourceFilePreserved()
        }
    }

    @Test fun crashAfterDurableFenceAndSelectorRecoversSameAttemptWithoutMergeOrAnotherCredential()=runBlocking {
        Fixture(base).use{f->
            f.initialize(corrupt=true);val first=f.coordinator()
            val attempt=(first.prepare(AccountTargetPurpose.RESTORE_LINKED) as AccountTargetPreparation.Ready).attempt
            f.beforeAdopt={throw java.io.IOException("Synthetic crash after selector")}
            assertTrue(first.signIn(attempt.operationId,"synthetic-provider-token","synthetic-nonce"){true} is LearningReply.Failed)
            assertEquals("transition",f.db.learning().currentOwner()?.status)
            assertEquals(f.source.revision+1,f.db.learning().currentOwner()?.revision)
            assertFalse(first.cancel(attempt.operationId));f.assertSourceFilePreserved()
            f.beforeAdopt={};f.targetEpoch=11
            val restored=f.coordinator();assertTrue(restored.recover{false} is LearningReply.Stored)
            assertEquals(11L,f.db.learning().currentOwner()?.privacyEpoch);assertEquals(1,f.providerCalls)
            assertEquals(1,f.bootOperations.distinct().size);assertNull(restored.pending())
            assertEquals(f.outbox.copy(isolated=true),f.db.learning().outboxEvent(f.outbox.eventId));f.assertSourceFilePreserved()
            val adopted=f.db.learning().currentOwner()
            assertEquals("ACCOUNT_ATTEMPT_SUPERSEDED",(first.signIn(attempt.operationId,"synthetic-provider-token","synthetic-nonce"){true} as LearningReply.Failed).code)
            assertEquals(adopted,f.db.learning().currentOwner())
        }
    }

    @Test fun linkedSwitchUsesIndependentTargetAndNeverMergesOrRetiresSourceRecords()=runBlocking {
        Fixture(base).use{f->
            f.initialize();f.source=f.source.copy(status="active");f.db.learning().putOwner(f.source);f.sourceAvailable=true
            val record=LearningRecord(f.source.profileId,0,UUID.randomUUID().toString(),"source-private-record","unchanged-source",1)
            f.db.learning().insertRecord(record)
            val prepared=f.accounts.prepareLinkedTarget(AccountTargetPurpose.SWITCH_LINKED) as AccountTargetPreparation.Ready
            assertEquals(1,f.sourceHttp)
            val coordinator=f.coordinator()
            assertTrue(coordinator.signIn(prepared.attempt.operationId,"synthetic-provider-token","synthetic-nonce"){true} is LearningReply.Stored)
            assertEquals(record,f.db.learning().record(record.profileId,record.privacyEpoch,record.recordId))
            assertEquals(f.outbox.copy(isolated=true),f.db.learning().outboxEvent(f.outbox.eventId));f.assertSourceFilePreserved()
            assertEquals(1,f.sourceHttp) // Only source GET; merge/logout/server mutation was never called.
        }
        Fixture(base).use{f->
            f.initialize();f.sourceAvailable=true;f.sourceKind="anonymous"
            assertEquals("SOURCE_MERGE_REQUIRED",(f.accounts.prepareLinkedTarget(AccountTargetPurpose.SWITCH_LINKED) as AccountTargetPreparation.Failed).code)
            assertNull(f.accounts.pendingLinkedTarget());assertEquals(f.source.copy(status="active"),f.db.learning().currentOwner())
        }
    }

    @Test fun existingMergeAuthHttpFailureAndLateCredentialCannotFenceOrIsolateTheValidSource()=runBlocking {
        Fixture(base).use{f->
            f.initialize();f.source=f.source.copy(status="active");f.db.learning().putOwner(f.source);f.sourceAvailable=true
            val intent=WirePersonalMergeIntent(UUID.randomUUID().toString(),f.source.profileId,f.source.privacyEpoch,"synthetic-merge-proof","2099-01-01T00:00:00Z","5")
            val pending=ProtectedMerge(intent,UUID.randomUUID().toString(),f.source.profileId,f.source.privacyEpoch,f.source.revision,f.sourceSession.subjectId.toString())
            AuthSessionStore(f.context).saveAccountState(learningJson.encodeToString(ProtectedMerge.serializer(),pending))
            f.originalBytes=File(f.directory,"learning-auth-v1.enc").readBytes()
            var provider:suspend(AuthSessionStore)->Unit={throw java.io.IOException("Synthetic Google Auth HTTP failure")}
            var targetRefresh:suspend(AuthSessionStore)->LearningSession={throw AssertionError("No target profile/merge request authorized")}
            f.accounts.mergeTargetAuthFactory={store->object:LinkedTargetAuth{
                override suspend fun signInGoogle(idToken:String,nonce:String?){provider(store)}
                override suspend fun ensureSession(allowCreate:Boolean,beforeCreate:suspend()->Unit):LearningSession {check(!allowCreate);return targetRefresh(store)}
                override suspend fun currentSession():LearningSession?=null
                override suspend fun bootstrapOperation(profileId:UUID)=throw AssertionError("No target bootstrap authorized")
                override suspend fun close(){}
            }}
            try {
                f.accounts.signInExistingGoogle("synthetic-provider-token","synthetic-nonce"){true}
                fail("Provider failure must propagate without fencing the source")
            } catch(_:java.io.IOException){}
            assertEquals(f.source,f.db.learning().currentOwner())
            assertTrue(f.db.learning().pendingOwnerTransitions().isEmpty())
            assertEquals(f.outbox,f.db.learning().outboxEvent(f.outbox.eventId));f.assertSourceFilePreserved()
            provider={store->
                store.saveSession(UserSession("synthetic-target-access","synthetic-target-refresh",expiresIn=3600,tokenType="bearer"))
                f.permitted=false
            }
            assertEquals("ACCOUNT_ACTION_CONTEXT_CHANGED",(f.accounts.signInExistingGoogle("synthetic-provider-token","synthetic-nonce"){f.permitted} as LearningReply.Failed).code)
            assertEquals(f.source,f.db.learning().currentOwner())
            assertTrue(f.db.learning().pendingOwnerTransitions().isEmpty())
            assertEquals(f.outbox,f.db.learning().outboxEvent(f.outbox.eventId));f.assertSourceFilePreserved()
            f.permitted=true
            val changed=f.source.copy(privacyEpoch=f.source.privacyEpoch+1,revision=f.source.revision+1)
            provider={throw AssertionError("Protected target must not require another Google credential")}
            targetRefresh={store->
                f.db.learning().putOwner(changed)
                LearningSession(f.targetSubject,f.targetSession,UUID.fromString(store.identity().installationId),"synthetic-target")
            }
            assertEquals("SESSION_CHANGED",(f.accounts.signInExistingGoogle("synthetic-provider-token","synthetic-nonce"){true} as LearningReply.Failed).code)
            assertEquals(changed,f.db.learning().currentOwner())
            assertTrue(f.db.learning().pendingOwnerTransitions().isEmpty())
            assertEquals(f.outbox,f.db.learning().outboxEvent(f.outbox.eventId));f.assertSourceFilePreserved()
            assertEquals(0,f.sourceHttp)
        }
    }
}
