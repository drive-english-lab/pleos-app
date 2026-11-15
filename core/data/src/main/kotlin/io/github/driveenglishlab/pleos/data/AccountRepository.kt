package io.github.driveenglishlab.pleos.data

import android.content.Context
import androidx.room.withTransaction
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.data.wire.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Serializable
internal data class ProtectedMerge(val intent: WirePersonalMergeIntent, val completeOperation: String,
    val sourceProfile: String, val sourceEpoch: Long, val sourceRevision: Long, val sourceSubject: String,
    val receipt: WirePersonalMergeReceipt? = null,
    val sourceReference:String?=null,val sourceInstallation:String?=null,
    val targetSubject:String?=null,val targetInstallation:String?=null,val targetSession:String?=null,
    val completeJson:String?=null,val completeHash:String?=null,val completeDispatched:Boolean=false,
    val recoveryPhase:String="reconciling",val reasonCode:String?=null,val definiteRejectionCode:String?=null,
    val predecessorOperation:String?=null,val replacement:ProtectedMergeReplacement?=null,
    val abandonedPreparations:List<ProtectedMergeReplacement> = emptyList()) {
    override fun toString()="ProtectedMerge(binding=redacted)"
}

/** Login is optional. Provider credentials arrive from an approved identity adapter, never Room/UI.
 * Existing-account sign-in uses an isolated SDK store until server merge and local adoption agree.
 */
class AccountRepository(private val context: Context, private val database: LearningDatabase,
    private val profiles: ProfileRepository, private val origin: String, private val allowLocalHttp: Boolean = false) {
    private val active get() = AuthSessionStore(context)
    internal var mergeTargetAuthFactory:(AuthSessionStore)->LinkedTargetAuth={ActualLinkedTargetAuth(it)}
    internal var mergeSourceAuthFactory:(AuthSessionStore)->LinkedTargetAuth={ActualLinkedTargetAuth(it)}
    internal var mergeApiFactory:(LearningSessionSource)->LearningApi={sessions->LearningApi(context,origin,sessions,allowLocalHttp)}
    private val mergeLane=mergeLanes.getOrPut(context.packageName){Mutex()}
    companion object { private val mergeLanes=ConcurrentHashMap<String,Mutex>() }
    private val mergeRecovery=MergeRecoveryJournal(database)
    private val api = LearningApi(context, origin, LearningSessionSource { profiles.maintenanceScope() }, allowLocalHttp)
    private val journal = SessionJournal(database.learning(), api, LearningContractJson(context))
    val transitions = OwnerTransition(context, database, profiles, origin, allowLocalHttp)
    private val linkedTargets by lazy {
        LinkedAccountTargetCoordinator(context,database,LinkedTargetProfileReader{session,operation->
            val provisional=OwnerContext(UUID.randomUUID(),0,1,UUID.randomUUID())
            var call=LearningCallScope(provisional,session)
            val targetApi=LearningApi(context,origin,LearningSessionSource{call},allowLocalHttp)
            when(val boot=targetApi.bootstrapProfile(call,WirePersonalBootstrapRequest(operation.toString(),null,"5"))) {
                is LearningReply.Failed->boot
                is LearningReply.Stored->{
                    call=call.copy(owner=provisional.copy(profileId=UUID.fromString(boot.value.profileContext.profileId),privacyEpoch=boot.value.profileContext.privacyEpoch))
                    targetApi.readProfile(call)
                }
            }
        },LinkedTargetAdopter{auth,profile,revision,operation,mayAdopt->
            profiles.adoptIdentity(auth,profile,revision,transitionOperation=operation,mayAdopt=mayAdopt)
        })
    }

    /** Refresh only the existing protected identity. This call can never sign up a guest. */
    suspend fun retryProtectedSession():LearningReply<WirePersonalProfileView> = profiles.connect(allowCreation=false)
    suspend fun pendingLinkedTarget():AccountTargetAttempt?=linkedTargets.pending()
    suspend fun prepareLinkedTarget(purpose:AccountTargetPurpose):AccountTargetPreparation {
        // A staged attempt must be resumed, never silently replaced with another credential flow.
        linkedTargets.pending()?.let{existing->
            if(existing.purpose!=purpose)return AccountTargetPreparation.Failed("ACCOUNT_TRANSITION_PENDING",false)
            return linkedTargets.prepare(purpose)
        }
        val current=profiles.current()
        if(purpose==AccountTargetPurpose.SWITCH_LINKED) {
            if(current==null)return AccountTargetPreparation.Failed("PROTECTED_SESSION_RETRY_REQUIRED",true)
            when(val profile=profiles.refresh()) {
                is LearningReply.Failed->return AccountTargetPreparation.Failed(profile.code,profile.retryable)
                is LearningReply.Stored->if(profile.value.profileKind!="linked")return AccountTargetPreparation.Failed("SOURCE_MERGE_REQUIRED",false)
            }
        } else {
            if(current!=null)return AccountTargetPreparation.Failed("EXPLICIT_LINK_OR_SWITCH_REQUIRED",false)
            val evidence=active.diagnosticState()
            if(evidence.sessionPresent==true)return AccountTargetPreparation.Failed("PROTECTED_SESSION_RETRY_REQUIRED",true)
        }
        return linkedTargets.prepare(purpose)
    }
    suspend fun signInLinkedTargetGoogle(attemptId:UUID,idToken:String,nonce:String?,mayAdopt:()->Boolean={true}):LearningReply<WirePersonalProfileView> =
        linkedTargets.signIn(attemptId,idToken,nonce,mayAdopt)
    /** Default recovery only completes an already-authorized durable fence; no foreground grant. */
    suspend fun recoverLinkedTarget(mayAdopt:()->Boolean={false}):LearningReply<WirePersonalProfileView>?=linkedTargets.recover(mayAdopt)
    suspend fun cancelLinkedTarget(attemptId:UUID):Boolean=linkedTargets.cancel(attemptId)


    suspend fun requestHistoryDeletion(): LearningReply<Unit> {
        val call = profiles.current() ?: return LearningReply.Failed("SESSION_UNAVAILABLE", true)
        val operation = UUID.randomUUID()
        val request = WirePersonalDeletionRequest(operation.toString(), call.owner.profileWire(), "5")
        database.withTransaction {
            database.learning().insertRecord(readRecord(call.owner, operation, "history_deletion_command",
                learningJson.encodeToString(WirePersonalDeletionRequest.serializer(), request), 1))
            FeatureLifecycle(database).blockForHistoryDeletion(call.owner,operation)
        }
        return retryDeletion()
    }

    suspend fun retryDeletion(): LearningReply<Unit> {
        val command = database.learning().pendingOwnerTransitions().firstOrNull { it.kind == "history_deletion" }
            ?: return LearningReply.Failed("NO_PENDING_DELETION", false)
        val call = profiles.maintenanceScope()?.takeIf { it.owner.profileId.toString() == command.profileId }
            ?: return LearningReply.Failed("SESSION_UNAVAILABLE", true)
        val row = database.learning().record(command.profileId, call.owner.privacyEpoch, command.operationId)
            ?: return LearningReply.Failed("DELETION_RECOVERY_REQUIRED", false)
        val request = learningJson.decodeFromString(WirePersonalDeletionRequest.serializer(), row.payloadJson)
        val before = api.readProfile(call)
        if (before is LearningReply.Failed) return before
        val previous = (before as LearningReply.Stored).value
        // Replay the original operation to distinguish its receipt from another device's erase.
        // Never rewrite the command to a newer epoch and delete newly created learning.
        return when (val reply = api.deleteLearningHistory(call, request)) {
            is LearningReply.Stored -> {
                val latest = if (previous.profileContext.privacyEpoch >= reply.value.profileContext.privacyEpoch) previous else {
                    when (val read = api.readProfile(call)) {
                        is LearningReply.Failed -> return read
                        is LearningReply.Stored -> read.value
                    }
                }
                profiles.reconcileDeletion(latest, request.operationId, call.owner.revision, reply.value)
                LearningReply.Stored(call, Unit)
            }
            is LearningReply.Failed -> {
                if (reply.code != "STALE_EPOCH") return reply
                val latest = when (val read = api.readProfile(call)) {
                    is LearningReply.Failed -> return read
                    is LearningReply.Stored -> read.value
                }
                if (latest.profileContext.privacyEpoch <= call.owner.privacyEpoch) return reply
                profiles.reconcileDeletion(latest, request.operationId, call.owner.revision, null)
                LearningReply.Stored(call, Unit)
            }
        }
    }

    suspend fun hasPendingDeletion() = database.learning().pendingOwnerTransitions().any { it.kind == "history_deletion" }

    /** Source writes and ACKs must reach a fixed server watermark before changing Auth. */
    suspend fun prepareExistingAccount(): LearningReply<WirePersonalMergeIntent> = mergeLane.withLock {
        val current = profiles.refresh()
        if (current !is LearningReply.Stored || current.value.profileKind != "anonymous") return LearningReply.Failed("EXPLICIT_ACCOUNT_SWITCH_REQUIRED", false)
        if (profiles.hasPendingSettings()) return LearningReply.Failed("SETTINGS_PENDING", true)
        val call = profiles.current() ?: return LearningReply.Failed("SESSION_UNAVAILABLE", true)
        if (database.learning().pendingLocalCommand(call.owner.profileId.toString()) != null) return LearningReply.Failed("SYNC_REQUIRED_BEFORE_MERGE", true)
        val flush = journal.flushAll(call)
        if (flush is LearningReply.Failed) return flush
        if (!sourceSettled(call.owner))
            return LearningReply.Failed("SYNC_REQUIRED_BEFORE_MERGE", true)
        var cursor: String? = null
        repeat(100) {
            when(val page = api.readSyncPage(call, call.owner.privacyEpoch, cursor, 50)) {
                is LearningReply.Failed -> return page
                is LearningReply.Stored -> {
                    if (page.value.resetRequired) return LearningReply.Failed("SESSION_CHANGED", false)
                    val next = page.value.cursor ?: return LearningReply.Failed("SYNC_NOT_READY", true)
                    if (page.value.records.size < 50 || next == cursor) {
                        val request = WirePersonalMergePrepare(UUID.randomUUID().toString(), call.owner.profileWire(), next, "5")
                        val reply = api.prepareMerge(call, request)
                        if (reply is LearningReply.Stored) active.saveAccountState(learningJson.encodeToString(ProtectedMerge.serializer(),
                            ProtectedMerge(reply.value, UUID.randomUUID().toString(), call.owner.profileId.toString(), call.owner.privacyEpoch,
                                call.owner.revision, call.session.subjectId.toString(),sourceReference=AuthSessionStore.activeBinding(context).reference,
                                sourceInstallation=call.session.installationId.toString())))
                        return reply
                    }
                    cursor = next
                }
            }
        }
        return LearningReply.Failed("SYNC_NOT_READY", true)
    }

    /** Called only with an actual Google credential and nonce from the approved provider flow. */
    suspend fun signInExistingGoogle(idToken: String, nonce: String?, mayAct:()->Boolean={true}): LearningReply<WirePersonalProfileView> =
        mergeLane.withLock{signInExistingGoogleLocked(idToken,nonce,mayAct)}
    private suspend fun signInExistingGoogleLocked(idToken:String,nonce:String?,mayAct:()->Boolean):LearningReply<WirePersonalProfileView> {
        if(!mayAct())return LearningReply.Failed("ACCOUNT_ACTION_CONTEXT_CHANGED",false)
        val old = active.identity()
        var pending = old.accountState?.let { learningJson.decodeFromString(ProtectedMerge.serializer(), it) }
            ?: return LearningReply.Failed("MERGE_PREPARATION_REQUIRED", false)
        val local = database.learning().currentOwner() ?: return LearningReply.Failed("SESSION_UNAVAILABLE", true)
        if (local.profileId != pending.sourceProfile || local.privacyEpoch!=pending.sourceEpoch ||
            !(local.status=="active" && local.revision==pending.sourceRevision || local.status=="transition" && local.revision==pending.sourceRevision+1))
            return LearningReply.Failed("SESSION_CHANGED", false)
        if(local.status=="transition" && database.learning().pendingOwnerTransitions().none{
                it.operationId==pending.completeOperation && it.kind=="merge" && it.profileId==pending.sourceProfile && it.ownerRevision==pending.sourceRevision})
            return LearningReply.Failed("SESSION_CHANGED",false)
        val target = AuthSessionStore(context, "learning-merge-target-${pending.intent.intentId}")
        if (target.identity().session == null) target.initializeIsolated(StoredIdentity(installationId = old.installationId,
            accountState = learningJson.encodeToString(ProtectedMerge.serializer(), pending)))
        val saved=target.identity().accountState?.let{learningJson.decodeFromString(ProtectedMerge.serializer(),it)}
        if(saved!=null) {
            require(saved.intent==pending.intent && saved.completeOperation==pending.completeOperation && saved.sourceProfile==pending.sourceProfile && saved.sourceEpoch==pending.sourceEpoch && saved.sourceRevision==pending.sourceRevision)
            pending=saved
        }
        val auth = mergeTargetAuthFactory(target)
        try {
            if(!mayAct())return LearningReply.Failed("ACCOUNT_ACTION_CONTEXT_CHANGED",false)
            // Target provider failure/cancellation must leave the valid source usable.
            // No source fence exists until the actual target session is protected.
            if(target.identity().session==null)auth.signInGoogle(idToken, nonce)
            if(!mayAct())return LearningReply.Failed("ACCOUNT_ACTION_CONTEXT_CHANGED",false)
            if(target.identity().session==null)return LearningReply.Failed("TARGET_SESSION_RECOVERY_REQUIRED",true)
            val latest=database.learning().currentOwner()?:return LearningReply.Failed("SESSION_CHANGED",false)
            if(latest.profileId!=pending.sourceProfile || latest.privacyEpoch!=pending.sourceEpoch)
                return LearningReply.Failed("SESSION_CHANGED",false)
            if(latest.status=="active" && latest.revision==pending.sourceRevision) {
                val source = profiles.current() ?: return LearningReply.Failed("SESSION_UNAVAILABLE", true)
                if(source.owner.profileId.toString()!=pending.sourceProfile || source.owner.privacyEpoch!=pending.sourceEpoch ||
                    source.owner.revision!=pending.sourceRevision || source.session.subjectId.toString()!=pending.sourceSubject)
                    return LearningReply.Failed("SESSION_CHANGED",false)
                val binding=AuthSessionStore.activeBinding(context)
                if(pending.sourceReference!=null && pending.sourceReference!=binding.reference || pending.sourceInstallation!=null && pending.sourceInstallation!=source.session.installationId.toString())
                    return LearningReply.Failed("SESSION_CHANGED",false)
                pending=pending.copy(sourceReference=binding.reference,sourceInstallation=source.session.installationId.toString())
                when(val frozen=freezeComplete(pending,target,auth){database.learning().currentOwner()==latest}) {
                    is LearningReply.Failed->return frozen
                    is LearningReply.Stored->pending=frozen.value
                }
                if(!mayAct())return LearningReply.Failed("ACCOUNT_ACTION_CONTEXT_CHANGED",false)
                val fenced=database.withTransaction {
                    if(database.learning().currentOwner()!=latest)return@withTransaction false
                    if(!mayAct() || !sourceSettled(source.owner))return@withTransaction false
                    database.learning().fenceOwner(source.owner, UUID.fromString(pending.completeOperation), "merge", "learning-merge-target-${pending.intent.intentId}")
                    FeatureLifecycle(database).isolateForOwnerTransition(source.owner.profileId)
                    true
                }
                if(!fenced)return LearningReply.Failed(if(mayAct())"SYNC_REQUIRED_BEFORE_MERGE" else "ACCOUNT_ACTION_CONTEXT_CHANGED",true)
            } else if(latest.status!="transition" || latest.revision!=pending.sourceRevision+1 ||
                database.learning().pendingOwnerTransitions().none{it.operationId==pending.completeOperation && it.kind=="merge" && it.profileId==pending.sourceProfile && it.ownerRevision==pending.sourceRevision})
                return LearningReply.Failed("SESSION_CHANGED",false)
            return completeTarget(pending, target, auth,mayAct)
        } finally { auth.close() }
    }

    /** Rechecked in the owner-fence transaction: an accepted answer after prepare
     * must not disappear merely because the earlier merge intent was already issued.
     */
    private suspend fun sourceSettled(owner:OwnerContext):Boolean {
        val profile=owner.profileId.toString()
        return database.learning().pendingLocalCommand(profile)==null && database.learning().pendingProfileSettings(profile)==null &&
            database.learning().pending(profile,owner.privacyEpoch).isEmpty() && database.progress().pendingPreferences(profile).isEmpty() &&
            database.aiLearning().pendingPreferences(profile).isEmpty() && database.aiLearning().unsettled(profile,owner.privacyEpoch).isEmpty() &&
            database.progress().unsettled(profile,owner.privacyEpoch).isEmpty() &&
            database.review().pending(profile,owner.privacyEpoch,owner.revision).none{it.kind!="legacy_attempt_admission"} &&
            database.review().unresolvedQuestionDeliveries(profile,owner.privacyEpoch).isEmpty() && database.segments().pending(profile,owner.privacyEpoch).isEmpty()
    }

    /** Fresh first execution only: freeze target epoch/body before the source owner is fenced. */
    private suspend fun freezeComplete(original:ProtectedMerge,target:AuthSessionStore,auth:ProfileAuth,isCurrent:suspend()->Boolean):LearningReply<ProtectedMerge> {
        val session=auth.ensureSession(false){error("No target signup")}
        if(!isCurrent())return LearningReply.Failed("SESSION_CHANGED",false)
        if(original.completeJson!=null) {
            if(original.targetSubject!=session.subjectId.toString() || original.targetInstallation!=session.installationId.toString() || original.targetSession!=session.sessionId.toString())
                return LearningReply.Failed("MERGE_TARGET_CHANGED",false)
            require(LearningJson.hash(original.completeJson)==original.completeHash)
            val fixed=learningJson.decodeFromString(WirePersonalMergeComplete.serializer(),original.completeJson)
            require(fixed.operationId==original.completeOperation && fixed.intentId==original.intent.intentId)
            return LearningReply.Stored(LearningCallScope(OwnerContext(UUID.fromString(fixed.profileContext.profileId),fixed.profileContext.privacyEpoch,original.sourceRevision,UUID.randomUUID()),session),original)
        }
        val provisional=OwnerContext(UUID.randomUUID(),0,1,UUID.randomUUID())
        var call=LearningCallScope(provisional,session)
        val targetApi=mergeApiFactory(LearningSessionSource{if(isCurrent())call else null})
        val boot=targetApi.bootstrapProfile(call,WirePersonalBootstrapRequest(UUID.randomUUID().toString(),null,"5"))
        if(boot !is LearningReply.Stored)return boot as LearningReply.Failed
        call=call.copy(owner=provisional.copy(profileId=UUID.fromString(boot.value.profileContext.profileId),privacyEpoch=boot.value.profileContext.privacyEpoch))
        val current=targetApi.readProfile(call)
        if(current !is LearningReply.Stored)return current as LearningReply.Failed
        if(current.value.profileKind!="linked")return LearningReply.Failed("PERMANENT_ACCOUNT_REQUIRED",false)
        val request=WirePersonalMergeComplete(original.completeOperation,current.value.profileContext,original.intent.intentId,original.intent.proofToken,"5")
        val bytes=learningJson.encodeToString(WirePersonalMergeComplete.serializer(),request)
        val pending=original.copy(targetSubject=session.subjectId.toString(),targetInstallation=session.installationId.toString(),targetSession=session.sessionId.toString(),completeJson=bytes,completeHash=LearningJson.hash(bytes))
        if(!isCurrent())return LearningReply.Failed("SESSION_CHANGED",false)
        target.saveAccountState(learningJson.encodeToString(ProtectedMerge.serializer(),pending))
        return LearningReply.Stored(call,pending)
    }

    private suspend fun completeTarget(original: ProtectedMerge, target: AuthSessionStore, targetAuth: ProfileAuth,
        mayAct:()->Boolean={true}): LearningReply<WirePersonalProfileView> {
        if(!mergeRecovery.current(original))return LearningReply.Failed("SESSION_CHANGED",false)
        var pending=original
        if(pending.receipt!=null)return adoptMerged(pending,target,mayAct)
        if(pending.recoveryPhase=="reprepare_required" || pending.recoveryPhase=="preparing")
            return LearningReply.Failed("MERGE_REPREPARE_REQUIRED",false)
        if(pending.completeJson!=null && (pending.targetSubject==null || pending.targetInstallation==null || pending.targetSession==null))
            return LearningReply.Failed("MERGE_TARGET_BINDING_UNAVAILABLE",false)
        val session=targetAuth.ensureSession(false){error("No anonymous target signup")}
        if((pending.targetSubject!=null && pending.targetSubject!=session.subjectId.toString()) ||
            (pending.targetInstallation!=null && pending.targetInstallation!=session.installationId.toString()) ||
            (pending.targetSession!=null && pending.targetSession!=session.sessionId.toString()))return LearningReply.Failed("MERGE_TARGET_CHANGED",false)
        if(pending.completeJson==null)return LearningReply.Failed("MERGE_ORIGINAL_REQUEST_UNAVAILABLE",false)
        val bytes=requireNotNull(pending.completeJson)
        require(LearningJson.hash(bytes)==pending.completeHash)
        val request=learningJson.decodeFromString(WirePersonalMergeComplete.serializer(),bytes)
        require(request.operationId==pending.completeOperation && request.intentId==pending.intent.intentId && request.proofToken==pending.intent.proofToken)
        if(!mayAct())return LearningReply.Failed("ACCOUNT_ACTION_CONTEXT_CHANGED",false)
        pending=pending.copy(completeDispatched=true)
        if(!mergeRecovery.current(pending))return LearningReply.Failed("SESSION_CHANGED",false)
        target.saveAccountState(learningJson.encodeToString(ProtectedMerge.serializer(),pending))
        val owner=OwnerContext(UUID.fromString(request.profileContext.profileId),request.profileContext.privacyEpoch,pending.sourceRevision+1,UUID.randomUUID())
        val call=LearningCallScope(owner,session)
        val targetApi=mergeApiFactory(LearningSessionSource{if(mergeRecovery.current(pending))call else null})
        val complete=targetApi.completeMerge(call,request)
        if(!mergeRecovery.current(pending))return LearningReply.Failed("SESSION_CHANGED",false)
        if(complete is LearningReply.Failed) {
            // Only this typed error, emitted at the bound complete boundary, proves no merge.
            val definite=complete.code=="MERGE_EXPIRED"
            val failed=pending.copy(recoveryPhase=if(definite)"reprepare_required" else "reconciling",reasonCode=complete.code,definiteRejectionCode=if(definite)complete.code else null)
            target.saveAccountState(learningJson.encodeToString(ProtectedMerge.serializer(),failed))
            mergeRecovery.mark(failed,if(definite)"reprepare_required" else "pending")
            return complete
        }
        val receipt=(complete as LearningReply.Stored).value
        require(receipt.operationId==pending.completeOperation && receipt.sourceProfileId==pending.sourceProfile && receipt.canonicalProfile.profileContext==request.profileContext)
        val accepted=pending.copy(receipt=receipt,recoveryPhase="adopting",reasonCode=null)
        target.saveAccountState(learningJson.encodeToString(ProtectedMerge.serializer(),accepted))
        if(!mergeRecovery.mark(accepted,"adopting"))return LearningReply.Failed("SESSION_CHANGED",false)
        return adoptMerged(accepted,target,mayAct)
    }

    private suspend fun adoptMerged(pending: ProtectedMerge, target: AuthSessionStore,mayAct:()->Boolean={true}): LearningReply<WirePersonalProfileView> {
        val receipt = requireNotNull(pending.receipt)
        if(!mergeRecovery.current(pending))return LearningReply.Failed("SESSION_CHANGED",false)
        val local = requireNotNull(database.learning().currentOwner())
        val command = UUID.fromString(pending.completeOperation)
        if (local.status == "active" && local.profileId == receipt.canonicalProfile.profileContext.profileId) {
            val latest = profiles.refresh()
            if (latest is LearningReply.Stored && latest.value.profileContext.privacyEpoch >= receipt.canonicalProfile.profileContext.privacyEpoch) database.withTransaction {
                eraseLocalLearning(database, pending.sourceProfile, pending.sourceEpoch)
                database.learning().updateOwnerJournal(command.toString(), "complete")
            }
            return latest
        }
        // Merge receipts are immutable history. The target may already have a newer
        // deletion generation, so validate a current protected read before adoption.
        val targetReader=mergeTargetAuthFactory(target)
        val confirmedProfile=try{
            val session=targetReader.ensureSession(false){error("No signup during merge recovery")}
            val owner=OwnerContext(UUID.fromString(receipt.canonicalProfile.profileContext.profileId),receipt.canonicalProfile.profileContext.privacyEpoch,
                local.revision+1,UUID.randomUUID())
            val call=LearningCallScope(owner,session)
            val reader=mergeApiFactory(LearningSessionSource{call})
            when(val latest=reader.readProfile(call)){
                is LearningReply.Failed->return latest
                is LearningReply.Stored->{
                    require(latest.value.profileContext.profileId==receipt.canonicalProfile.profileContext.profileId &&
                        latest.value.profileContext.privacyEpoch>=receipt.canonicalProfile.profileContext.privacyEpoch)
                    latest.value
                }
            }
        }finally{targetReader.close()}
        val revision = if (local.status == "active" && local.profileId == pending.sourceProfile) {
            val call = profiles.current() ?: return LearningReply.Failed("SESSION_UNAVAILABLE", true)
            database.withTransaction{
                if(!sourceSettled(call.owner))return@withTransaction null
                val fenced=database.learning().fenceOwner(call.owner,command,"merge","learning-merge-target-${pending.intent.intentId}")
                FeatureLifecycle(database).isolateForOwnerTransition(call.owner.profileId)
                fenced.revision
            }?:return LearningReply.Failed("SYNC_REQUIRED_BEFORE_MERGE",true)
        } else {
            require(local.status == "transition" && local.profileId == pending.sourceProfile)
            local.revision
        }
        if(!mayAct())return LearningReply.Failed("ACCOUNT_ACTION_CONTEXT_CHANGED",false)
        if(!mergeRecovery.current(pending))return LearningReply.Failed("SESSION_CHANGED",false)
        val targetIdentity = target.identity()
        val previous = active.identity()
        if (previous.session?.user?.id != targetIdentity.session?.user?.id)
            active.replaceForTransition(pending.sourceSubject, targetIdentity.copy(bootstrapProfileId = receipt.canonicalProfile.profileContext.profileId,
                bootstrapOperationId = UUID.randomUUID().toString(), accountState = null))
        return profiles.adoptIdentity(mergeTargetAuthFactory(active).adoptionAuth, confirmedProfile, revision,
            pending.sourceProfile to pending.sourceEpoch, command.toString(),mayAdopt=mayAct)
    }

    /** An authenticated target may await an explicit retry while the source is still usable. */
    private suspend fun preFenceMerge():ProtectedMerge? {
        val local=database.learning().currentOwner()?.takeIf{it.status=="active"}?:return null
        val saved=runCatching{active.readExistingIdentity()}.getOrNull()?:return null
        val source=runCatching{saved.accountState?.let{learningJson.decodeFromString(ProtectedMerge.serializer(),it)}}.getOrNull()?:return null
        if(source.sourceProfile!=local.profileId || source.sourceEpoch!=local.privacyEpoch || source.sourceRevision!=local.revision || source.receipt!=null)return null
        val target=runCatching{AuthSessionStore(context,"learning-merge-target-${source.intent.intentId}").readExistingIdentity()}.getOrNull()?:return null
        if(target.session==null)return null
        return target.accountState?.let{learningJson.decodeFromString(ProtectedMerge.serializer(),it)}?.takeIf{
            it.intent==source.intent && it.completeOperation==source.completeOperation && it.sourceProfile==source.sourceProfile && it.sourceEpoch==source.sourceEpoch && it.sourceRevision==source.sourceRevision
        }
    }

    suspend fun hasPendingAccountTransition():Boolean=pendingLinkedTarget()!=null || pendingMergeRecovery()!=null
    suspend fun pendingMergeRecovery():MergeRecoveryProjection? {
        val row=mergeRecovery.head()
        if(row==null) {
            val pending=preFenceMerge()?:return null
            return MergeRecoveryProjection(UUID.fromString(pending.completeOperation),MergeRecoveryPhase.RECONCILING,"MERGE_CONFIRMATION_REQUIRED")
        }
        val reference=row.isolatedSessionReference?:return MergeRecoveryProjection(UUID.fromString(row.operationId),MergeRecoveryPhase.RECONCILING,"MERGE_RECOVERY_REQUIRED")
        val pending=runCatching{AuthSessionStore(context,reference).readExistingIdentity()?.accountState?.let{learningJson.decodeFromString(ProtectedMerge.serializer(),it)}}.getOrNull()
        return MergeRecoveryProjection(UUID.fromString(row.operationId),when(pending?.recoveryPhase){
            "reprepare_required"->MergeRecoveryPhase.REPREPARE_REQUIRED;"preparing"->MergeRecoveryPhase.PREPARING;"adopting"->MergeRecoveryPhase.ADOPTING;else->MergeRecoveryPhase.RECONCILING
        },pending?.reasonCode)
    }
    suspend fun recoverMerge(allowReprepare:Boolean=false,mayAct:()->Boolean={true}): LearningReply<WirePersonalProfileView>? = mergeLane.withLock {
        val row=mergeRecovery.head()
        if(row==null) {
            preFenceMerge()?:return@withLock null
            if(!allowReprepare)return@withLock LearningReply.Failed("MERGE_CONFIRMATION_REQUIRED",false)
            return@withLock signInExistingGoogleLocked("",null,mayAct)
        }
        val reference=row.isolatedSessionReference?:return@withLock LearningReply.Failed("MERGE_RECOVERY_REQUIRED",false)
        val store=AuthSessionStore(context,reference)
        val pending=store.readExistingIdentity()?.accountState?.let{learningJson.decodeFromString(ProtectedMerge.serializer(),it)}
            ?:return@withLock LearningReply.Failed("MERGE_RECOVERY_REQUIRED",true)
        if(!mergeRecovery.current(pending))return@withLock LearningReply.Failed("SESSION_CHANGED",false)
        if(pending.receipt!=null)return@withLock adoptMerged(pending,store,mayAct)
        if(pending.completeJson!=null && !pending.completeDispatched && !allowReprepare)return@withLock LearningReply.Failed("MERGE_CONFIRMATION_REQUIRED",false)
        if(pending.recoveryPhase in setOf("reprepare_required","preparing")) {
            if(!allowReprepare)return@withLock LearningReply.Failed("MERGE_REPREPARE_REQUIRED",false)
            return@withLock reprepareMerge(pending,store,mayAct)
        }
        val auth=mergeTargetAuthFactory(store)
        try{completeTarget(pending,store,auth,mayAct)}finally{auth.close()}
    }

    /** An explicit retry may replace only a definitively rejected, still-owned merge. */
    private suspend fun reprepareMerge(original:ProtectedMerge,store:AuthSessionStore,mayAct:()->Boolean):LearningReply<WirePersonalProfileView> {
        if(!mayAct())return LearningReply.Failed("ACCOUNT_ACTION_CONTEXT_CHANGED",false)
        if(original.receipt!=null || original.definiteRejectionCode!="MERGE_EXPIRED" || original.completeJson==null)
            return LearningReply.Failed("MERGE_RESULT_UNCERTAIN",false)
        var pending=original
        var sourceAuth:LinkedTargetAuth?=null
        var targetAuth:LinkedTargetAuth?=null
        suspend fun hold(code:String,retryable:Boolean=false):LearningReply.Failed {
            if(mergeRecovery.current(pending))store.saveAccountState(learningJson.encodeToString(ProtectedMerge.serializer(),pending.copy(reasonCode=code)))
            return LearningReply.Failed(code,retryable)
        }
        try {
            if(!mergeRecovery.current(pending))return LearningReply.Failed("SESSION_CHANGED",false)
            val sourceReference=pending.sourceReference?:return hold("MERGE_SOURCE_BINDING_UNAVAILABLE")
            val sourceInstallation=pending.sourceInstallation?:return hold("MERGE_SOURCE_BINDING_UNAVAILABLE")
            if(AuthSessionStore.activeBinding(context).reference!=sourceReference)return hold("MERGE_SOURCE_CHANGED")
            val sourceStore=AuthSessionStore(context,sourceReference)
            if(sourceStore.readExistingIdentity()?.session==null)return hold("MERGE_SOURCE_AUTH_REQUIRED",true)
            sourceAuth=mergeSourceAuthFactory(sourceStore)
            val sourceSession=sourceAuth.ensureSession(false){error("No guest during merge recovery")}
            if(sourceSession.subjectId.toString()!=pending.sourceSubject || sourceSession.installationId.toString()!=sourceInstallation)
                return hold("MERGE_SOURCE_CHANGED")
            val sourceOwner=OwnerContext(UUID.fromString(pending.sourceProfile),pending.sourceEpoch,pending.sourceRevision+1,UUID.randomUUID())
            val sourceCall=LearningCallScope(sourceOwner,sourceSession)
            val sourceApi=mergeApiFactory(LearningSessionSource{if(mergeRecovery.current(pending))sourceCall else null})
            val sourceProfile=when(val latest=sourceApi.readProfile(sourceCall)) {
                is LearningReply.Failed->return hold(latest.code,latest.retryable)
                is LearningReply.Stored->latest.value
            }
            if(sourceProfile.profileContext.profileId!=pending.sourceProfile || sourceProfile.profileContext.privacyEpoch!=pending.sourceEpoch)
                return hold("MERGE_SOURCE_EPOCH_CHANGED")
            if(sourceProfile.profileKind!="anonymous")return hold("MERGE_SOURCE_OWNERSHIP_CHANGED")
            if(!mergeRecovery.sourceSettled(pending))return hold("SYNC_REQUIRED_BEFORE_MERGE",true)
            targetAuth=mergeTargetAuthFactory(store)
            val targetSession=targetAuth.ensureSession(false){error("No target signup during merge recovery")}
            if(targetSession.subjectId.toString()!=pending.targetSubject || targetSession.installationId.toString()!=pending.targetInstallation || pending.targetSession!=null && targetSession.sessionId.toString()!=pending.targetSession)
                return hold("MERGE_TARGET_CHANGED")
            val frozen=learningJson.decodeFromString(WirePersonalMergeComplete.serializer(),requireNotNull(pending.completeJson))
            require(LearningJson.hash(requireNotNull(pending.completeJson))==pending.completeHash)
            val targetCall=LearningCallScope(OwnerContext(UUID.fromString(frozen.profileContext.profileId),frozen.profileContext.privacyEpoch,pending.sourceRevision+1,UUID.randomUUID()),targetSession)
            val targetApi=mergeApiFactory(LearningSessionSource{if(mergeRecovery.current(pending))targetCall else null})
            val targetProfile=when(val latest=targetApi.readProfile(targetCall)) {
                is LearningReply.Failed->return hold(latest.code,latest.retryable)
                is LearningReply.Stored->latest.value
            }
            if(targetProfile.profileKind!="linked" || targetProfile.profileContext.profileId!=frozen.profileContext.profileId || targetProfile.profileContext.privacyEpoch<frozen.profileContext.privacyEpoch)
                return hold("MERGE_TARGET_CHANGED")
            val previousPreparation=pending.replacement
            if(previousPreparation!=null && previousPreparation.prepareDispatched && previousPreparation.intent==null && previousPreparation.childCompleteJson==null) {
                if(pending.abandonedPreparations.size>=20)return hold("MERGE_PREPARATION_RETRY_LIMIT",false)
                pending=pending.copy(replacement=null,abandonedPreparations=pending.abandonedPreparations+previousPreparation)
            }
            if(pending.replacement==null) {
                pending=pending.copy(recoveryPhase="preparing",replacement=ProtectedMergeReplacement(UUID.randomUUID().toString(),UUID.randomUUID().toString()))
                store.saveAccountState(learningJson.encodeToString(ProtectedMerge.serializer(),pending))
                if(!mergeRecovery.mark(pending,"preparing"))return LearningReply.Failed("SESSION_CHANGED",false)
            }
            var replacement=requireNotNull(pending.replacement)
            if(replacement.prepareJson==null) {
                var cursor:String?=null
                var watermark:String?=null
                for(index in 0 until 100) {
                    val page=when(val result=sourceApi.readSyncPage(sourceCall,pending.sourceEpoch,cursor,50)) {
                        is LearningReply.Failed->return hold(result.code,result.retryable)
                        is LearningReply.Stored->result.value
                    }
                    if(page.resetRequired)return hold("MERGE_SOURCE_EPOCH_CHANGED")
                    val next=page.cursor?:return hold("SYNC_NOT_READY",true)
                    if(page.records.size<50 || next==cursor){watermark=next;break}
                    cursor=next
                }
                val request=WirePersonalMergePrepare(replacement.prepareOperation,sourceOwner.profileWire(),watermark?:return hold("SYNC_NOT_READY",true),"5")
                val bytes=learningJson.encodeToString(WirePersonalMergePrepare.serializer(),request)
                replacement=replacement.copy(prepareJson=bytes,prepareHash=LearningJson.hash(bytes))
                pending=pending.copy(replacement=replacement)
                if(!mergeRecovery.current(pending))return LearningReply.Failed("SESSION_CHANGED",false)
                store.saveAccountState(learningJson.encodeToString(ProtectedMerge.serializer(),pending))
            }
            if(!mayAct())return hold("ACCOUNT_ACTION_CONTEXT_CHANGED")
            var intent=replacement.intent
            if(intent==null) {
                val bytes=requireNotNull(replacement.prepareJson)
                require(LearningJson.hash(bytes)==replacement.prepareHash)
                val request=learningJson.decodeFromString(WirePersonalMergePrepare.serializer(),bytes)
                require(request.operationId==replacement.prepareOperation && request.profileContext==sourceOwner.profileWire())
                replacement=replacement.copy(prepareDispatched=true)
                pending=pending.copy(replacement=replacement)
                if(!mergeRecovery.current(pending) || !mayAct())return hold("ACCOUNT_ACTION_CONTEXT_CHANGED")
                store.saveAccountState(learningJson.encodeToString(ProtectedMerge.serializer(),pending))
                when(val result=sourceApi.prepareMerge(sourceCall,request)) {
                    is LearningReply.Failed->return hold(result.code,result.retryable)
                    is LearningReply.Stored->intent=result.value
                }
                val confirmedIntent=requireNotNull(intent)
                require(confirmedIntent.sourceProfileId==pending.sourceProfile && confirmedIntent.sourcePrivacyEpoch==pending.sourceEpoch)
                replacement=replacement.copy(intent=intent)
                pending=pending.copy(replacement=replacement)
                if(!mergeRecovery.current(pending))return LearningReply.Failed("SESSION_CHANGED",false)
                store.saveAccountState(learningJson.encodeToString(ProtectedMerge.serializer(),pending))
            }
            val acceptedIntent=requireNotNull(intent)
            if(replacement.childCompleteJson==null) {
                val request=WirePersonalMergeComplete(replacement.completeOperation,targetProfile.profileContext,acceptedIntent.intentId,acceptedIntent.proofToken,"5")
                val bytes=learningJson.encodeToString(WirePersonalMergeComplete.serializer(),request)
                replacement=replacement.copy(childCompleteJson=bytes,childCompleteHash=LearningJson.hash(bytes))
                pending=pending.copy(replacement=replacement)
                if(!mergeRecovery.current(pending))return LearningReply.Failed("SESSION_CHANGED",false)
                store.saveAccountState(learningJson.encodeToString(ProtectedMerge.serializer(),pending))
            }
            val bytes=requireNotNull(replacement.childCompleteJson)
            require(LearningJson.hash(bytes)==replacement.childCompleteHash)
            val frozenChild=learningJson.decodeFromString(WirePersonalMergeComplete.serializer(),bytes)
            require(frozenChild.operationId==replacement.completeOperation && frozenChild.intentId==acceptedIntent.intentId && frozenChild.profileContext.profileId==targetProfile.profileContext.profileId)
            val next=ProtectedMerge(acceptedIntent,replacement.completeOperation,pending.sourceProfile,pending.sourceEpoch,pending.sourceRevision,pending.sourceSubject,
                sourceReference=sourceReference,sourceInstallation=sourceInstallation,targetSubject=pending.targetSubject,targetInstallation=pending.targetInstallation,targetSession=pending.targetSession,
                completeJson=bytes,completeHash=replacement.childCompleteHash,predecessorOperation=pending.completeOperation)
            val target=AuthSessionStore(context,"learning-merge-target-${acceptedIntent.intentId}")
            val existing=target.readExistingIdentity()
            if(existing==null)target.initializeIsolated(store.identity().copy(accountState=learningJson.encodeToString(ProtectedMerge.serializer(),next)))
            else require(existing.accountState?.let{learningJson.decodeFromString(ProtectedMerge.serializer(),it)}==next)
            if(!mayAct())return hold("ACCOUNT_ACTION_CONTEXT_CHANGED")
            if(!mergeRecovery.replace(pending,next))return LearningReply.Failed("SESSION_CHANGED",false)
            val auth=mergeTargetAuthFactory(target)
            return try{completeTarget(next,target,auth,mayAct)}finally{auth.close()}
        }catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
         catch(_:Exception){return hold("MERGE_PROTECTED_RECOVERY_REQUIRED",true)}
        finally{sourceAuth?.close();targetAuth?.close()}
    }

    /** New identity linking keeps the anonymous user's subject and profile. */
    suspend fun linkNewGoogle(idToken: String, nonce: String?,mayAct:()->Boolean={true}): LearningReply<WirePersonalProfileView> {
        if(!mayAct())return LearningReply.Failed("ACCOUNT_ACTION_CONTEXT_CHANGED",false)
        val before = profiles.current() ?: return LearningReply.Failed("SESSION_UNAVAILABLE", true)
        val auth = SupabaseProfileAuth(active)
        try {
            auth.ensureSession(false) { error("No signup during identity link") }
            if(!mayAct())return LearningReply.Failed("ACCOUNT_ACTION_CONTEXT_CHANGED",false)
            auth.linkGoogle(idToken, nonce)
            val after = auth.currentSession() ?: return LearningReply.Failed("SESSION_UNAVAILABLE", true)
            if (after.subjectId != before.session.subjectId) return LearningReply.Failed("MERGE_REQUIRED", false)
            profiles.reloadProtectedAuth(SupabaseProfileAuth(active))
            if(!mayAct())return LearningReply.Failed("ACCOUNT_ACTION_CONTEXT_CHANGED",false)
            return profiles.refresh()
        } finally { auth.close() }
    }
}
