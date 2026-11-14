package io.github.driveenglishlab.pleos.data

import android.content.Context
import androidx.room.withTransaction
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.data.wire.*
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
internal data class ProtectedMerge(val intent: WirePersonalMergeIntent, val completeOperation: String,
    val sourceProfile: String, val sourceEpoch: Long, val sourceRevision: Long, val sourceSubject: String,
    val receipt: WirePersonalMergeReceipt? = null)

/** Login is optional. Provider credentials arrive from an approved identity adapter, never Room/UI.
 * Existing-account sign-in uses an isolated SDK store until server merge and local adoption agree.
 */
class AccountRepository(private val context: Context, private val database: LearningDatabase,
    private val profiles: ProfileRepository, private val origin: String, private val allowLocalHttp: Boolean = false) {
    private val active get() = AuthSessionStore(context)
    internal var mergeTargetAuthFactory:(AuthSessionStore)->LinkedTargetAuth={ActualLinkedTargetAuth(it)}
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
    suspend fun prepareExistingAccount(): LearningReply<WirePersonalMergeIntent> {
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
                                call.owner.revision, call.session.subjectId.toString())))
                        return reply
                    }
                    cursor = next
                }
            }
        }
        return LearningReply.Failed("SYNC_NOT_READY", true)
    }

    /** Called only with an actual Google credential and nonce from the approved provider flow. */
    suspend fun signInExistingGoogle(idToken: String, nonce: String?, mayAct:()->Boolean={true}): LearningReply<WirePersonalProfileView> {
        if(!mayAct())return LearningReply.Failed("ACCOUNT_ACTION_CONTEXT_CHANGED",false)
        val old = active.identity()
        val pending = old.accountState?.let { learningJson.decodeFromString(ProtectedMerge.serializer(), it) }
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
        val auth = mergeTargetAuthFactory(target)
        try {
            if(!mayAct())return LearningReply.Failed("ACCOUNT_ACTION_CONTEXT_CHANGED",false)
            // Target provider failure/cancellation must leave the valid source usable.
            // No source fence exists until the actual target session is protected.
            auth.signInGoogle(idToken, nonce)
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

    private suspend fun completeTarget(pending: ProtectedMerge, target: AuthSessionStore, targetAuth: ProfileAuth,mayAct:()->Boolean={true}): LearningReply<WirePersonalProfileView> {
        val session = targetAuth.ensureSession(false) { error("No anonymous target signup") }
        val provisional = OwnerContext(UUID.randomUUID(), 0, 1, UUID.randomUUID())
        var targetCall = LearningCallScope(provisional, session)
        val targetApi = LearningApi(context, origin, LearningSessionSource { targetCall }, allowLocalHttp)
        val boot = targetApi.bootstrapProfile(targetCall, WirePersonalBootstrapRequest(UUID.randomUUID().toString(), null, "5"))
        if (boot !is LearningReply.Stored) return boot as LearningReply.Failed
        targetCall = targetCall.copy(owner = provisional.copy(profileId = UUID.fromString(boot.value.profileContext.profileId), privacyEpoch = boot.value.profileContext.privacyEpoch))
        val current = targetApi.readProfile(targetCall)
        if (current !is LearningReply.Stored) return current as LearningReply.Failed
        if (current.value.profileKind == "anonymous") return LearningReply.Failed("PERMANENT_ACCOUNT_REQUIRED", false)
        val request = WirePersonalMergeComplete(pending.completeOperation, current.value.profileContext, pending.intent.intentId, pending.intent.proofToken, "5")
        if(!mayAct())return LearningReply.Failed("ACCOUNT_ACTION_CONTEXT_CHANGED",false)
        val complete = targetApi.completeMerge(targetCall, request)
        if (complete !is LearningReply.Stored) return complete as LearningReply.Failed
        val accepted = pending.copy(receipt = complete.value)
        target.saveAccountState(learningJson.encodeToString(ProtectedMerge.serializer(), accepted))
        return adoptMerged(accepted, target,mayAct)
    }

    private suspend fun adoptMerged(pending: ProtectedMerge, target: AuthSessionStore,mayAct:()->Boolean={true}): LearningReply<WirePersonalProfileView> {
        val receipt = requireNotNull(pending.receipt)
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
        val targetReader=SupabaseProfileAuth(target)
        val confirmedProfile=try{
            val session=targetReader.ensureSession(false){error("No signup during merge recovery")}
            val owner=OwnerContext(UUID.fromString(receipt.canonicalProfile.profileContext.profileId),receipt.canonicalProfile.profileContext.privacyEpoch,
                local.revision+1,UUID.randomUUID())
            val call=LearningCallScope(owner,session)
            val reader=LearningApi(context,origin,LearningSessionSource{call},allowLocalHttp)
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
        val targetIdentity = target.identity()
        val previous = active.identity()
        if (previous.session?.user?.id != targetIdentity.session?.user?.id)
            active.replaceForTransition(pending.sourceSubject, targetIdentity.copy(bootstrapProfileId = receipt.canonicalProfile.profileContext.profileId,
                bootstrapOperationId = UUID.randomUUID().toString(), accountState = null))
        return profiles.adoptIdentity(SupabaseProfileAuth(active), confirmedProfile, revision,
            pending.sourceProfile to pending.sourceEpoch, command.toString(),mayAdopt=mayAct)
    }

    suspend fun recoverMerge(): LearningReply<WirePersonalProfileView>? {
        val pending = database.learning().pendingOwnerTransitions().firstOrNull { it.kind == "merge" } ?: return null
        val ref = pending.isolatedSessionReference ?: return LearningReply.Failed("MERGE_RECOVERY_REQUIRED", false)
        val store = AuthSessionStore(context, ref)
        val value = store.identity().accountState?.let { learningJson.decodeFromString(ProtectedMerge.serializer(), it) }
            ?: return LearningReply.Failed("MERGE_RECOVERY_REQUIRED", true)
        if (value.receipt != null) return adoptMerged(value, store)
        val auth = SupabaseProfileAuth(store)
        return try { completeTarget(value, store, auth) } finally { auth.close() }
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
