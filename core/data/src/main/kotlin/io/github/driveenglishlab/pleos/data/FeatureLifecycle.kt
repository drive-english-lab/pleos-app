package io.github.driveenglishlab.pleos.data

import androidx.room.withTransaction
import io.github.driveenglishlab.pleos.domain.OwnerContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Shared hooks. Actual server merge/delete/logout workflows invoke these at their commit boundaries. */
@Singleton
class FeatureLifecycle @Inject constructor(private val database: LearningDatabase) {
    suspend fun isCurrent(scope: OwnerContext): Boolean {
        val current = database.learning().currentOwner()
        return current != null && current.profileId == scope.profileId.toString() && current.privacyEpoch == scope.privacyEpoch &&
            current.revision == scope.revision && current.status in setOf("provisional", "active")
    }

    /** Closes learning display/writes before networking; preserves original data until confirmed deletion. */
    suspend fun blockForHistoryDeletion(scope: OwnerContext, operationId: UUID): LocalOwner = database.withTransaction {
        require(isCurrent(scope)) { "Owner changed before deletion." }
        val fenced=database.learning().fenceOwner(scope,operationId,"history_deletion",null)
        isolateLearningForDeletion(scope.profileId)
        fenced
    }

    /** Unlike an identity switch, learning deletion leaves epoch-independent settings writable later. */
    private suspend fun isolateLearningForDeletion(profileId:UUID){
        require(database.inTransaction())
        val profile=profileId.toString()
        database.progress().isolateLearning(profile);database.aiLearning().isolateLearning(profile)
        database.review().isolateLearning(profile);database.segments().isolate(profile)
        database.progress().clearVisibleCache(profile);database.aiLearning().clearVisibleCache(profile)
        database.review().clearVisibleCache(profile);database.segments().clearVisibleState(profile)
    }

    /** Call within the same DB transaction as confirmed base-history deletion and epoch advance. */
    suspend fun clearConfirmedEpoch(profileId: UUID, epoch: Long) {
        require(database.inTransaction()) { "Deletion must share the owner/epoch transaction." }
        require(epoch >= 0)
        val profile = profileId.toString()
        database.progress().deleteEpochCache(profile, epoch)
        database.progress().deleteEpochJournal(profile, epoch)
        database.aiLearning().deleteEpochCache(profile, epoch)
        database.aiLearning().deleteEpochJournal(profile, epoch)
        database.review().deleteConfirmedEpoch(profile, epoch)
        database.segments().deleteEpochState(profile, epoch)
        database.segments().deleteEpochJournal(profile, epoch)
        database.segments().deleteEpochCompletions(profile, epoch)
    }
    /** A newer confirmed server generation also invalidates any older retained epochs.
     * Enumerate stored generations, never iterate an untrusted numeric epoch range.
     */
    suspend fun clearConfirmedThroughEpoch(profileId:UUID,lastErasedEpoch:Long){
        require(database.inTransaction());require(lastErasedEpoch>=0)
        val tables=listOf("speaking_records_cache","speaking_records_journal","ai_learning_cache","ai_learning_journal",
            "review_context","review_operation","review_observation","fresh_practice_intent","preparation_waiter","source6_record",
            "segment_state","segment_journal","segment_completion")
        val query=tables.joinToString(" UNION "){"SELECT privacyEpoch FROM $it WHERE profileId=? AND privacyEpoch<=?"}
        val args=tables.flatMap{listOf<Any>(profileId.toString(),lastErasedEpoch)}.toTypedArray()
        val epochs=linkedSetOf(lastErasedEpoch)
        database.openHelper.writableDatabase.query(query,args).use{rows->while(rows.moveToNext())epochs+=rows.getLong(0)}
        for(epoch in epochs)clearConfirmedEpoch(profileId,epoch)
    }

    /** Call before replacing the active owner. Old journals stay isolated, never copied to a new guest. */
    suspend fun isolateForOwnerTransition(profileId: UUID) {
        require(database.inTransaction()) { "Isolation must share the owner transition transaction." }
        val profile = profileId.toString()
        database.progress().isolateSettings(profile)
        database.progress().isolateLearning(profile)
        database.aiLearning().isolateSettings(profile)
        database.aiLearning().isolateLearning(profile)
        database.review().isolateLearning(profile)
        database.segments().isolate(profile)
        database.progress().clearVisibleCache(profile)
        database.aiLearning().clearVisibleCache(profile)
        database.review().clearVisibleCache(profile)
        database.segments().clearVisibleState(profile)
    }
}
