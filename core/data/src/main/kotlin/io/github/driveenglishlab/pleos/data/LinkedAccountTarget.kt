package io.github.driveenglishlab.pleos.data

import android.content.Context
import androidx.room.withTransaction
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** A restore never claims ownership of the old guest or merges its records. */
@Serializable enum class AccountTargetPurpose { RESTORE_LINKED, SWITCH_LINKED }
enum class AccountTargetPhase { AUTH_REQUIRED, AUTHENTICATING, SERVER_CONFIRMATION, CONFIRMATION_REQUIRED, ADOPTING }
sealed interface AccountTargetPreparation {
    data class Ready(val attempt:AccountTargetAttempt):AccountTargetPreparation
    data class Failed(val code:String,val retryable:Boolean):AccountTargetPreparation
}
data class AccountTargetAttempt(val operationId:UUID,val purpose:AccountTargetPurpose,val phase:AccountTargetPhase) {
    override fun toString()="AccountTargetAttempt(purpose=$purpose,phase=$phase)"
}
@Serializable internal data class ProtectedAuthBinding(val reference:String,val revision:Long,val operationId:String?) {
    override fun toString()="ProtectedAuthBinding(reference=redacted)"
}
@Serializable internal data class ProtectedLinkedTarget(val operationId:String,val purpose:AccountTargetPurpose,
    val sourceProfile:String,val sourceEpoch:Long,val sourceRevision:Long,val sourceStatus:String,
    val sourceBinding:ProtectedAuthBinding,val bootstrapOperation:String,val confirmedProfile:WirePersonalProfileView?=null,
    val verifiedSubject:String?=null) {
    override fun toString()="ProtectedLinkedTarget(binding=redacted)"
}
internal interface LinkedTargetAuth:ProfileAuth {
    val adoptionAuth:ProfileAuth get()=this
    suspend fun signInGoogle(idToken:String,nonce:String?)
    suspend fun close()
}
internal class ActualLinkedTargetAuth(store:AuthSessionStore):LinkedTargetAuth {
    private val delegate=SupabaseProfileAuth(store)
    override val adoptionAuth:ProfileAuth get()=delegate
    override suspend fun ensureSession(allowCreate:Boolean,beforeCreate:suspend()->Unit)=delegate.ensureSession(false){error("Target signup forbidden")}
    override suspend fun currentSession()=delegate.currentSession()
    override suspend fun bootstrapOperation(profileId:UUID)=delegate.bootstrapOperation(profileId)
    override suspend fun signInGoogle(idToken:String,nonce:String?)=delegate.signInGoogle(idToken,nonce)
    override suspend fun close(){delegate.close()}
}
internal fun interface LinkedTargetProfileReader {
    suspend fun confirm(session:LearningSession,bootstrapOperation:UUID):LearningReply<WirePersonalProfileView>
}
internal fun interface LinkedTargetAdopter {
    suspend fun adopt(auth:ProfileAuth,profile:WirePersonalProfileView,expectedRevision:Long,operation:String,mayAdopt:()->Boolean):LearningReply<WirePersonalProfileView>
}

/** Uses existing Room owner journal; protected target tokens stay in their own SDK store. */
internal class LinkedAccountTargetCoordinator(private val context:Context,private val database:LearningDatabase,
    private val profileReader:LinkedTargetProfileReader,private val adopter:LinkedTargetAdopter,
    private val authFactory:(AuthSessionStore)->LinkedTargetAuth={ActualLinkedTargetAuth(it)}) {
    private val lane=Mutex()
    private val dao get()=database.learning()
    private fun reference(operation:String)="learning-account-target-$operation"
    private suspend fun journal(operation:String?=null):OwnerJournal? {
        val sql="SELECT operationId,profileId,ownerRevision,kind,status,isolatedSessionReference FROM owner_journal WHERE kind='account_target' AND status NOT IN ('complete','cancelled')"+
            if(operation==null)" AND profileId=(SELECT profileId FROM local_owner WHERE slot=1) AND ((status='adopting' AND ownerRevision+1=(SELECT revision FROM local_owner WHERE slot=1)) OR (status!='adopting' AND ownerRevision=(SELECT revision FROM local_owner WHERE slot=1))) ORDER BY rowid DESC LIMIT 1"
            else " AND operationId=? LIMIT 1"
        return database.openHelper.writableDatabase.query(sql,if(operation==null)emptyArray<Any>() else arrayOf<Any>(operation)).use{row->
            if(!row.moveToFirst())null else OwnerJournal(row.getString(0),row.getString(1),row.getLong(2),row.getString(3),row.getString(4),row.getString(5))
        }
    }
    private suspend fun state(row:OwnerJournal):Pair<AuthSessionStore,ProtectedLinkedTarget> {
        require(row.isolatedSessionReference==reference(row.operationId))
        val store=AuthSessionStore(context,reference(row.operationId))
        val saved=requireNotNull(store.readExistingIdentity())
        val value=learningJson.decodeFromString(ProtectedLinkedTarget.serializer(),requireNotNull(saved.accountState))
        require(value.operationId==row.operationId && value.sourceProfile==row.profileId && value.sourceRevision==row.ownerRevision)
        return store to value
    }
    private fun public(row:OwnerJournal,value:ProtectedLinkedTarget)=AccountTargetAttempt(UUID.fromString(row.operationId),value.purpose,when(row.status){
        "authenticating"->AccountTargetPhase.AUTHENTICATING
        "authenticated"->AccountTargetPhase.SERVER_CONFIRMATION
        "target_verified"->AccountTargetPhase.CONFIRMATION_REQUIRED
        "adopting"->AccountTargetPhase.ADOPTING
        else->AccountTargetPhase.AUTH_REQUIRED
    })
    suspend fun pending():AccountTargetAttempt?=lane.withLock {
        val row=journal()?:return@withLock null
        runCatching{public(row,state(row).second)}.getOrNull()
    }
    suspend fun prepare(purpose:AccountTargetPurpose):AccountTargetPreparation = lane.withLock {
        try {
            val source=dao.currentOwner()?:return@withLock AccountTargetPreparation.Failed("SESSION_UNAVAILABLE",true)
            journal()?.let{row->
                val (_,value)=state(row)
                if(value.sourceProfile!=source.profileId || value.sourceRevision!=source.revision || value.purpose!=purpose)
                    return@withLock AccountTargetPreparation.Failed("ACCOUNT_TRANSITION_PENDING",false)
                return@withLock AccountTargetPreparation.Ready(public(row,value))
            }
            if(source.status !in setOf("active","provisional") || dao.pendingOwnerTransitions().isNotEmpty())
                return@withLock AccountTargetPreparation.Failed("PROFILE_TRANSITION_PENDING",false)
            val binding=AuthSessionStore.activeBinding(context)
            val sourceIdentity=runCatching{AuthSessionStore(context,binding.reference).readExistingIdentity()}.getOrNull()
            val operation=UUID.randomUUID()
            val value=ProtectedLinkedTarget(operation.toString(),purpose,source.profileId,source.privacyEpoch,source.revision,source.status,binding,UUID.randomUUID().toString())
            val target=AuthSessionStore(context,reference(value.operationId))
            target.initializeIsolated(StoredIdentity(installationId=sourceIdentity?.installationId?:UUID.randomUUID().toString(),
                accountState=learningJson.encodeToString(ProtectedLinkedTarget.serializer(),value)))
            val row=OwnerJournal(value.operationId,source.profileId,source.revision,"account_target","auth_prepared",reference(value.operationId))
            database.withTransaction {
                require(dao.currentOwner()==source && journal()==null && dao.pendingOwnerTransitions().isEmpty())
                dao.insertJournal(row)
            }
            AccountTargetPreparation.Ready(public(row,value))
        }catch(cancelled:CancellationException){throw cancelled}
         catch(_:Exception){AccountTargetPreparation.Failed("ACCOUNT_TARGET_PREPARATION_UNAVAILABLE",true)}
    }
    suspend fun cancel(operation:UUID):Boolean=lane.withLock {
        val row=journal(operation.toString())?:return@withLock false
        if(row.status=="adopting")return@withLock false // No rollback after a durable owner fence.
        val (store,value)=state(row)
        if(store.readExistingIdentity()?.session!=null)return@withLock false
        val current=dao.currentOwner()
        if(current?.profileId!=value.sourceProfile || current.revision!=value.sourceRevision || current.status!=value.sourceStatus)return@withLock false
        dao.updateOwnerJournal(row.operationId,"cancelled")
        true
    }
    suspend fun signIn(operation:UUID,idToken:String,nonce:String?,mayAdopt:()->Boolean):LearningReply<WirePersonalProfileView> = lane.withLock {
        if(idToken.isBlank())return@withLock LearningReply.Failed("ACCOUNT_CREDENTIAL_REQUIRED",false)
        execute(operation,idToken,nonce,mayAdopt,false)
    }
    suspend fun recover(mayAdopt:()->Boolean):LearningReply<WirePersonalProfileView>? = lane.withLock {
        val pending=journal()?:return@withLock null
        execute(UUID.fromString(pending.operationId),null,null,mayAdopt,true)
    }
    private suspend fun execute(operation:UUID,idToken:String?,nonce:String?,mayAdopt:()->Boolean,recovery:Boolean):LearningReply<WirePersonalProfileView> {
        var adopted=false
        var auth:LinkedTargetAuth?=null
        try {
            var row=journal(operation.toString())?:return LearningReply.Failed("ACCOUNT_ATTEMPT_SUPERSEDED",false)
            val (store,initial)=state(row)
            if(!sourceCurrent(row,initial))return LearningReply.Failed("SESSION_CHANGED",false)
            val durableAdoption=row.status=="adopting"
            if(!recovery && !mayAdopt())return LearningReply.Failed("ACCOUNT_ACTION_CONTEXT_CHANGED",false)
            auth=authFactory(store)
            if(store.readExistingIdentity()?.session==null) {
                if(idToken==null)return LearningReply.Failed("ACCOUNT_CREDENTIAL_REQUIRED",false)
                if(!mayAdopt())return LearningReply.Failed("ACCOUNT_ACTION_CONTEXT_CHANGED",false)
                dao.updateOwnerJournal(row.operationId,"authenticating")
                auth.signInGoogle(idToken,nonce)
                dao.updateOwnerJournal(row.operationId,"authenticated")
                if(!mayAdopt())return LearningReply.Failed("ACCOUNT_ACTION_CONTEXT_CHANGED",false)
            }
            val session=auth.ensureSession(false){error("Target signup forbidden")}
            if(initial.verifiedSubject!=null && initial.verifiedSubject!=session.subjectId.toString())return LearningReply.Failed("ACCOUNT_TARGET_CHANGED",false)
            val actual=when(val latest=profileReader.confirm(session,UUID.fromString(initial.bootstrapOperation))) {
                is LearningReply.Failed->return latest
                is LearningReply.Stored->latest.value
            }
            require(actual.profileKind=="linked" && actual.profileContext.privacyEpoch>=0)
            initial.confirmedProfile?.let{previous->require(previous.profileContext.profileId==actual.profileContext.profileId && previous.profileContext.privacyEpoch<=actual.profileContext.privacyEpoch)}
            val confirmed=initial.copy(confirmedProfile=actual,verifiedSubject=session.subjectId.toString())
            store.saveAccountState(learningJson.encodeToString(ProtectedLinkedTarget.serializer(),confirmed))
            row=requireNotNull(journal(operation.toString()))
            require(sourceCurrent(row,confirmed))
            if(!durableAdoption) {
                dao.updateOwnerJournal(row.operationId,"target_verified")
                if(!mayAdopt())return LearningReply.Failed(if(recovery)"ACCOUNT_TARGET_CONFIRMATION_REQUIRED" else "ACCOUNT_ACTION_CONTEXT_CHANGED",false)
                database.withTransaction {
                    val source=requireNotNull(dao.currentOwner())
                    require(source.profileId==confirmed.sourceProfile && source.privacyEpoch==confirmed.sourceEpoch && source.revision==confirmed.sourceRevision && source.status==confirmed.sourceStatus)
                    require(journal(row.operationId)?.status=="target_verified" && AuthSessionStore.activeBinding(context)==confirmed.sourceBinding)
                    if(!mayAdopt())throw AccountActionContextChanged()
                    dao.isolate(source.profileId)
                    FeatureLifecycle(database).isolateForOwnerTransition(UUID.fromString(source.profileId))
                    dao.putOwner(source.copy(revision=source.revision+1,status="transition"))
                    dao.updateOwnerJournal(row.operationId,"adopting")
                }
            }
            // Recovery of an already durable fence is storage reconciliation, never playback/UI.
            val permitted:()->Boolean=if(recovery && durableAdoption)({true}) else mayAdopt
            return withContext(NonCancellable) {
                if(!permitted())return@withContext LearningReply.Failed("ACCOUNT_ACTION_CONTEXT_CHANGED",false)
                require(sourceCurrent(requireNotNull(journal(operation.toString())),confirmed))
                AuthSessionStore.selectVerifiedTarget(context,confirmed.sourceBinding,reference(confirmed.operationId),operation)
                if(!permitted())return@withContext LearningReply.Failed("ACCOUNT_ACTION_CONTEXT_CHANGED",false)
                val reply=adopter.adopt(requireNotNull(auth).adoptionAuth,actual,confirmed.sourceRevision+1,confirmed.operationId,permitted)
                if(reply is LearningReply.Stored)adopted=true
                reply
            }
        }catch(cancelled:CancellationException){throw cancelled}
         catch(_:AccountActionContextChanged){return LearningReply.Failed("ACCOUNT_ACTION_CONTEXT_CHANGED",false)}
         catch(_:Exception){return LearningReply.Failed("ACCOUNT_TARGET_RECOVERY_REQUIRED",true)}
        finally{if(!adopted)try{auth?.close()}catch(_:Exception){/* Retain protected target journal. */}}
    }
    private suspend fun sourceCurrent(row:OwnerJournal,value:ProtectedLinkedTarget):Boolean {
        val owner=dao.currentOwner()?:return false
        return owner.profileId==value.sourceProfile && owner.privacyEpoch==value.sourceEpoch &&
            (if(row.status=="adopting")owner.status=="transition" && owner.revision==value.sourceRevision+1
            else owner.status==value.sourceStatus && owner.revision==value.sourceRevision)
    }
    private class AccountActionContextChanged:Exception()
}
