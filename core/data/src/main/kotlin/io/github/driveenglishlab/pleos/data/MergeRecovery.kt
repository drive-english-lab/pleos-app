package io.github.driveenglishlab.pleos.data

import androidx.room.withTransaction
import io.github.driveenglishlab.pleos.data.wire.*
import java.util.UUID
import kotlinx.serialization.Serializable

/** Product projection contains no proof, credential, request body or output permission. */
enum class MergeRecoveryPhase { RECONCILING, REPREPARE_REQUIRED, PREPARING, ADOPTING }
data class MergeRecoveryProjection(val operationId:UUID,val phase:MergeRecoveryPhase,val reasonCode:String?)
@Serializable internal data class ProtectedMergeReplacement(val prepareOperation:String,val completeOperation:String,
    val prepareJson:String?=null,val prepareHash:String?=null,val intent:WirePersonalMergeIntent?=null,
    val childCompleteJson:String?=null,val childCompleteHash:String?=null,val prepareDispatched:Boolean=false)

/** Existing journal rows are preserved as a linked lineage, never treated as merge receipts. */
internal class MergeRecoveryJournal(private val database:LearningDatabase) {
    private val dao get()=database.learning()
    suspend fun head():OwnerJournal?=database.openHelper.writableDatabase.query(
        "SELECT operationId,profileId,ownerRevision,kind,status,isolatedSessionReference FROM owner_journal WHERE kind='merge' AND status NOT IN ('complete','reprepared') AND profileId=(SELECT profileId FROM local_owner WHERE slot=1) AND ownerRevision+1=(SELECT revision FROM local_owner WHERE slot=1) ORDER BY rowid DESC LIMIT 1"
    ).use{row->if(!row.moveToFirst())null else OwnerJournal(row.getString(0),row.getString(1),row.getLong(2),row.getString(3),row.getString(4),row.getString(5))}
    suspend fun current(value:ProtectedMerge):Boolean {
        val owner=dao.currentOwner()?:return false
        val head=head()?:return false
        return owner.status=="transition" && owner.profileId==value.sourceProfile && owner.privacyEpoch==value.sourceEpoch &&
            owner.revision==value.sourceRevision+1 && head.operationId==value.completeOperation && head.ownerRevision==value.sourceRevision &&
            head.isolatedSessionReference=="learning-merge-target-${value.intent.intentId}"
    }
    suspend fun mark(value:ProtectedMerge,status:String):Boolean=database.withTransaction {
        if(!current(value))return@withTransaction false
        dao.updateOwnerJournal(value.completeOperation,status);true
    }
    suspend fun replace(previous:ProtectedMerge,next:ProtectedMerge):Boolean=database.withTransaction {
        if(!current(previous) || next.predecessorOperation!=previous.completeOperation || previous.receipt!=null)return@withTransaction false
        require(next.sourceProfile==previous.sourceProfile && next.sourceEpoch==previous.sourceEpoch && next.sourceRevision==previous.sourceRevision &&
            next.sourceSubject==previous.sourceSubject && next.targetSubject==previous.targetSubject && next.targetInstallation==previous.targetInstallation)
        val replacement=requireNotNull(previous.replacement)
        require(replacement.completeOperation==next.completeOperation && replacement.intent==next.intent)
        dao.insertJournal(OwnerJournal(next.completeOperation,next.sourceProfile,next.sourceRevision,"merge","pending","learning-merge-target-${next.intent.intentId}"))
        dao.updateOwnerJournal(previous.completeOperation,"reprepared")
        true
    }
    /** Fencing hides pending rows from ordinary workers. Reprepare must still see those rows. */
    suspend fun sourceSettled(value:ProtectedMerge):Boolean {
        if(!current(value) || dao.pendingLocalCommand(value.sourceProfile)!=null || dao.pendingProfileSettings(value.sourceProfile)!=null)return false
        val profile=value.sourceProfile;val epoch=value.sourceEpoch
        val checks=listOf(
            "SELECT 1 FROM learning_outbox WHERE profileId=? AND privacyEpoch=? LIMIT 1",
            "SELECT 1 FROM daily_preference_journal WHERE profileId=? AND state IN ('pending','sending','conflict') LIMIT 1",
            "SELECT 1 FROM learning_preference_journal WHERE profileId=? AND state IN ('pending','sending','conflict') LIMIT 1",
            "SELECT 1 FROM speaking_records_journal WHERE profileId=? AND privacyEpoch=? AND state!='acknowledged' LIMIT 1",
            "SELECT 1 FROM ai_learning_journal WHERE profileId=? AND privacyEpoch=? AND state IN ('pending','sending','conflict') LIMIT 1",
            "SELECT 1 FROM review_operation WHERE profileId=? AND privacyEpoch=? AND status IN ('pending','isolated') AND kind!='legacy_attempt_admission' LIMIT 1",
            "SELECT 1 FROM segment_journal WHERE profileId=? AND privacyEpoch=? AND state='pending' LIMIT 1"
        )
        return checks.none{sql->database.openHelper.writableDatabase.query(sql,if(sql.contains("privacyEpoch"))arrayOf<Any>(profile,epoch) else arrayOf<Any>(profile)).use{it.moveToFirst()}}
    }
}
