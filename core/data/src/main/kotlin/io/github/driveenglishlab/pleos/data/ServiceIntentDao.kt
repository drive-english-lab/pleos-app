package io.github.driveenglishlab.pleos.data

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*
import io.github.driveenglishlab.pleos.domain.OwnerContext

/** Original local input binding and admission are immutable; only outcome/state delivery advances. */
@Entity(tableName = "playback_request_intent", indices = [Index(value = ["profileId", "privacyEpoch", "isolated"])])
data class LocalPlaybackRequest(@PrimaryKey val operationId: String, val profileId: String, val privacyEpoch: Long,
    val ownerRevision: Long, val contextToken: String, val processToken: String, val bindingJson: String,
    val requestHash: String, val state: String, val polls: Int, val admissionJson: String?, val outcomeJson: String?,
    val outcomeVersion: Long, val activationClaimId: String?, val isolated: Boolean)

/** Passive public read snapshots. They never stand in for current rights or playback permission. */
@Entity(tableName = "catalog_view_page", primaryKeys = ["profileId", "privacyEpoch", "view", "filterKey", "snapshotId", "cursorKey"])
data class LocalCatalogViewPage(val profileId: String, val privacyEpoch: Long, val ownerRevision: Long,
    val view: String, val filterKey: String, val snapshotId: String, val cursorKey: String,
    val localSequence: Long, val payloadJson: String, val payloadHash: String)

@Dao
abstract class ServiceIntentDao {
    @Query("SELECT * FROM local_owner WHERE slot=1") protected abstract suspend fun owner(): LocalOwner?
    @Query("SELECT * FROM playback_request_intent WHERE operationId=:id") abstract suspend fun intent(id: String): LocalPlaybackRequest?
    @Query("SELECT * FROM playback_request_intent WHERE profileId=:profile AND privacyEpoch=:epoch AND ownerRevision=:revision AND isolated=0 AND state IN ('active','expired') ORDER BY rowid DESC")
    abstract suspend fun restorableRequests(profile:String,epoch:Long,revision:Long):List<LocalPlaybackRequest>
    @Query("SELECT * FROM playback_request_intent WHERE profileId=:profile AND privacyEpoch=:epoch AND ownerRevision=:revision AND isolated=0 AND admissionJson IS NOT NULL AND outcomeJson IS NOT NULL")
    abstract suspend fun confirmedOutcomes(profile: String, epoch: Long, revision: Long): List<LocalPlaybackRequest>
    @Insert(onConflict = OnConflictStrategy.ABORT) protected abstract suspend fun insertIntent(value: LocalPlaybackRequest)
    @Query("SELECT * FROM review_operation WHERE operationId=:id") protected abstract suspend fun localInput(id:String):LocalReviewOperation?
    @Query("SELECT * FROM review_operation WHERE profileId=:profile AND privacyEpoch=:epoch AND ownerRevision=:revision AND kind='local_review3_parent_input' AND targetId=:parent AND status='local_complete'")
    protected abstract suspend fun parentInputs(profile:String,epoch:Long,revision:Long,parent:String):List<LocalReviewOperation>
    @Query("SELECT * FROM review_operation WHERE profileId=:profile AND privacyEpoch=:epoch AND ownerRevision=:revision AND kind='local_review3_parent_input' AND status='local_complete' ORDER BY rowid DESC")
    protected abstract suspend fun allParentInputs(profile:String,epoch:Long,revision:Long):List<LocalReviewOperation>
    @Insert(onConflict=OnConflictStrategy.ABORT) protected abstract suspend fun insertLocalInput(value:LocalReviewOperation)
    @Query("UPDATE playback_request_intent SET state=:state,polls=:polls,admissionJson=:admission,outcomeJson=:outcome,outcomeVersion=:version,activationClaimId=:claim WHERE operationId=:id AND state=:expectedState AND polls=:expectedPolls AND outcomeVersion=:expectedVersion AND isolated=0")
    protected abstract suspend fun updateIntent(id: String, expectedState: String, expectedPolls: Int, expectedVersion: Long,
        state: String, polls: Int, admission: String?, outcome: String?, version: Long, claim: String?): Int
    @Query("SELECT * FROM catalog_view_page WHERE profileId=:profile AND privacyEpoch=:epoch AND view=:view AND filterKey=:filter ORDER BY localSequence DESC LIMIT 1")
    abstract suspend fun latestCatalog(profile: String, epoch: Long, view: String, filter: String): LocalCatalogViewPage?
    @Query("SELECT * FROM catalog_view_page WHERE profileId=:profile AND privacyEpoch=:epoch AND view=:view AND filterKey=:filter AND snapshotId=:snapshot ORDER BY localSequence")
    abstract suspend fun catalogPages(profile: String, epoch: Long, view: String, filter: String, snapshot: String): List<LocalCatalogViewPage>
    @Query("SELECT * FROM catalog_view_page WHERE profileId=:profile AND privacyEpoch=:epoch AND view=:view AND filterKey=:filter AND snapshotId=:snapshot AND cursorKey=:cursor")
    protected abstract suspend fun catalogPage(profile: String, epoch: Long, view: String, filter: String, snapshot: String, cursor: String): LocalCatalogViewPage?
    @Query("SELECT COALESCE(MAX(localSequence),0) FROM catalog_view_page WHERE profileId=:profile AND privacyEpoch=:epoch AND view=:view AND filterKey=:filter")
    abstract suspend fun catalogSequence(profile: String, epoch: Long, view: String, filter: String): Long
    @Insert(onConflict = OnConflictStrategy.ABORT) protected abstract suspend fun insertCatalog(value: LocalCatalogViewPage)
    @Query("UPDATE playback_request_intent SET isolated=1 WHERE profileId=:profile") abstract suspend fun isolate(profile: String)
    @Query("DELETE FROM catalog_view_page WHERE profileId=:profile") abstract suspend fun clearVisibleCatalog(profile: String)
    @Query("DELETE FROM playback_request_intent WHERE profileId=:profile AND privacyEpoch=:epoch") abstract suspend fun deleteEpochIntents(profile: String, epoch: Long)
    @Query("DELETE FROM catalog_view_page WHERE profileId=:profile AND privacyEpoch=:epoch") abstract suspend fun deleteEpochCatalog(profile: String, epoch: Long)

    private suspend fun authorize(scope: OwnerContext) {
        val current = owner()
        require(current != null && current.profileId == scope.profileId.toString() && current.privacyEpoch == scope.privacyEpoch &&
            current.revision == scope.revision && current.status in setOf("active", "provisional")) { "Owner changed." }
    }
    private fun belongsTo(scope: OwnerContext, row: LocalPlaybackRequest) = row.profileId == scope.profileId.toString() && row.privacyEpoch == scope.privacyEpoch && row.ownerRevision == scope.revision
    @Transaction open suspend fun register(scope: OwnerContext, value: LocalPlaybackRequest): LocalPlaybackRequest {
        authorize(scope)
        require(belongsTo(scope, value) && value.state == "active" && value.polls == 0 && value.admissionJson == null && value.outcomeJson == null &&
            value.outcomeVersion == 0L && value.activationClaimId == null && !value.isolated)
        val old = intent(value.operationId)
        if (old != null) {
            require(!old.isolated && old.copy(state = "active", polls = 0, admissionJson = null, outcomeJson = null, outcomeVersion = 0,
                activationClaimId = null) == value) { "Original input binding cannot change." }
            return old
        }
        insertIntent(value); return value
    }
    @Transaction open suspend fun advance(scope: OwnerContext, previous: LocalPlaybackRequest, next: LocalPlaybackRequest): Boolean {
        authorize(scope); require(belongsTo(scope, previous) && belongsTo(scope, next) && !previous.isolated && !next.isolated)
        require(previous.copy(state = next.state, polls = next.polls, admissionJson = next.admissionJson, outcomeJson = next.outcomeJson,
            outcomeVersion = next.outcomeVersion, activationClaimId = next.activationClaimId) == next) { "Immutable intent binding changed." }
        require(next.state in setOf("active", "expired", "cancelled", "applied") && (previous.state == next.state || previous.state == "active" ||
            previous.state=="expired" && next.state=="cancelled"))
        require(next.polls >= previous.polls && next.outcomeVersion >= previous.outcomeVersion)
        require(previous.admissionJson == null || previous.admissionJson == next.admissionJson)
        if (previous.outcomeVersion == next.outcomeVersion && previous.outcomeJson != next.outcomeJson) {
            val beforeRoot = Json.parseToJsonElement(requireNotNull(previous.outcomeJson)).jsonObject
            val afterRoot = Json.parseToJsonElement(requireNotNull(next.outcomeJson)).jsonObject
            require((beforeRoot["local_review_contract_version"]==JsonPrimitive(3))==(afterRoot["local_review_contract_version"]==JsonPrimitive(3)))
            val before=serviceIntentOutcome(beforeRoot);val after=serviceIntentOutcome(afterRoot)
            require(before.filterKeys { it != "observed_at" } == after.filterKeys { it != "observed_at" })
            require(Instant.parse(after.getValue("observed_at").jsonPrimitive.content) >= Instant.parse(before.getValue("observed_at").jsonPrimitive.content))
            if(beforeRoot["local_review_contract_version"]==JsonPrimitive(3)) {
                val oldTarget=beforeRoot.getValue("pending_target") as? JsonObject
                val newTarget=afterRoot.getValue("pending_target") as? JsonObject
                require((oldTarget==null)==(newTarget==null))
                if(oldTarget!=null && newTarget!=null)require(listOf("article_id","source_snapshot_id","source_hash").all{oldTarget[it]==newTarget[it]})
            }
        }
        require((next.state == "applied") == (next.activationClaimId != null))
        val actual = intent(previous.operationId) ?: return false
        if (actual != previous) return false
        return updateIntent(previous.operationId, previous.state, previous.polls, previous.outcomeVersion, next.state, next.polls,
            next.admissionJson, next.outcomeJson, next.outcomeVersion, next.activationClaimId) == 1
    }
    /** A local explicit input revokes delivery grants immediately, without sending server cancel.
     * The marker is local_complete, not a server acknowledgement or a network outbox item.
     */
    @Transaction open suspend fun recordPendingOriginalInput(scope:OwnerContext,parent:LocalPlaybackRequest,childId:String,anchorJson:String):Boolean {
        authorize(scope);require(belongsTo(scope,parent) && !parent.isolated && childId!=parent.operationId)
        if(intent(parent.operationId)!=parent || parent.state=="applied")return false
        if(localInput(pendingChoiceStopId(parent.operationId))!=null)return false
        val prior=parentInputs(parent.profileId,parent.privacyEpoch,parent.ownerRevision,parent.operationId)
        if(parent.state=="cancelled" && prior.isEmpty())return false // A real explicit stop is not a parent anchor.
        val markerId=pendingOriginalMarkerId(childId)
        val marker=LocalReviewOperation(markerId,parent.profileId,parent.privacyEpoch,parent.ownerRevision,
            "local_review3_parent_input",parent.operationId,anchorJson,LearningJson.hash(anchorJson),"local_complete",null)
        val existing=localInput(markerId)
        if(existing!=null)require(existing==marker) else insertLocalInput(marker)
        for(value in prior) {
            val priorChild=Json.parseToJsonElement(value.payloadJson).jsonObject.getValue("child_operation_id").jsonPrimitive.content
            if(priorChild!=childId)intent(priorChild)?.takeIf{belongsTo(scope,it) && !it.isolated && it.state in setOf("active","expired")}?.let {
                require(advance(scope,it,it.copy(state="cancelled")))
            }
        }
        if(parent.state in setOf("active","expired"))require(advance(scope,parent,parent.copy(state="cancelled")))
        return true
    }
    @Transaction open suspend fun hasPendingOriginalInput(scope:OwnerContext,parentId:String,childId:String):Boolean {
        return pendingOriginalInput(scope,parentId,childId)!=null
    }
    @Transaction open suspend fun pendingOriginalInput(scope:OwnerContext,parentId:String,childId:String):LocalReviewOperation? {
        authorize(scope)
        val value=localInput(pendingOriginalMarkerId(childId))?:return null
        require(value.profileId==scope.profileId.toString() && value.privacyEpoch==scope.privacyEpoch && value.ownerRevision==scope.revision &&
            value.kind=="local_review3_parent_input" && value.targetId==parentId && value.status=="local_complete" &&
            LearningJson.hash(value.payloadJson)==value.payloadHash)
        return value
    }
    @Transaction open suspend fun pendingChoiceInputs(scope:OwnerContext):List<LocalReviewOperation> {
        authorize(scope);return allParentInputs(scope.profileId.toString(),scope.privacyEpoch,scope.revision)
    }
    @Transaction open suspend fun pendingChoiceStopped(scope:OwnerContext,parentId:String):Boolean {
        authorize(scope);return localInput(pendingChoiceStopId(parentId))!=null
    }
    @Transaction open suspend fun stopPendingChoice(scope:OwnerContext,value:LocalPlaybackRequest) {
        authorize(scope);require(belongsTo(scope,value) && !value.isolated)
        val binding=Json.parseToJsonElement(value.bindingJson).jsonObject
        val parentId=binding["pending_original_parent_operation_id"]?.jsonPrimitive?.content?:value.operationId
        val id=pendingChoiceStopId(parentId)
        val payload=buildJsonObject{put("parent_operation_id",parentId)}.toString()
        val existing=localInput(id)
        if(existing==null)insertLocalInput(LocalReviewOperation(id,value.profileId,value.privacyEpoch,value.ownerRevision,
            "local_review3_pending_stop",parentId,payload,LearningJson.hash(payload),"local_complete",null))
        else require(existing.profileId==value.profileId && existing.privacyEpoch==value.privacyEpoch && existing.ownerRevision==value.ownerRevision)
    }
    @Transaction open suspend fun pendingChoiceNotAdmitted(scope:OwnerContext,value:LocalPlaybackRequest):Boolean {
        authorize(scope);require(belongsTo(scope,value) && !value.isolated)
        val saved=localInput(pendingChoiceNotAdmittedId(value.operationId))?:return false
        return saved.profileId==value.profileId && saved.privacyEpoch==value.privacyEpoch && saved.ownerRevision==value.ownerRevision &&
            saved.payloadHash==LearningJson.hash(saved.payloadJson) && Json.parseToJsonElement(saved.payloadJson).jsonObject["request_hash"]==JsonPrimitive(value.requestHash)
    }
    /** Written only for the authenticated observation-only endpoint's exact no-admission result. */
    @Transaction open suspend fun recordPendingChoiceNotAdmitted(scope:OwnerContext,value:LocalPlaybackRequest) {
        authorize(scope);require(belongsTo(scope,value) && !value.isolated && value.admissionJson==null && value.outcomeJson==null)
        val id=pendingChoiceNotAdmittedId(value.operationId)
        val payload=buildJsonObject{put("operation_id",value.operationId);put("request_hash",value.requestHash)}.toString()
        val previous=localInput(id)
        if(previous==null)insertLocalInput(LocalReviewOperation(id,value.profileId,value.privacyEpoch,value.ownerRevision,"local_review3_not_admitted",value.operationId,
            payload,LearningJson.hash(payload),"local_complete",null))
        else require(previous.profileId==value.profileId && previous.privacyEpoch==value.privacyEpoch && previous.ownerRevision==value.ownerRevision && previous.payloadJson==payload)
    }
    @Transaction open suspend fun saveCatalog(scope: OwnerContext, value: LocalCatalogViewPage) {
        authorize(scope)
        require(value.profileId == scope.profileId.toString() && value.privacyEpoch == scope.privacyEpoch && value.ownerRevision == scope.revision &&
            value.localSequence >= 1 && LearningJson.hash(value.payloadJson) == value.payloadHash)
        val old = catalogPage(value.profileId, value.privacyEpoch, value.view, value.filterKey, value.snapshotId, value.cursorKey)
        if (old != null) { require(old.payloadJson == value.payloadJson && old.payloadHash == value.payloadHash); return }
        require(value.localSequence == catalogSequence(value.profileId, value.privacyEpoch, value.view, value.filterKey) + 1)
        insertCatalog(value)
    }
}

private fun pendingOriginalMarkerId(childId:String)=UUID.nameUUIDFromBytes("review3-parent-input:$childId".toByteArray(Charsets.UTF_8)).toString()
private fun pendingChoiceStopId(parentId:String)=UUID.nameUUIDFromBytes("review3-pending-stop:$parentId".toByteArray(Charsets.UTF_8)).toString()
private fun pendingChoiceNotAdmittedId(childId:String)=UUID.nameUUIDFromBytes("review3-not-admitted:$childId".toByteArray(Charsets.UTF_8)).toString()
internal fun serviceIntentOutcome(value:JsonObject):JsonObject {
    if(value["local_review_contract_version"]!=JsonPrimitive(3))return value
    require(value.keys==setOf("local_review_contract_version","outcome_projection","pending_target"))
    require(value.getValue("pending_target")==JsonNull || value.getValue("pending_target") is JsonObject)
    return value.getValue("outcome_projection").jsonObject
}

/** Root's preserving Room4→5 migration calls this once; existing streams/records are untouched. */
fun createServiceIntentTables(database: SupportSQLiteDatabase) {
    database.execSQL("CREATE TABLE IF NOT EXISTS playback_request_intent (operationId TEXT NOT NULL PRIMARY KEY, profileId TEXT NOT NULL, privacyEpoch INTEGER NOT NULL, ownerRevision INTEGER NOT NULL, contextToken TEXT NOT NULL, processToken TEXT NOT NULL, bindingJson TEXT NOT NULL, requestHash TEXT NOT NULL, state TEXT NOT NULL, polls INTEGER NOT NULL, admissionJson TEXT, outcomeJson TEXT, outcomeVersion INTEGER NOT NULL, activationClaimId TEXT, isolated INTEGER NOT NULL)")
    database.execSQL("CREATE INDEX IF NOT EXISTS index_playback_request_intent_profileId_privacyEpoch_isolated ON playback_request_intent(profileId, privacyEpoch, isolated)")
    database.execSQL("CREATE TABLE IF NOT EXISTS catalog_view_page (profileId TEXT NOT NULL, privacyEpoch INTEGER NOT NULL, ownerRevision INTEGER NOT NULL, view TEXT NOT NULL, filterKey TEXT NOT NULL, snapshotId TEXT NOT NULL, cursorKey TEXT NOT NULL, localSequence INTEGER NOT NULL, payloadJson TEXT NOT NULL, payloadHash TEXT NOT NULL, PRIMARY KEY(profileId,privacyEpoch,view,filterKey,snapshotId,cursorKey))")
}
