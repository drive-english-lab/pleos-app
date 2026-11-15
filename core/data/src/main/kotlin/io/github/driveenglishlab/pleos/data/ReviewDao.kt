package io.github.driveenglishlab.pleos.data

import androidx.room.*
import io.github.driveenglishlab.pleos.domain.OwnerContext

@Entity(tableName = "review_context", primaryKeys = ["profileId", "privacyEpoch", "selectionId"])
data class LocalReviewContext(val profileId: String, val privacyEpoch: Long, val selectionId: String,
    val version: Long, val payloadJson: String, val payloadHash: String)
@Entity(tableName = "review_operation", indices = [Index(value = ["profileId", "privacyEpoch", "status"])])
data class LocalReviewOperation(@PrimaryKey val operationId: String, val profileId: String, val privacyEpoch: Long,
    val ownerRevision: Long, val kind: String, val targetId: String?, val payloadJson: String, val payloadHash: String,
    val status: String = "pending", val receiptJson: String? = null)
@Entity(tableName = "review_observation", primaryKeys = ["profileId", "privacyEpoch", "deviceStreamId", "sequence"],
    indices = [Index(value = ["observationId"], unique = true)])
data class LocalReviewObservation(val profileId: String, val privacyEpoch: Long, val deviceStreamId: String,
    val sequence: Long, val observationId: String, val operationId: String, val payloadHash: String)
@Entity(tableName = "fresh_practice_intent")
data class LocalFreshPracticeIntent(@PrimaryKey val operationId: String, val profileId: String, val privacyEpoch: Long,
    val ownerRevision: Long, val sourceLessonId: String, val newLessonId: String, val expectedResumeVersion: Long,
    val pinnedRefJson: String, val payloadHash: String, val state: String = "prepared")
@Entity(tableName = "preparation_waiter")
data class LocalPreparationWaiter(@PrimaryKey val requestId: String, val profileId: String, val privacyEpoch: Long,
    val ownerRevision: Long, val contextToken: String, val desiredAction: String, val requestJson: String, val requestHash: String,
    val requestVersion: Long = 0, val responseJson: String? = null, val cancelled: Boolean = false)
@Entity(tableName = "settings_draft", primaryKeys = ["profileId", "privacyEpoch"])
data class LocalSettingsDraft(val profileId: String, val privacyEpoch: Long, val ownerRevision: Long,
    val revision: Long, val baseSettingsVersion: Long, val originalJson: String, val draftJson: String,
    val previewToken: String?, val savedOperationId: String?)
@Entity(tableName = "source6_record", primaryKeys = ["profileId", "privacyEpoch", "kind", "recordId"])
data class LocalSource6Record(val profileId:String,val privacyEpoch:Long,val kind:String,val recordId:String,
    val version:Long,val payloadJson:String,val payloadHash:String)

@Dao
abstract class ReviewDao {
    @Query("SELECT * FROM source6_record WHERE profileId=:profile AND privacyEpoch=:epoch AND kind=:kind AND recordId=:id")
    abstract suspend fun source6Record(profile:String,epoch:Long,kind:String,id:String):LocalSource6Record?
    @Query("SELECT * FROM source6_record WHERE profileId=:profile AND privacyEpoch=:epoch AND kind=:kind")
    abstract suspend fun source6Records(profile:String,epoch:Long,kind:String):List<LocalSource6Record>
    @Insert(onConflict=OnConflictStrategy.ABORT) protected abstract suspend fun insertSource6(value:LocalSource6Record)
    @Query("UPDATE source6_record SET version=:version,payloadJson=:json,payloadHash=:hash WHERE profileId=:profile AND privacyEpoch=:epoch AND kind=:kind AND recordId=:id AND version=:previous")
    protected abstract suspend fun advanceSource6(profile:String,epoch:Long,kind:String,id:String,previous:Long,version:Long,json:String,hash:String):Int
    @Query("DELETE FROM source6_record WHERE profileId=:profile") protected abstract suspend fun clearSource6(profile:String)
    @Query("SELECT * FROM local_owner WHERE slot = 1") protected abstract suspend fun owner(): LocalOwner?
    @Query("SELECT * FROM review_context WHERE profileId=:profile AND privacyEpoch=:epoch AND selectionId=:selection")
    abstract suspend fun context(profile: String, epoch: Long, selection: String): LocalReviewContext?
    @Insert(onConflict = OnConflictStrategy.ABORT) protected abstract suspend fun insertContext(value: LocalReviewContext)
    @Query("UPDATE review_context SET version=:version,payloadJson=:json,payloadHash=:hash WHERE profileId=:profile AND privacyEpoch=:epoch AND selectionId=:selection AND version=:previous")
    protected abstract suspend fun advanceContext(profile: String, epoch: Long, selection: String, previous: Long, version: Long, json: String, hash: String): Int
    @Query("SELECT * FROM review_operation WHERE operationId=:id") abstract suspend fun operation(id: String): LocalReviewOperation?
    @Query("SELECT * FROM review_operation WHERE profileId=:profile AND privacyEpoch=:epoch AND ownerRevision=:revision AND status='pending' AND kind!='capture_language_confirmation_v1' ORDER BY rowid")
    abstract suspend fun pending(profile: String, epoch: Long, revision: Long): List<LocalReviewOperation>
    @Query("SELECT * FROM review_operation WHERE profileId=:profile AND privacyEpoch=:epoch AND kind='qa_delivery_unverified' AND status IN ('pending','isolated') ORDER BY rowid")
    abstract suspend fun unresolvedQuestionDeliveries(profile:String,epoch:Long):List<LocalReviewOperation>
    @Query("UPDATE review_operation SET status='joined' WHERE operationId=:id AND payloadHash=:hash AND status IN ('pending','isolated')")
    protected abstract suspend fun joinQuestionDeliveryRow(id:String,hash:String):Int
    @Insert(onConflict = OnConflictStrategy.ABORT) protected abstract suspend fun insertOperation(value: LocalReviewOperation)
    @Query("UPDATE review_operation SET status=:status,receiptJson=:receipt WHERE operationId=:id AND payloadHash=:hash AND status='pending'")
    protected abstract suspend fun updateReceipt(id: String, hash: String, status: String, receipt: String): Int
    @Query("SELECT COALESCE(MAX(sequence),0) FROM review_observation WHERE profileId=:profile AND privacyEpoch=:epoch AND deviceStreamId=:stream")
    abstract suspend fun frontier(profile: String, epoch: Long, stream: String): Long
    @Query("SELECT * FROM review_observation WHERE observationId=:id") protected abstract suspend fun observation(id: String): LocalReviewObservation?
    @Query("SELECT op.* FROM review_operation op JOIN review_observation ob ON ob.operationId=op.operationId WHERE ob.profileId=:profile AND ob.privacyEpoch=:epoch AND ob.deviceStreamId=:stream AND ob.sequence<=:through AND op.status='pending' ORDER BY ob.sequence")
    abstract suspend fun pendingObservations(profile:String,epoch:Long,stream:String,through:Long):List<LocalReviewOperation>
    @Insert(onConflict = OnConflictStrategy.ABORT) protected abstract suspend fun insertObservation(value: LocalReviewObservation)
    @Query("SELECT * FROM fresh_practice_intent WHERE operationId=:id") abstract suspend fun freshIntent(id: String): LocalFreshPracticeIntent?
    @Insert(onConflict = OnConflictStrategy.ABORT) protected abstract suspend fun insertFresh(value: LocalFreshPracticeIntent)
    @Query("UPDATE fresh_practice_intent SET state=:next WHERE operationId=:id AND state=:previous")
    protected abstract suspend fun advanceFresh(id: String, previous: String, next: String): Int
    @Query("SELECT * FROM preparation_waiter WHERE requestId=:id") abstract suspend fun waiter(id: String): LocalPreparationWaiter?
    @Insert(onConflict = OnConflictStrategy.ABORT) protected abstract suspend fun insertWaiter(value: LocalPreparationWaiter)
    @Query("UPDATE preparation_waiter SET requestVersion=:version,responseJson=:response WHERE requestId=:id AND requestVersion=:previous")
    protected abstract suspend fun advanceWaiter(id: String, previous: Long, version: Long, response: String): Int
    @Query("UPDATE preparation_waiter SET cancelled=1 WHERE requestId=:id") protected abstract suspend fun cancelWaiterRow(id: String)
    @Query("SELECT * FROM settings_draft WHERE profileId=:profile AND privacyEpoch=:epoch") abstract suspend fun draft(profile: String, epoch: Long): LocalSettingsDraft?
    @Query("SELECT * FROM settings_draft WHERE profileId=:profile ORDER BY ownerRevision DESC, revision DESC, privacyEpoch DESC")
    protected abstract suspend fun profileDrafts(profile:String):List<LocalSettingsDraft>
    @Insert(onConflict = OnConflictStrategy.ABORT) protected abstract suspend fun insertDraft(value: LocalSettingsDraft)
    @Update protected abstract suspend fun replaceOwnerDraft(value:LocalSettingsDraft):Int
    @Query("UPDATE settings_draft SET revision=:revision,draftJson=:json,previewToken=:preview,savedOperationId=:saved WHERE profileId=:profile AND privacyEpoch=:epoch AND revision=:previous")
    protected abstract suspend fun advanceDraft(profile: String, epoch: Long, previous: Long, revision: Long, json: String, preview: String?, saved: String?): Int
    @Query("DELETE FROM settings_draft WHERE profileId=:profile AND privacyEpoch=:epoch AND revision=:revision")
    protected abstract suspend fun deleteDraft(profile: String, epoch: Long, revision: Long): Int
    @Query("DELETE FROM review_context WHERE profileId=:profile") protected abstract suspend fun clearContexts(profile: String)
    @Query("DELETE FROM review_operation WHERE profileId=:profile") protected abstract suspend fun clearOperations(profile: String)
    @Query("DELETE FROM review_observation WHERE profileId=:profile") protected abstract suspend fun clearObservations(profile: String)
    @Query("DELETE FROM fresh_practice_intent WHERE profileId=:profile") protected abstract suspend fun clearFresh(profile: String)
    @Query("DELETE FROM preparation_waiter WHERE profileId=:profile") protected abstract suspend fun clearWaiters(profile: String)
    @Query("DELETE FROM settings_draft WHERE profileId=:profile") protected abstract suspend fun clearDrafts(profile: String)
    @Query("UPDATE review_operation SET status='isolated' WHERE profileId=:profile AND (status='pending' OR (kind='capture_language_confirmation_v1' AND status='receipt_ready'))")
    protected abstract suspend fun isolateOperations(profile:String)
    @Query("UPDATE preparation_waiter SET cancelled=1 WHERE profileId=:profile")
    protected abstract suspend fun isolateWaiters(profile:String)
    @Query("UPDATE fresh_practice_intent SET state='isolated' WHERE profileId=:profile AND state IN ('prepared','synced')")
    protected abstract suspend fun isolateFresh(profile:String)
    @Query("UPDATE settings_draft SET previewToken=NULL,revision=revision+1 WHERE profileId=:profile AND previewToken IS NOT NULL")
    protected abstract suspend fun invalidatePreviews(profile:String)
    @Query("DELETE FROM review_context WHERE profileId=:profile AND privacyEpoch=:epoch") protected abstract suspend fun deleteEpochContexts(profile:String,epoch:Long)
    @Query("DELETE FROM review_operation WHERE profileId=:profile AND privacyEpoch=:epoch") protected abstract suspend fun deleteEpochOperations(profile:String,epoch:Long)
    @Query("DELETE FROM review_observation WHERE profileId=:profile AND privacyEpoch=:epoch") protected abstract suspend fun deleteEpochObservations(profile:String,epoch:Long)
    @Query("DELETE FROM fresh_practice_intent WHERE profileId=:profile AND privacyEpoch=:epoch") protected abstract suspend fun deleteEpochFresh(profile:String,epoch:Long)
    @Query("DELETE FROM preparation_waiter WHERE profileId=:profile AND privacyEpoch=:epoch") protected abstract suspend fun deleteEpochWaiters(profile:String,epoch:Long)
    @Query("DELETE FROM source6_record WHERE profileId=:profile AND privacyEpoch=:epoch") protected abstract suspend fun deleteEpochSource6(profile:String,epoch:Long)

    protected suspend fun requireOwner(expected: OwnerContext) {
        val value = owner()
        require(value != null && value.profileId == expected.profileId.toString() && value.privacyEpoch == expected.privacyEpoch &&
            value.revision == expected.revision && value.status in setOf("provisional", "active")) { "Owner changed." }
    }
    private fun owned(value: LocalReviewOperation, owner: OwnerContext) = value.profileId == owner.profileId.toString() && value.privacyEpoch == owner.privacyEpoch && value.ownerRevision == owner.revision

    @Query("SELECT COUNT(*) FROM review_operation WHERE profileId=:profile AND privacyEpoch=:epoch AND kind='local_offer_cancel' AND targetId=:selection AND status IN ('pending','isolated')")
    abstract suspend fun localOfferCancellations(profile:String,epoch:Long,selection:String):Int

    @Transaction open suspend fun queue(owner: OwnerContext, value: LocalReviewOperation): LocalReviewOperation {
        requireOwner(owner); require(owned(value, owner) && value.status == "pending" && value.receiptJson == null)
        val previous = operation(value.operationId)
        if (previous != null) {
            require(previous.status!="isolated") { "Operation belongs to an isolated owner." }
            require(previous.copy(status = "pending", receiptJson = null) == value) { "Immutable operation changed." }
            return previous
        }
        insertOperation(value); return value
    }
    @Query("SELECT * FROM review_operation WHERE profileId=:profile AND privacyEpoch=:epoch AND ownerRevision=:revision AND kind='capture_language_confirmation_v1' ORDER BY rowid")
    abstract suspend fun captureConfirmations(profile:String,epoch:Long,revision:Long):List<LocalReviewOperation>
    @Query("UPDATE review_operation SET status=:next,receiptJson=:receipt WHERE operationId=:id AND payloadHash=:hash AND status=:previous AND kind='capture_language_confirmation_v1'")
    protected abstract suspend fun advanceCaptureConfirmation(id:String,hash:String,previous:String,next:String,receipt:String?):Int
    /** Dedicated monotonically reducing state CAS. Immutable wrapper bytes never change. */
    @Transaction open suspend fun transitionCaptureConfirmation(owner:OwnerContext,expected:LocalReviewOperation,next:String,receipt:String?=expected.receiptJson):LocalReviewOperation {
        requireOwner(owner);require(owned(expected,owner) && expected.kind=="capture_language_confirmation_v1")
        val previous=requireNotNull(operation(expected.operationId));require(previous==expected && previous.payloadHash==LearningJson.hash(previous.payloadJson))
        require(next in setOf("pending","receipt_ready","accepted","cancelled","orphaned","isolated"))
        if(previous.receiptJson!=null)require(receipt==previous.receiptJson)
        val valid=next==previous.status || when(previous.status) {
            "pending" -> next in setOf("receipt_ready","cancelled","orphaned","isolated")
            "receipt_ready" -> next in setOf("accepted","cancelled","orphaned","isolated")
            else -> false
        }
        require(valid);if(next in setOf("receipt_ready","accepted"))require(receipt!=null)
        if(next==previous.status && receipt==previous.receiptJson)return previous
        require(advanceCaptureConfirmation(previous.operationId,previous.payloadHash,previous.status,next,receipt)==1)
        return previous.copy(status=next,receiptJson=receipt)
    }
    @Transaction open suspend fun storeCaptureConfirmationReceipt(owner:OwnerContext,id:String,hash:String,receipt:String,live:Boolean):LocalReviewOperation {
        requireOwner(owner);val current=requireNotNull(operation(id))
        require(owned(current,owner) && current.kind=="capture_language_confirmation_v1" && current.payloadHash==hash)
        val next=if(current.status=="pending"){if(live)"receipt_ready" else "cancelled"}else current.status
        return transitionCaptureConfirmation(owner,current,next,receipt)
    }
    @Transaction open suspend fun closeCaptureConfirmation(owner:OwnerContext,id:String,hash:String,orphaned:Boolean):LocalReviewOperation {
        requireOwner(owner);val current=requireNotNull(operation(id))
        require(owned(current,owner) && current.kind=="capture_language_confirmation_v1" && current.payloadHash==hash)
        return if(current.status in setOf("pending","receipt_ready"))transitionCaptureConfirmation(owner,current,if(orphaned)"orphaned" else "cancelled") else current
    }
    @Transaction open suspend fun queueBatch(owner:OwnerContext,values:List<LocalReviewOperation>):List<LocalReviewOperation> {
        requireOwner(owner);require(values.isNotEmpty() && values.map{it.operationId}.distinct().size==values.size)
        return values.map{queue(owner,it)}
    }
    @Transaction open suspend fun queueAttemptAndFence(owner:OwnerContext,attempt:LocalReviewOperation,fence:LocalReviewOperation,deviceStreamId:String,throughSequence:Long,
        confirmationAnswer:io.github.driveenglishlab.pleos.domain.AcceptedAnswer?=null) {
        requireOwner(owner)
        val existingAttempt=operation(attempt.operationId);val existingFence=operation(fence.operationId)
        if(existingAttempt==null || existingFence==null)require(throughSequence==frontier(owner.profileId.toString(),owner.privacyEpoch,deviceStreamId))
        queueBatch(owner,listOf(attempt,fence))
        if(confirmationAnswer!=null)CaptureConfirmationCommit.accept(this,confirmationAnswer,CaptureConfirmationProtocol.SOURCE6)
    }
    @Transaction open suspend fun storeSource6(owner:OwnerContext,value:LocalSource6Record):Boolean {
        requireOwner(owner);require(value.profileId==owner.profileId.toString() && value.privacyEpoch==owner.privacyEpoch && value.version>=1)
        require(LearningJson.hash(value.payloadJson)==value.payloadHash)
        val canonical=incrementalCanonical(learningJson.parseToJsonElement(value.payloadJson))
        val normalized=value.copy(payloadJson=canonical,payloadHash=LearningJson.hash(canonical))
        val old=source6Record(value.profileId,value.privacyEpoch,value.kind,value.recordId)
        if(old==null){insertSource6(normalized);return true}
        if(value.version<old.version)return false
        require(LearningJson.hash(old.payloadJson)==old.payloadHash)
        if(value.version==old.version){require(incrementalCanonical(learningJson.parseToJsonElement(old.payloadJson))==normalized.payloadJson);return false}
        require(advanceSource6(value.profileId,value.privacyEpoch,value.kind,value.recordId,old.version,value.version,normalized.payloadJson,normalized.payloadHash)==1);return true
    }
    /** Read proof may be cached only while its original acknowledged format operation still exists. */
    @Transaction open suspend fun isCurrentOwner(expected:OwnerContext):Boolean =
        try { requireOwner(expected); true } catch(_:IllegalArgumentException) { false }

    @Transaction open suspend fun storeConfirmedFormatHead(owner:OwnerContext,operationId:String,requestHash:String,
        receiptHash:String,value:LocalSource6Record):Boolean {
        requireOwner(owner)
        val original=requireNotNull(operation(operationId))
        require(owned(original,owner) && original.kind=="source6_format" && original.status=="acknowledged" &&
            original.payloadHash==requestHash && LearningJson.hash(original.payloadJson)==requestHash &&
            original.receiptJson?.let { LearningJson.hash(it) }==receiptHash &&
            value.kind=="confirmed_auto_format_head" && value.recordId==operationId && value.version==1L)
        return storeSource6(owner,value)
    }
    /** Legacy originals stay schema5 and must still exist inside this same owner transaction. */
    @Transaction open suspend fun storeConfirmedLegacyFormatHead(owner:OwnerContext,operationId:String,legacy:LearningDao,
        requestJson:String,receiptJson:String,value:LocalSource6Record):Boolean {
        requireOwner(owner)
        val request=requireNotNull(legacy.record(owner.profileId.toString(),owner.privacyEpoch,stableRecordId("request:$operationId").toString()))
        val receipt=requireNotNull(legacy.record(owner.profileId.toString(),owner.privacyEpoch,stableRecordId("receipt:$operationId").toString()))
        require(request.kind=="format_change_request" && receipt.kind=="format_change_receipt" && request.payloadSchemaVersion==5 && receipt.payloadSchemaVersion==5 &&
            request.version==1L && receipt.version==1L && request.payloadJson==requestJson && receipt.payloadJson==receiptJson &&
            value.kind=="confirmed_auto_format_head" && value.recordId==operationId && value.version==1L)
        return storeSource6(owner,value)
    }
    @Transaction open suspend fun storeContext(owner: OwnerContext, value: LocalReviewContext): Boolean {
        requireOwner(owner); require(value.profileId == owner.profileId.toString() && value.privacyEpoch == owner.privacyEpoch && value.version >= 1)
        val previous = context(value.profileId, value.privacyEpoch, value.selectionId)
        if (previous == null) { insertContext(value); return true }
        if (value.version < previous.version) return false
        if (value.version == previous.version) { require(value == previous); return false }
        require(advanceContext(value.profileId,value.privacyEpoch,value.selectionId,previous.version,value.version,value.payloadJson,value.payloadHash)==1)
        return true
    }
    @Transaction open suspend fun acknowledge(owner: OwnerContext, command: LocalReviewOperation, receipt: String, conflict: Boolean = false) {
        requireOwner(owner); require(owned(command,owner))
        val previous = operation(command.operationId) ?: error("Missing immutable operation.")
        require(previous.payloadHash == command.payloadHash)
        val status = if (conflict) "conflict" else "acknowledged"
        if (previous.status != "pending") { require(previous.status == status && previous.receiptJson == receipt); return }
        require(updateReceipt(previous.operationId,previous.payloadHash,status,receipt)==1)
    }
    @Transaction open suspend fun acknowledgeContext(owner:OwnerContext,command:LocalReviewOperation,receipt:String,context:LocalReviewContext) {
        storeContext(owner,context);acknowledge(owner,command,receipt)
    }
    @Transaction open suspend fun joinQuestionDelivery(owner:OwnerContext,id:String,hash:String){
        requireOwner(owner);val value=operation(id)?:error("Original delivery missing.")
        require(value.profileId==owner.profileId.toString() && value.privacyEpoch==owner.privacyEpoch && value.kind=="qa_delivery_unverified" && value.payloadHash==hash)
        if(value.status=="joined")return
        require(joinQuestionDeliveryRow(id,hash)==1)
    }
    @Transaction open suspend fun queueObservation(owner: OwnerContext, operation: LocalReviewOperation, value: LocalReviewObservation) {
        requireOwner(owner);require(value.profileId==owner.profileId.toString() && value.privacyEpoch==owner.privacyEpoch && value.operationId==operation.operationId && value.payloadHash==operation.payloadHash)
        val old = observation(value.observationId)
        if (old != null) { require(old == value); queue(owner,operation); return }
        require(value.sequence == frontier(value.profileId,value.privacyEpoch,value.deviceStreamId)+1)
        queue(owner,operation);insertObservation(value)
    }
    @Transaction open suspend fun prepareFresh(owner: OwnerContext, value: LocalFreshPracticeIntent): LocalFreshPracticeIntent {
        requireOwner(owner);require(value.profileId==owner.profileId.toString() && value.privacyEpoch==owner.privacyEpoch && value.ownerRevision==owner.revision && value.state=="prepared" && value.sourceLessonId!=value.newLessonId)
        val previous=freshIntent(value.operationId)
        if(previous!=null){require(previous.state!="isolated" && previous.copy(state="prepared")==value);return previous}
        insertFresh(value);return value
    }
    @Transaction open suspend fun transitionFresh(owner: OwnerContext,id:String,previous:String,next:String) {
        requireOwner(owner);val value=freshIntent(id)?:error("Missing intent.")
        require(value.profileId==owner.profileId.toString() && value.privacyEpoch==owner.privacyEpoch && value.ownerRevision==owner.revision)
        require(previous to next in setOf("prepared" to "synced","synced" to "selected","prepared" to "conflict","synced" to "conflict"))
        if(value.state==next)return
        require(advanceFresh(id,previous,next)==1)
    }
    @Transaction open suspend fun storeWaiter(owner: OwnerContext,value:LocalPreparationWaiter):LocalPreparationWaiter {
        requireOwner(owner);require(value.profileId==owner.profileId.toString() && value.privacyEpoch==owner.privacyEpoch && value.ownerRevision==owner.revision && value.requestVersion==0L && value.responseJson==null && !value.cancelled)
        val previous=waiter(value.requestId)
        if(previous!=null){require(previous.copy(requestVersion=0,responseJson=null,cancelled=false)==value);return previous}
        insertWaiter(value);return value
    }
    @Transaction open suspend fun acceptPreparation(owner: OwnerContext,id:String,version:Long,response:String):LocalPreparationWaiter {
        requireOwner(owner);val previous=waiter(id)?:error("Missing waiter.")
        require(previous.profileId==owner.profileId.toString() && previous.privacyEpoch==owner.privacyEpoch && previous.ownerRevision==owner.revision && version>=1)
        if(version<previous.requestVersion)return previous
        if(version==previous.requestVersion){require(previous.responseJson==response);return previous}
        require(advanceWaiter(id,previous.requestVersion,version,response)==1)
        return previous.copy(requestVersion=version,responseJson=response)
    }
    @Transaction open suspend fun cancelWaiter(owner:OwnerContext,id:String){requireOwner(owner);val value=waiter(id)?:return;require(value.profileId==owner.profileId.toString() && value.privacyEpoch==owner.privacyEpoch);cancelWaiterRow(id)}
    @Transaction open suspend fun openDraft(owner:OwnerContext,value:LocalSettingsDraft):LocalSettingsDraft {
        requireOwner(owner);require(value.profileId==owner.profileId.toString() && value.privacyEpoch==owner.privacyEpoch && value.ownerRevision==owner.revision && value.revision==1L)
        val previous=draft(value.profileId,value.privacyEpoch)
        if(previous!=null){require(previous.ownerRevision==owner.revision);return previous};insertDraft(value);return value
    }
    @Transaction open suspend fun changeDraft(owner:OwnerContext,previous:Long,json:String,preview:String?,saved:String?):LocalSettingsDraft {
        requireOwner(owner);val value=draft(owner.profileId.toString(),owner.privacyEpoch)?:error("Missing draft.")
        require(value.ownerRevision==owner.revision && value.revision==previous)
        require(advanceDraft(value.profileId,value.privacyEpoch,previous,previous+1,json,preview,saved)==1)
        return value.copy(revision=previous+1,draftJson=json,previewToken=preview,savedOperationId=saved)
    }
    @Transaction open suspend fun closeDraft(owner:OwnerContext,revision:Long){requireOwner(owner);require(deleteDraft(owner.profileId.toString(),owner.privacyEpoch,revision)==1)}
    /** Ordinary settings survive a learning epoch change; they are never copied to a different profile. */
    @Transaction open suspend fun ownerDraftRows(owner:OwnerContext):List<LocalSettingsDraft> {
        requireOwner(owner);return profileDrafts(owner.profileId.toString())
    }
    @Transaction open suspend fun writeOwnerDraft(owner:OwnerContext,expected:LocalSettingsDraft?,value:LocalSettingsDraft,source:LocalSettingsDraft?=null):LocalSettingsDraft {
        requireOwner(owner)
        require(value.profileId==owner.profileId.toString() && value.privacyEpoch==owner.privacyEpoch && value.ownerRevision==owner.revision)
        require(value.previewToken==null && value.savedOperationId==null && value.revision>=1)
        require(draft(value.profileId,value.privacyEpoch)==expected) { "Settings draft changed." }
        if(source!=null) {
            require(source.profileId==value.profileId && draft(source.profileId,source.privacyEpoch)==source) { "Preserved settings draft changed." }
        }
        if(expected==null)insertDraft(value) else {
            require(value.revision>expected.revision);require(replaceOwnerDraft(value)==1)
        }
        return value
    }
    @Transaction open suspend fun discardOwnerDraftRows(owner:OwnerContext,expectedRows:List<LocalSettingsDraft>,ownedEpochs:Set<Long>):Boolean {
        requireOwner(owner)
        require(profileDrafts(owner.profileId.toString())==expectedRows) { "Settings draft changed." }
        val rows=expectedRows.filter{it.privacyEpoch in ownedEpochs}
        rows.forEach{require(deleteDraft(it.profileId,it.privacyEpoch,it.revision)==1)}
        return rows.isNotEmpty()
    }
    /** Explicit local history purge; the authoritative server deletion receipt is a separate gate. */
    @Transaction open suspend fun purgeProfile(profile:String){clearContexts(profile);clearOperations(profile);clearObservations(profile);clearFresh(profile);clearWaiters(profile);clearDrafts(profile);clearSource6(profile)}
    /** Keep immutable bytes until server confirmation; isolated requests never re-enter pending. */
    @Transaction open suspend fun isolateLearning(profile:String){isolateOperations(profile);isolateWaiters(profile);isolateFresh(profile);invalidatePreviews(profile)}
    @Transaction open suspend fun clearVisibleCache(profile:String){clearContexts(profile);clearSource6(profile);invalidatePreviews(profile)}
    /** Confirmed learning deletion is epoch-exact and deliberately preserves settings drafts. */
    @Transaction open suspend fun deleteConfirmedEpoch(profile:String,epoch:Long){
        require(epoch>=0);deleteEpochContexts(profile,epoch);deleteEpochOperations(profile,epoch);deleteEpochObservations(profile,epoch)
        deleteEpochFresh(profile,epoch);deleteEpochWaiters(profile,epoch);deleteEpochSource6(profile,epoch)
    }
}
