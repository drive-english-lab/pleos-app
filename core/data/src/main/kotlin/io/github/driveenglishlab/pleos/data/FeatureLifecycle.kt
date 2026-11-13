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
        val owner = requireNotNull(database.learning().currentOwner())
        database.learning().insertJournal(OwnerJournal(operationId.toString(), owner.profileId, owner.revision,
            "feature_history_deletion", "pending", null))
        database.learning().isolate(owner.profileId)
        database.progress().isolateLearning(owner.profileId)
        database.aiLearning().isolateLearning(owner.profileId)
        database.progress().clearVisibleCache(owner.profileId)
        database.aiLearning().clearVisibleCache(owner.profileId)
        owner.copy(revision = owner.revision + 1, status = "history_deletion_pending").also { database.learning().putOwner(it) }
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
    }

    /** Call before replacing the active owner. Old journals stay isolated, never copied to a new guest. */
    suspend fun isolateForOwnerTransition(profileId: UUID) {
        require(database.inTransaction()) { "Isolation must share the owner transition transaction." }
        val profile = profileId.toString()
        database.progress().isolateSettings(profile)
        database.progress().isolateLearning(profile)
        database.aiLearning().isolateSettings(profile)
        database.aiLearning().isolateLearning(profile)
        database.progress().clearVisibleCache(profile)
        database.aiLearning().clearVisibleCache(profile)
    }
}
