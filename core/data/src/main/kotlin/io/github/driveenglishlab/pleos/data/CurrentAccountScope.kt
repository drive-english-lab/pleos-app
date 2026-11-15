package io.github.driveenglishlab.pleos.data

import android.content.Context
import io.github.driveenglishlab.pleos.domain.OwnerContext
import kotlinx.coroutines.CancellationException

/** R6 composition policy. It is not a provider/SDK capability or an identity recovery action. */
class CurrentAccountScope(private val context:Context,private val database:LearningDatabase) {
    companion object { const val accountActionsEnabled=false }
    enum class PendingKind { MERGE, LINKED_TARGET, LOGOUT, OWNER_TRANSITION, PROTECTED_IDENTITY, PROTECTED_SESSION, OWNER_RECHECK }
    data class PendingAction(val kind:PendingKind,val reasonCode:String)
    private data class Snapshot(val owner:LocalOwner?,val pending:PendingAction?,val sessionPresent:Boolean,val hold:PendingAction?=pending)

    /** Read-only: no SDK initialization/refresh, AtomicFile repair, new key/session, cancel or ACK. */
    suspend fun pendingAccountAction():String?=inspect().pending?.reasonCode
    suspend fun recoveryHold():String?=inspect().hold?.reasonCode
    /** Local preflight only. ProfileAuth and the authenticated API still verify session/owner binding. */
    suspend fun permitsProtectedOperation(owner:OwnerContext):Boolean {
        val state=inspect();val current=state.owner
        return state.hold==null && state.sessionPresent && current!=null && current.status in setOf("active","provisional","delete_pending") &&
            current.profileId==owner.profileId.toString() && current.privacyEpoch==owner.privacyEpoch && current.revision==owner.revision
    }
    private fun pending(kind:PendingKind)=PendingAction(kind,when(kind){
        PendingKind.MERGE,PendingKind.LINKED_TARGET,PendingKind.LOGOUT,PendingKind.OWNER_TRANSITION->"ACCOUNT_TRANSITION_PRESERVED"
        PendingKind.PROTECTED_IDENTITY->"PROTECTED_IDENTITY_UNAVAILABLE"
        PendingKind.PROTECTED_SESSION->"PROTECTED_SESSION_RECHECK_REQUIRED"
        PendingKind.OWNER_RECHECK->"OWNER_RECHECK_REQUIRED"
    })
    private suspend fun inspect():Snapshot {
        var observedOwner:LocalOwner?=null
        try {
            val owner=database.learning().currentOwner()
            observedOwner=owner
            val before=owner
            val binding=AuthSessionStore.activeBinding(context)
            val store=AuthSessionStore(context,binding.reference)
            val diagnostic=store.diagnosticState()
            val identity=if(diagnostic.failure==ProfileConnectionFailure.NONE)store.readExistingIdentity() else null
            val rows=database.openHelper.writableDatabase.query(
                "SELECT operationId,profileId,ownerRevision,kind,status,isolatedSessionReference FROM owner_journal WHERE kind IN ('merge','logout','account_target') AND status NOT IN ('complete','reprepared','cancelled') ORDER BY rowid DESC"
            ).use{cursor->buildList{while(cursor.moveToNext())add(OwnerJournal(cursor.getString(0),cursor.getString(1),cursor.getLong(2),cursor.getString(3),cursor.getString(4),cursor.getString(5)))}}
            if(database.learning().currentOwner()!=before || AuthSessionStore.activeBinding(context)!=binding)
                return Snapshot(owner,pending(PendingKind.OWNER_RECHECK),false)
            val present=diagnostic.sessionPresent==true
            var action:PendingAction?=null
            if(owner!=null) {
                if(rows.any{row->row.kind=="account_target" && row.profileId==owner.profileId &&
                    (row.ownerRevision==owner.revision || row.status=="adopting" && row.ownerRevision+1==owner.revision)})action=pending(PendingKind.LINKED_TARGET)
                if(rows.any{row->row.kind=="merge" && row.profileId==owner.profileId && row.ownerRevision+1==owner.revision})action=pending(PendingKind.MERGE)
                if(rows.any{row->row.kind=="logout" &&
                    (owner.status=="transition" && row.profileId==owner.profileId && row.ownerRevision+1==owner.revision ||
                        owner.status=="provisional" && row.profileId!=owner.profileId && row.ownerRevision+2==owner.revision)})
                    return Snapshot(owner,pending(PendingKind.LOGOUT),present)
                if(owner.status=="transition")return Snapshot(owner,action?:pending(PendingKind.OWNER_TRANSITION),present)
                if(identity?.bootstrapProfileId!=null && identity.bootstrapProfileId!=owner.profileId)
                    return Snapshot(owner,action?:pending(PendingKind.OWNER_RECHECK),present,pending(PendingKind.OWNER_RECHECK))
                identity?.accountState?.let{serialized->
                    val merge=runCatching{learningJson.decodeFromString(ProtectedMerge.serializer(),serialized)}.getOrNull()
                    if(merge?.receipt!=null && merge.receipt.canonicalProfile.profileContext.profileId!=owner.profileId)
                        return Snapshot(owner,action?:pending(PendingKind.OWNER_RECHECK),present,pending(PendingKind.OWNER_RECHECK))
                    if(merge!=null && merge.sourceProfile==owner.profileId && merge.sourceEpoch==owner.privacyEpoch &&
                        (merge.sourceRevision==owner.revision || merge.sourceRevision+1==owner.revision))action=pending(PendingKind.MERGE)
                    val target=runCatching{learningJson.decodeFromString(ProtectedLinkedTarget.serializer(),serialized)}.getOrNull()
                    if(target?.confirmedProfile!=null && target.confirmedProfile.profileContext.profileId!=owner.profileId)
                        return Snapshot(owner,action?:pending(PendingKind.OWNER_RECHECK),present,pending(PendingKind.OWNER_RECHECK))
                    if(target!=null && target.sourceProfile==owner.profileId && target.sourceEpoch==owner.privacyEpoch &&
                        (target.sourceRevision==owner.revision || target.sourceRevision+1==owner.revision))action=pending(PendingKind.LINKED_TARGET)
                    // The account payload is not an authentication claim. Preserve it separately
                    // while the current source session remains independently usable.
                    if(merge==null && target==null)action=pending(PendingKind.PROTECTED_IDENTITY)
                }
            }
            if(diagnostic.failure!=ProfileConnectionFailure.NONE)return Snapshot(owner,pending(PendingKind.PROTECTED_IDENTITY),false)
            if(owner==null) {
                // Only a genuinely untouched installation may enter the existing anonymous bootstrap.
                val retained=database.openHelper.writableDatabase.query(
                    "SELECT 1 FROM owner_journal UNION ALL SELECT 1 FROM learning_record UNION ALL SELECT 1 FROM learning_outbox LIMIT 1"
                ).use{it.moveToFirst()}
                return Snapshot(null,if(retained || identity!=null || diagnostic.encryptedFilePresent==true)pending(PendingKind.OWNER_RECHECK) else null,false)
            }
            if(owner.status !in setOf("active","provisional","delete_pending"))return Snapshot(owner,pending(PendingKind.OWNER_RECHECK),present)
            if(!present) {
                val untouched=action==null && owner.status=="provisional" && database.learning().authCreation(owner.profileId)==null && identity?.creationStarted!=true
                return Snapshot(owner,action?:if(untouched)null else pending(PendingKind.PROTECTED_SESSION),false)
            }
            return Snapshot(owner,action,true,if(action!=null && owner.status=="provisional")action else null)
        }catch(cancelled:CancellationException){throw cancelled}
         catch(_:Exception){return Snapshot(observedOwner,pending(PendingKind.PROTECTED_IDENTITY),false)}
    }
}
