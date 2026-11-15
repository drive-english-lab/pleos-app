package io.github.driveenglishlab.pleos.data

import androidx.room.*
import io.github.driveenglishlab.pleos.domain.OwnerContext
import kotlinx.serialization.json.*

/** Epoch-independent setting snapshot. Not an output or synchronization grant. */
@Entity(tableName = "learning_preference_cache")
data class LocalAiLearningPreference(@PrimaryKey val profileId: String, val version: Long, val payloadJson: String)

/** Original intent and wire bytes are immutable once this operation is recorded. */
@Entity(tableName = "learning_preference_journal", indices = [Index(value = ["profileId", "isolated", "localSequence"])])
data class LocalAiLearningPreferenceJournal(@PrimaryKey val operationId: String, val profileId: String,
    val ownerRevision: Long, val localSequence: Long, val intentJson: String,
    val payloadJson: String, val payloadHash: String, val state: String, val isolated: Boolean)

@Entity(tableName = "ai_learning_cache", primaryKeys = ["profileId", "privacyEpoch", "kind", "cacheKey"])
data class LocalAiLearningCache(val profileId: String, val privacyEpoch: Long, val kind: String,
    val cacheKey: String, val revision: Long, val payloadJson: String)

@Entity(tableName = "ai_learning_journal", indices = [Index(value = ["profileId", "privacyEpoch", "isolated"])])
data class LocalAiLearningJournal(@PrimaryKey val operationId: String, val profileId: String, val privacyEpoch: Long,
    val ownerRevision: Long, val kind: String, val payloadJson: String, val payloadHash: String,
    val state: String, val isolated: Boolean)

@Dao
abstract class AiLearningDao {
    @Query("SELECT * FROM local_owner WHERE slot = 1")
    protected abstract suspend fun owner(): LocalOwner?
    @Query("SELECT * FROM learning_preference_cache WHERE profileId = :profile")
    abstract suspend fun preference(profile: String): LocalAiLearningPreference?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putPreference(value: LocalAiLearningPreference)
    @Query("SELECT * FROM learning_preference_journal WHERE profileId = :profile AND isolated = 0 ORDER BY localSequence DESC LIMIT 1")
    abstract suspend fun latestPreference(profile: String): LocalAiLearningPreferenceJournal?
    @Query("SELECT * FROM learning_preference_journal WHERE profileId = :profile AND isolated = 0 AND state IN ('pending', 'sending', 'conflict') ORDER BY localSequence")
    abstract suspend fun unsettledPreferences(profile: String): List<LocalAiLearningPreferenceJournal>
    @Query("UPDATE learning_preference_journal SET state = 'superseded' WHERE profileId = :profile AND isolated = 0 AND state IN ('pending', 'sending', 'conflict')")
    protected abstract suspend fun supersedePreferenceIntents(profile: String)
    @Query("SELECT * FROM learning_preference_journal WHERE operationId = :id")
    abstract suspend fun preferenceOperation(id: String): LocalAiLearningPreferenceJournal?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertPreferenceOperation(value: LocalAiLearningPreferenceJournal)
    @Query("SELECT * FROM learning_preference_journal WHERE profileId = :profile AND isolated = 0 AND state IN ('pending', 'sending') ORDER BY localSequence LIMIT 50")
    abstract suspend fun pendingPreferences(profile: String): List<LocalAiLearningPreferenceJournal>
    @Query("UPDATE learning_preference_journal SET state = :next WHERE operationId = :id AND state = :previous AND isolated = 0")
    protected abstract suspend fun updatePreferenceState(id: String, previous: String, next: String): Int
    @Query("SELECT * FROM ai_learning_cache WHERE profileId = :profile AND privacyEpoch = :epoch AND kind = :kind AND cacheKey = :key")
    abstract suspend fun cache(profile: String, epoch: Long, kind: String, key: String): LocalAiLearningCache?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putCache(value: LocalAiLearningCache)
    @Query("SELECT * FROM ai_learning_journal WHERE profileId = :profile AND privacyEpoch = :epoch AND isolated = 0 AND state IN ('pending', 'sending', 'conflict') ORDER BY rowid")
    abstract suspend fun unsettled(profile: String, epoch: Long): List<LocalAiLearningJournal>
    @Query("SELECT * FROM ai_learning_journal WHERE profileId = :profile AND privacyEpoch = :epoch AND kind = 'assessment_review_request' AND isolated = 0 ORDER BY rowid")
    abstract suspend fun assessmentRequests(profile: String, epoch: Long): List<LocalAiLearningJournal>
    @Query("SELECT * FROM ai_learning_journal WHERE operationId = :id")
    abstract suspend fun operation(id: String): LocalAiLearningJournal?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertOperation(value: LocalAiLearningJournal)
    @Query("SELECT * FROM ai_learning_journal WHERE profileId = :profile AND privacyEpoch = :epoch AND isolated = 0 AND state IN ('pending', 'sending') ORDER BY rowid LIMIT 50")
    abstract suspend fun pending(profile: String, epoch: Long): List<LocalAiLearningJournal>
    @Query("UPDATE ai_learning_journal SET state = :next WHERE operationId = :id AND state = :previous AND isolated = 0")
    protected abstract suspend fun updateState(id: String, previous: String, next: String): Int
    @Query("DELETE FROM ai_learning_cache WHERE profileId = :profile AND privacyEpoch = :epoch")
    abstract suspend fun deleteEpochCache(profile: String, epoch: Long)
    @Query("DELETE FROM ai_learning_journal WHERE profileId = :profile AND privacyEpoch = :epoch")
    abstract suspend fun deleteEpochJournal(profile: String, epoch: Long)
    @Query("UPDATE learning_preference_journal SET isolated = 1 WHERE profileId = :profile")
    abstract suspend fun isolateSettings(profile: String)
    @Query("UPDATE ai_learning_journal SET isolated = 1 WHERE profileId = :profile")
    abstract suspend fun isolateLearning(profile: String)
    @Query("DELETE FROM ai_learning_cache WHERE profileId = :profile")
    abstract suspend fun clearVisibleCache(profile: String)

    private suspend fun authorize(scope: OwnerContext) {
        val current = owner()
        require(current != null && current.profileId == scope.profileId.toString() && current.privacyEpoch == scope.privacyEpoch &&
            current.revision == scope.revision && current.status in setOf("provisional", "active")) { "Owner changed." }
    }

    @Transaction
    open suspend fun savePreference(scope: OwnerContext, value: LocalAiLearningPreference): Boolean {
        authorize(scope); require(value.profileId == scope.profileId.toString() && value.version >= 0)
        val previous = preference(value.profileId)
        if (previous != null) {
            if (value.version < previous.version) return false
            if (value.version == previous.version) { require(value == previous); return false }
        }
        putPreference(value); return true
    }

    @Transaction
    open suspend fun queuePreference(scope: OwnerContext, value: LocalAiLearningPreferenceJournal): Boolean {
        authorize(scope)
        require(value.profileId == scope.profileId.toString() && value.ownerRevision == scope.revision &&
            value.localSequence > 0 && value.state == "pending" && !value.isolated && LearningJson.hash(value.payloadJson) == value.payloadHash)
        val old = preferenceOperation(value.operationId)
        if (old != null) { require(old == value); return false }
        insertPreferenceOperation(value); return true
    }

    /** Explicit replacement is a new user choice, never a rewrite/rebase of old wire bytes. */
    @Transaction
    open suspend fun replacePreferenceIntent(scope: OwnerContext, value: LocalAiLearningPreferenceJournal): Boolean {
        authorize(scope)
        require(value.profileId == scope.profileId.toString() && value.ownerRevision == scope.revision && value.localSequence > 0 &&
            value.state == "pending" && !value.isolated && LearningJson.hash(value.payloadJson) == value.payloadHash)
        val old = preferenceOperation(value.operationId)
        if (old != null) { require(old == value); return false }
        supersedePreferenceIntents(value.profileId)
        insertPreferenceOperation(value)
        return true
    }

    /** Frozen identities stay fixed while a current rights read may mark them withdrawn. */
    @Transaction
    open suspend fun saveSelectionMaterialProjection(scope: OwnerContext, value: LocalAiLearningCache): Boolean {
        authorize(scope)
        require(value.profileId == scope.profileId.toString() && value.privacyEpoch == scope.privacyEpoch &&
            value.kind == "selection_material" && value.revision >= 1)
        val previous = cache(value.profileId, value.privacyEpoch, value.kind, value.cacheKey)
        if (previous != null) {
            if (previous.revision > value.revision) return false
            val old = featureJson.parseToJsonElement(previous.payloadJson).jsonObject
            val new = featureJson.parseToJsonElement(value.payloadJson).jsonObject
            for (key in listOf("profile_context", "selection_id", "preference_version_at_selection", "selected_band")) require(old[key] == new[key])
            for (key in listOf("material_id", "band", "content_ref", "practice_content_ref")) require(old.getValue("material").jsonObject[key] == new.getValue("material").jsonObject[key])
            if (previous == value) return false
        }
        putCache(value)
        return true
    }

    /** GET is a mutable projection of a fixed evidence revision: pending may become ready.
     * The repository serializes GET/actions and checks result version/disposition before this.
     */
    @Transaction
    open suspend fun saveRecommendationProjection(scope: OwnerContext, value: LocalAiLearningCache): Boolean {
        authorize(scope)
        require(value.profileId == scope.profileId.toString() && value.privacyEpoch == scope.privacyEpoch &&
            value.kind == "recommendation" && value.cacheKey == "current" && value.revision >= 0)
        val previous = cache(value.profileId, value.privacyEpoch, value.kind, value.cacheKey)
        if (previous != null && previous.revision > value.revision) return false
        if (previous == value) return false
        putCache(value)
        return true
    }

    /** Mutable review progress can advance while the effective assessment revision stays fixed. */
    @Transaction
    open suspend fun saveAssessmentProjection(scope: OwnerContext, value: LocalAiLearningCache): Boolean {
        authorize(scope)
        require(value.profileId == scope.profileId.toString() && value.privacyEpoch == scope.privacyEpoch &&
            value.kind == "assessment_review" && value.revision >= 1)
        val next = learningJson.parseToJsonElement(value.payloadJson).jsonObject
        val nextEffective = next.getValue("effective_assessment").jsonObject
        require(next.getValue("profile_context").jsonObject.let {
            it["profile_id"] == JsonPrimitive(value.profileId) && it["privacy_epoch"] == JsonPrimitive(value.privacyEpoch)
        })
        require(nextEffective["attempt_id"] == JsonPrimitive(value.cacheKey) &&
            nextEffective.getValue("projection_revision").jsonPrimitive.long == value.revision)
        val previous = cache(value.profileId, value.privacyEpoch, value.kind, value.cacheKey)
        if (previous != null) {
            if (value.revision < previous.revision) return false
            val old = learningJson.parseToJsonElement(previous.payloadJson).jsonObject
            val oldEffective = old.getValue("effective_assessment").jsonObject
            for (key in listOf("lesson_id", "attempt_id", "original_feedback_hash")) require(oldEffective[key] == nextEffective[key])
            val oldAssessment = oldEffective.getValue("assessment_revision").jsonPrimitive.long
            val newAssessment = nextEffective.getValue("assessment_revision").jsonPrimitive.long
            require(newAssessment >= oldAssessment)
            if (newAssessment == oldAssessment) require(oldEffective["basis_assessment_hash"] == nextEffective["basis_assessment_hash"])
            if (value.revision == previous.revision) require(oldEffective == nextEffective)
            val oldReview = old["review"]?.takeUnless { it == JsonNull }?.jsonObject
            val newReview = next["review"]?.takeUnless { it == JsonNull }?.jsonObject
            if (oldReview != null && newReview != null && oldReview["review_id"] == newReview["review_id"]) {
                for (key in listOf("lesson_id", "attempt_id", "source_ref", "target_assessment_revision", "target_assessment_hash", "basis_hash", "requested_at"))
                    require(oldReview[key] == newReview[key])
                val oldVersion = oldReview.getValue("version").jsonPrimitive.long
                val newVersion = newReview.getValue("version").jsonPrimitive.long
                if (newVersion < oldVersion) return false
                if (newVersion == oldVersion) require(oldReview == newReview)
            }
            if (old == next) return false
        }
        putCache(value)
        return true
    }

    /** Apply's preference and recommendation receipt become visible in one local transaction. */
    @Transaction
    open suspend fun acknowledgeRecommendationAction(scope: OwnerContext, id: String,
        result: LocalAiLearningCache, receipt: LocalAiLearningCache, preference: LocalAiLearningPreference): Boolean {
        authorize(scope)
        val row = requireNotNull(operation(id))
        require(row.profileId == scope.profileId.toString() && row.privacyEpoch == scope.privacyEpoch && row.ownerRevision == scope.revision &&
            row.kind == "recommendation_action" && !row.isolated && row.state == "sending")
        savePreference(scope, preference)
        saveRecommendationProjection(scope, result)
        saveCache(scope, receipt)
        return updateState(id, "sending", "acknowledged") == 1
    }

    @Transaction
    open suspend fun saveCache(scope: OwnerContext, value: LocalAiLearningCache): Boolean {
        authorize(scope); require(value.profileId == scope.profileId.toString() && value.privacyEpoch == scope.privacyEpoch && value.revision >= 0)
        val previous = cache(value.profileId, value.privacyEpoch, value.kind, value.cacheKey)
        if (previous != null) {
            if (value.revision < previous.revision) return false
            if (value.revision == previous.revision) { require(value == previous); return false }
        }
        putCache(value); return true
    }

    @Transaction
    open suspend fun queue(scope: OwnerContext, value: LocalAiLearningJournal): Boolean {
        authorize(scope)
        require(value.profileId == scope.profileId.toString() && value.privacyEpoch == scope.privacyEpoch &&
            value.ownerRevision == scope.revision && value.state == "pending" && !value.isolated && LearningJson.hash(value.payloadJson) == value.payloadHash)
        val old = operation(value.operationId)
        if (old != null) { require(old == value); return false }
        insertOperation(value); return true
    }

    @Transaction
    open suspend fun transition(scope: OwnerContext, id: String, expected: String, next: String, setting: Boolean): Boolean {
        authorize(scope)
        require((expected == "pending" && next in setOf("sending", "conflict")) ||
            (expected == "sending" && next in setOf("acknowledged", "conflict", "pending")))
        if (setting) {
            val row = requireNotNull(preferenceOperation(id))
            require(row.profileId == scope.profileId.toString() && row.ownerRevision <= scope.revision && !row.isolated)
            return updatePreferenceState(id, expected, next) == 1
        }
        val row = requireNotNull(operation(id))
        require(row.profileId == scope.profileId.toString() && row.privacyEpoch == scope.privacyEpoch && row.ownerRevision == scope.revision && !row.isolated)
        return updateState(id, expected, next) == 1
    }
}
