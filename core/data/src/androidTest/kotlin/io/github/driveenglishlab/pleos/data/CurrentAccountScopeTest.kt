package io.github.driveenglishlab.pleos.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.withTransaction
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

/** R6 isolated composition fixtures. Real Room/encryption/strict HTTP; no actual SDK/provider proof. */
@RunWith(AndroidJUnit4::class)
class CurrentAccountScopeTest {
    private val base get()=InstrumentationRegistry.getInstrumentation().targetContext
    private class Fixture(base:Context):AutoCloseable {
        val directory=File(base.cacheDir,"r6-scope-${UUID.randomUUID()}").apply{mkdirs()}
        val packageId="${base.packageName}.r6scope.${UUID.randomUUID()}"
        val context=object:ContextWrapper(base){override fun getNoBackupFilesDir()=directory;override fun getPackageName()=packageId}
        val db=Room.inMemoryDatabaseBuilder(base,LearningDatabase::class.java).build()
        lateinit var owner:LocalOwner
        val subject=UUID.randomUUID();val installation=UUID.randomUUID()
        val session=LearningSession(subject,UUID.randomUUID(),installation,"synthetic-current")
        var currentSession:LearningSession?=session
        var kind="anonymous";var epoch=0L;var calls=0;var features=0;var targetAuth=0;var creates=0;var deletionOffline=false
        val auth=object:ProfileAuth {
            override suspend fun ensureSession(allowCreate:Boolean,beforeCreate:suspend()->Unit):LearningSession {
                if(allowCreate){creates++;throw AssertionError("Reconciliation cannot create a guest")}
                return requireNotNull(currentSession)
            }
            override suspend fun currentSession()=currentSession
            override suspend fun bootstrapOperation(profileId:UUID)=UUID.randomUUID()
        }
        fun profile(version:String="5")=buildJsonObject {
            put("schema_version",version);putJsonObject("profile_context"){put("profile_id",owner.profileId);put("privacy_epoch",epoch)}
            putJsonObject("settings"){
                put("topics",JsonArray(emptyList()));put("listening_mode","original_full");put("followup_policy",if(version=="6")JsonPrimitive("none") else JsonNull)
                put("explicit_fields",JsonArray(emptyList()));put("onboarding_complete",false);put("version",1)
            }
            put("active_resume",JsonNull);put("version",1);put("resume_version",epoch+1);put("profile_kind",kind)
            putJsonObject("history_coverage"){put("origin","known_new_profile");put("version",1);put("history_gap",false)}
        }
        private fun response(value:JsonElement,status:Int=200)=LearningHttpResponse(status,mapOf("Content-Type" to "application/json"),value.toString().toByteArray())
        private fun error(code:String,operation:String?=null,status:Int=403)=response(buildJsonObject {
            put("schema_version","5");put("operation_id",operation?.let(::JsonPrimitive)?:JsonNull)
            putJsonObject("error"){put("code",code);put("retryable",status>=500);put("message_ko","합성 현재 소유 검증")}
        },status)
        val transport=object:LearningHttpTransport {
            override val configuredOrigin="https://r6-current-scope.fixture"
            override suspend fun exchange(method:String,path:String,query:Map<String,String>,headers:Map<String,String>,body:ByteArray?):LearningHttpResponse {
                calls++
                if(headers["Authorization"]!="Bearer synthetic-current")return error("FORBIDDEN")
                if(path=="/v1/profile" && method=="get")return response(profile())
                if(path in setOf("/v1/profile/sync","/v2/profile/sync") && method=="get") {
                    val version=if(path.startsWith("/v2"))"6" else "5"
                    return response(buildJsonObject{
                        put("profile_context",profile(version).getValue("profile_context"));put("reset_required",false);put("records",JsonArray(emptyList()))
                        put("cursor","synthetic-r6-cursor");put("profile",profile(version));put("schema_version",version)
                    })
                }
                if(path=="/v1/profile/history-deletions") {
                    val request=learningJson.decodeFromString(WirePersonalDeletionRequest.serializer(),requireNotNull(body).toString(Charsets.UTF_8))
                    if(deletionOffline)return error("UPSTREAM_UNAVAILABLE",request.operationId,503)
                    epoch=request.profileContext.privacyEpoch+1
                    val receipt=WirePersonalDeletionReceipt(request.operationId,WireCommonProfileContext(owner.profileId,epoch),epoch-1,"2026-11-15T00:00:00Z","all_profile_learning_history",true,"5")
                    return response(learningJson.encodeToJsonElement(WirePersonalDeletionReceipt.serializer(),receipt))
                }
                throw AssertionError("No account, credential, target adoption or new guest route is permitted")
            }
        }
        fun api(sessions:LearningSessionSource)=LearningApi(sessions,LearningContractJson(context),transport)
        val profiles by lazy {ProfileRepository(db,auth){sessions->api(sessions)}}
        val api by lazy {api(profiles)}
        val accounts by lazy {AccountRepository(context,db,profiles,"https://r6-current-scope.fixture").also{
            it.mergeTargetAuthFactory={targetAuth++;throw AssertionError("Google account recovery is deferred")}
        }}
        val policy get()=CurrentAccountScope(context,db)
        val worker get()=ProfileSyncWorker(FeatureLifecycle(db))
        suspend fun initialize(status:String="active",validSession:Boolean=true,revision:Long=1) {
            owner=db.learning().provisionalOwner().copy(status=status,revision=revision);db.learning().putOwner(owner)
            val saved=if(validSession)UserSession("synthetic-protected-access","synthetic-protected-refresh",expiresIn=3600,tokenType="bearer",user=UserInfo(aud="authenticated",id=subject.toString())) else null
            AuthSessionStore(context).initializeIsolated(StoredIdentity(installationId=installation.toString(),creationStarted=true,session=saved,bootstrapProfileId=owner.profileId,bootstrapOperationId=UUID.randomUUID().toString()))
            if(!validSession)currentSession=null
        }
        suspend fun sync()=worker.reconcile(context,db,profiles,api,accounts){scope->features++;LearningReply.Stored(scope,Unit)}
        suspend fun incremental():LearningReply<Source6ProfileSnapshot> {
            val codecs=IncrementalContractJson(context);val incremental=api.incremental(context)
            val review=ReviewRepository(incremental,db.review(),ReviewJournal(db.review(),codecs),codecs)
            val practice=Source6PracticeRepository(incremental,db.review(),codecs,review,{_,_,_->true})
            val reconciler=IncrementalSyncReconciler(incremental,review,practice,Source6ListeningRepository(incremental,db.review(),codecs))
            return worker.reconcileIncremental(context,db,profiles,api,accounts,reconciler,
                flushActualOutputs={LearningReply.Stored(it,Unit)},flushFeatures={features++;LearningReply.Stored(it,Unit)})
        }
        suspend fun row(kind:String,status:String="pending",profile:String=owner.profileId,revision:Long=owner.revision,reference:String?=null):OwnerJournal =
            OwnerJournal(UUID.randomUUID().toString(),profile,revision,kind,status,reference).also{db.learning().insertJournal(it)}
        suspend fun immutableOutbox(profile:String=owner.profileId,isolated:Boolean=false)=OutboxRecord(UUID.randomUUID().toString(),profile,0,1,UUID.randomUUID().toString(),"immutable-preserved-payload","immutable-preserved-hash",isolated).also{db.learning().insertOutbox(it)}
        fun bytes()=File(directory,"learning-auth-v1.enc").readBytes()
        override fun close(){db.close();directory.deleteRecursively();val keys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};keys.aliases().toList().filter{it.startsWith("$packageId.")}.forEach(keys::deleteEntry)}
    }

    @Test fun untouchedInstallationAllowsOnlyExistingAnonymousBootstrapAndPublicCacheIsNotPersonalEvidence()=runBlocking {
        Fixture(base).use{f->
            assertFalse(CurrentAccountScope.accountActionsEnabled);assertNull(f.policy.recoveryHold())
            val id=UUID.randomUUID().toString()
            f.db.openHelper.writableDatabase.execSQL("INSERT INTO catalog_view_page VALUES (?,?,?,?,?,?,?,?,?,?)",arrayOf<Any>(id,0L,1L,"interests","public",id,"",1L,"{}",LearningJson.hash("{}")))
            assertNull(f.policy.recoveryHold());assertNull(f.db.learning().currentOwner())
            assertFalse(File(f.directory,"learning-auth-v1.enc").exists())
            assertFalse(KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.containsAlias("${f.packageId}.learning-auth-v1"))
        }
    }
    @Test fun orphanedOwnerWithAnyPersonalRecordOutboxFenceOrPendingJournalIsNeverANewInstallation()=runBlocking {
        for(evidence in listOf("record","outbox","auth_creation","merge","logout","account_target"))Fixture(base).use{f->
            val id=UUID.randomUUID().toString()
            when(evidence){
                "record"->f.db.learning().insertRecord(LearningRecord(id,0,id,"private","immutable",1))
                "outbox"->f.immutableOutbox(id)
                else->f.db.learning().insertJournal(OwnerJournal(id,id,1,evidence,"pending",null))
            }
            assertEquals("OWNER_RECHECK_REQUIRED",f.policy.recoveryHold())
            assertNull(f.db.learning().currentOwner());assertFalse(File(f.directory,"learning-auth-v1.enc").exists())
        }
    }
    @Test fun validAnonymousCurrentOwnerKeepsBothSyncPathsAndRejectsOtherOwnerEpochAndRevision()=runBlocking {
        Fixture(base).use{f->
            f.initialize();val before=f.bytes();val scope=requireNotNull(f.profiles.current())
            assertNull(f.policy.recoveryHold());assertTrue(f.policy.permitsProtectedOperation(scope.owner))
            assertFalse(f.policy.permitsProtectedOperation(scope.owner.copy(profileId=UUID.randomUUID())))
            assertFalse(f.policy.permitsProtectedOperation(scope.owner.copy(privacyEpoch=1)))
            assertFalse(f.policy.permitsProtectedOperation(scope.owner.copy(revision=2)))
            assertTrue(f.sync() is LearningReply.Stored);assertTrue(f.incremental() is LearningReply.Stored)
            assertEquals(2,f.features);assertEquals(0,f.targetAuth);assertEquals(0,f.creates)
            assertEquals(f.owner,f.db.learning().currentOwner());assertArrayEquals(before,f.bytes())
        }
    }
    @Test fun validLinkedOwnerAndOldTerminalOrForeignLogoutLineageContinueWithoutRevocationOrRelabeling()=runBlocking {
        Fixture(base).use{f->
            f.initialize(revision=5);f.kind="linked";val before=f.bytes()
            val journals=listOf(f.row("merge","reprepared",revision=4),f.row("account_target","complete"),f.row("account_target","cancelled"),f.row("logout",profile=UUID.randomUUID().toString(),revision=3))
            val oldOutbox=f.immutableOutbox(journals.last().profileId,true)
            assertNull(f.policy.pendingAccountAction());assertNull(f.policy.recoveryHold())
            val result=f.sync();assertTrue(result is LearningReply.Stored)
            assertEquals("linked",(result as LearningReply.Stored).value.profile.profileKind)
            assertArrayEquals(before,f.bytes());assertEquals(oldOutbox,f.db.learning().outboxEvent(oldOutbox.eventId));assertEquals(0,f.targetAuth)
            assertEquals("pending",f.db.openHelper.writableDatabase.query("SELECT status FROM owner_journal WHERE operationId=?",arrayOf<Any>(journals.last().operationId)).use{it.moveToFirst();it.getString(0)})
        }
    }
    @Test fun preFenceMergeIsInformationalAndSameSourceSyncDoesNotCompleteOrDiscardThePreparedAccount()=runBlocking {
        Fixture(base).use{f->
            f.initialize()
            val intent=WirePersonalMergeIntent(UUID.randomUUID().toString(),f.owner.profileId,0,"p".repeat(64),"2099-01-01T00:00:00Z","5")
            val prepared=ProtectedMerge(intent,UUID.randomUUID().toString(),f.owner.profileId,0,1,f.subject.toString())
            AuthSessionStore(f.context).saveAccountState(learningJson.encodeToString(ProtectedMerge.serializer(),prepared));val before=f.bytes()
            assertEquals("ACCOUNT_TRANSITION_PRESERVED",f.policy.pendingAccountAction());assertNull(f.policy.recoveryHold())
            assertTrue(f.sync() is LearningReply.Stored);assertTrue(f.incremental() is LearningReply.Stored)
            assertEquals(0,f.targetAuth);assertArrayEquals(before,f.bytes());assertEquals(f.owner,f.db.learning().currentOwner())
        }
    }
    @Test fun unauthenticatedTargetIsKeptWhileValidSourceWorksAndUnconfirmedSourceCannotBecomeANewGuest()=runBlocking {
        Fixture(base).use{f->
            f.initialize();val attempt=UUID.randomUUID();val reference="learning-account-target-$attempt"
            val row=OwnerJournal(attempt.toString(),f.owner.profileId,1,"account_target","auth_prepared",reference);f.db.learning().insertJournal(row)
            val target=AuthSessionStore(f.context,reference);target.initializeIsolated(StoredIdentity())
            val source=f.bytes();val targetBytes=File(f.directory,"$reference.enc").readBytes()
            assertNotNull(f.policy.pendingAccountAction());assertNull(f.policy.recoveryHold());assertTrue(f.incremental() is LearningReply.Stored)
            assertArrayEquals(source,f.bytes());assertArrayEquals(targetBytes,File(f.directory,"$reference.enc").readBytes())
            assertEquals("auth_prepared",f.db.openHelper.writableDatabase.query("SELECT status FROM owner_journal WHERE operationId=?",arrayOf<Any>(row.operationId)).use{it.moveToFirst();it.getString(0)})
        }
        Fixture(base).use{f->
            f.initialize(status="provisional",validSession=false);f.row("account_target","auth_prepared")
            assertNotNull(f.policy.recoveryHold());assertTrue(f.sync() is LearningReply.Failed)
            assertEquals(0,f.calls);assertEquals(0,f.creates);assertEquals(f.owner,f.db.learning().currentOwner())
        }
    }
    @Test fun fencedMergeTargetAndLogoutArePreservedByBothWorkersWithNoAccountOrNetworkStep()=runBlocking {
        for(kind in listOf("merge","account_target","logout"))Fixture(base).use{f->
            f.initialize(status="transition",revision=2)
            f.row(kind,if(kind=="account_target")"adopting" else "pending",revision=1)
            val outbox=f.immutableOutbox();val bytes=f.bytes()
            assertNotNull(f.policy.recoveryHold());assertTrue(f.sync() is LearningReply.Failed);assertTrue(f.incremental() is LearningReply.Failed)
            assertEquals(0,f.calls);assertEquals(0,f.targetAuth);assertEquals(0,f.creates)
            assertEquals(f.owner,f.db.learning().currentOwner());assertEquals(outbox,f.db.learning().outboxEvent(outbox.eventId));assertArrayEquals(bytes,f.bytes())
        }
    }
    @Test fun logoutProvisionalHoldCannotCreateGuestWhileAlreadyConfirmedNewOwnerIgnoresTheOldJournal()=runBlocking {
        for(status in listOf("provisional","active"))Fixture(base).use{f->
            f.initialize(status=status,revision=3);f.row("logout",profile=UUID.randomUUID().toString(),revision=1)
            val before=f.bytes()
            if(status=="provisional") {assertNotNull(f.policy.recoveryHold());assertTrue(f.sync() is LearningReply.Failed);assertEquals(0,f.calls)}
            else {assertNull(f.policy.pendingAccountAction());assertNull(f.policy.recoveryHold());assertTrue(f.sync() is LearningReply.Stored)}
            assertArrayEquals(before,f.bytes());assertEquals(f.owner,f.db.learning().currentOwner());assertEquals(0,f.creates)
        }
    }
    @Test fun missingAndDamagedProtectedSessionsPreserveCiphertextOutboxAndCreationFenceWithoutNewIdentity()=runBlocking {
        for(damaged in listOf(false,true))Fixture(base).use{f->
            f.initialize(status="provisional",validSession=damaged)
            f.row("auth_creation","started");val outbox=f.immutableOutbox()
            if(damaged){val file=File(f.directory,"learning-auth-v1.enc");file.writeBytes(file.readBytes().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()})}
            val before=f.bytes();assertNotNull(f.policy.recoveryHold());assertTrue(f.sync() is LearningReply.Failed)
            assertEquals(0,f.calls);assertEquals(0,f.creates);assertEquals(f.owner,f.db.learning().currentOwner());assertArrayEquals(before,f.bytes())
            assertEquals(outbox,f.db.learning().outboxEvent(outbox.eventId));assertNotNull(f.db.learning().authCreation(f.owner.profileId))
        }
    }
    @Test fun currentDeletionStaysAvailableAndBackendBindingOrLateOwnerStillPreventsForeignAcceptance()=runBlocking {
        Fixture(base).use{f->
            f.initialize();val before=f.bytes();val original=requireNotNull(f.profiles.current());val operation=UUID.randomUUID()
            val request=WirePersonalDeletionRequest(operation.toString(),original.owner.profileWire(),"5")
            f.db.withTransaction {
                f.db.learning().insertRecord(readRecord(original.owner,operation,"history_deletion_command",learningJson.encodeToString(WirePersonalDeletionRequest.serializer(),request),1))
                FeatureLifecycle(f.db).blockForHistoryDeletion(original.owner,operation)
            }
            val deleting=requireNotNull(f.db.learning().currentOwner());assertEquals("delete_pending",deleting.status)
            assertNull(f.policy.recoveryHold());assertTrue(f.policy.permitsProtectedOperation(OwnerContext(UUID.fromString(deleting.profileId),0,deleting.revision,UUID.randomUUID())))
            // Existing authenticated deletion/epoch adapter against this fixture's strict HTTP backend.
            val maintenance=f.api(LearningSessionSource{f.profiles.maintenanceScope()})
            val call=requireNotNull(f.profiles.maintenanceScope())
            val receipt=maintenance.deleteLearningHistory(call,request)
            assertTrue(receipt is LearningReply.Stored)
            val latest=learningJson.decodeFromString(WirePersonalProfileView.serializer(),f.profile().toString())
            f.profiles.reconcileDeletion(latest,operation.toString(),deleting.revision,(receipt as LearningReply.Stored).value)
            assertTrue(f.sync() is LearningReply.Stored)
            assertEquals(1L,f.db.learning().currentOwner()?.privacyEpoch);assertArrayEquals(before,f.bytes());assertEquals(0,f.creates)
        }
        Fixture(base).use{f->
            f.initialize();val originalIdentity=AuthSessionStore(f.context).identity()
            AuthSessionStore(f.context).initializeIsolated(originalIdentity.copy(bootstrapProfileId=UUID.randomUUID().toString()))
            var before=f.bytes()
            assertEquals("OWNER_RECHECK_REQUIRED",f.policy.recoveryHold());assertTrue(f.sync() is LearningReply.Failed)
            assertEquals(0,f.calls);assertEquals(f.owner,f.db.learning().currentOwner());assertArrayEquals(before,f.bytes())
            AuthSessionStore(f.context).initializeIsolated(originalIdentity)
            val foreign=learningJson.decodeFromString(WirePersonalProfileView.serializer(),JsonObject(f.profile()+("profile_context" to buildJsonObject{put("profile_id",UUID.randomUUID().toString());put("privacy_epoch",0)})).toString())
            val target=ProtectedLinkedTarget(UUID.randomUUID().toString(),AccountTargetPurpose.SWITCH_LINKED,UUID.randomUUID().toString(),0,1,"active",AuthSessionStore.activeBinding(f.context),UUID.randomUUID().toString(),foreign,f.subject.toString())
            AuthSessionStore(f.context).saveAccountState(learningJson.encodeToString(ProtectedLinkedTarget.serializer(),target));before=f.bytes()
            assertEquals("OWNER_RECHECK_REQUIRED",f.policy.recoveryHold());assertTrue(f.sync() is LearningReply.Failed)
            assertEquals(0,f.calls);assertEquals(f.owner,f.db.learning().currentOwner());assertArrayEquals(before,f.bytes())
            AuthSessionStore(f.context).initializeIsolated(originalIdentity);before=f.bytes()
            // Presence is a local preflight; real ProfileAuth/API ownership checks remain authoritative.
            f.currentSession=LearningSession(UUID.randomUUID(),UUID.randomUUID(),f.installation,"synthetic-foreign")
            assertNull(f.policy.recoveryHold());assertEquals("FORBIDDEN",(f.sync() as LearningReply.Failed).code)
            assertEquals(0,f.features);assertEquals(f.owner,f.db.learning().currentOwner());assertArrayEquals(before,f.bytes())
            f.currentSession=f.session
            val other=f.owner.copy(profileId=UUID.randomUUID().toString(),revision=2)
            val result=f.worker.reconcile(f.context,f.db,f.profiles,f.api,f.accounts){scope->f.db.learning().putOwner(other);LearningReply.Stored(scope,Unit)}
            assertTrue(result is LearningReply.Failed);assertEquals(other,f.db.learning().currentOwner());assertArrayEquals(before,f.bytes())
        }
    }
}
