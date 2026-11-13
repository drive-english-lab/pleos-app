package io.github.driveenglishlab.pleos.data

import androidx.room.*
import io.github.driveenglishlab.pleos.domain.OwnerContext

/** Epoch-independent setting snapshot. Not an output or synchronization grant. */
@Entity(tableName = "daily_preference_cache")
data class LocalProgressPreference(@PrimaryKey val profileId: String, val version: Long, val payloadJson: String)

/** Original intent and wire bytes are immutable once this operation is recorded. */
@Entity(tableName = "daily_preference_journal", indices = [Index(value = ["profileId", "isolated", "localSequence"])])
data class LocalProgressPreferenceJournal(@PrimaryKey val operationId: String, val profileId: String,
    val ownerRevision: Long, val localSequence: Long, val intentJson: String,
    val payloadJson: String, val payloadHash: String, val state: String, val isolated: Boolean)

@Entity(tableName = "speaking_records_cache", primaryKeys = ["profileId", "privacyEpoch", "kind", "cacheKey"])
data class LocalProgressCache(val profileId: String, val privacyEpoch: Long, val kind: String,
    val cacheKey: String, val revision: Long, val payloadJson: String)

@Entity(tableName = "speaking_records_journal", indices = [Index(value = ["profileId", "privacyEpoch", "isolated"])])
data class LocalProgressJournal(@PrimaryKey val operationId: String, val profileId: String, val privacyEpoch: Long,
    val ownerRevision: Long, val kind: String, val payloadJson: String, val payloadHash: String,
    val state: String, val isolated: Boolean)

@Dao
abstract class LearningProgressDao {
    @Query("SELECT * FROM local_owner WHERE slot = 1")
    protected abstract suspend fun owner(): LocalOwner?
    @Query("SELECT * FROM daily_preference_cache WHERE profileId = :profile")
    abstract suspend fun preference(profile: String): LocalProgressPreference?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putPreference(value: LocalProgressPreference)
    @Query("SELECT * FROM daily_preference_journal WHERE operationId = :id")
    abstract suspend fun preferenceOperation(id: String): LocalProgressPreferenceJournal?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertPreferenceOperation(value: LocalProgressPreferenceJournal)
    @Query("SELECT * FROM daily_preference_journal WHERE profileId = :profile AND isolated = 0 AND state IN ('pending', 'sending') ORDER BY localSequence LIMIT 50")
    abstract suspend fun pendingPreferences(profile: String): List<LocalProgressPreferenceJournal>
    @Query("UPDATE daily_preference_journal SET state = :next WHERE operationId = :id AND state = :previous AND isolated = 0")
    protected abstract suspend fun updatePreferenceState(id: String, previous: String, next: String): Int
    @Query("SELECT * FROM speaking_records_cache WHERE profileId = :profile AND privacyEpoch = :epoch AND kind = :kind AND cacheKey = :key")
    abstract suspend fun cache(profile: String, epoch: Long, kind: String, key: String): LocalProgressCache?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putCache(value: LocalProgressCache)
    @Query("SELECT * FROM speaking_records_journal WHERE operationId = :id")
    abstract suspend fun operation(id: String): LocalProgressJournal?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertOperation(value: LocalProgressJournal)
    @Query("SELECT * FROM speaking_records_journal WHERE profileId = :profile AND privacyEpoch = :epoch AND isolated = 0 AND state IN ('pending', 'sending') ORDER BY rowid LIMIT 50")
    abstract suspend fun pending(profile: String, epoch: Long): List<LocalProgressJournal>
    @Query("UPDATE speaking_records_journal SET state = :next WHERE operationId = :id AND state = :previous AND isolated = 0")
    protected abstract suspend fun updateState(id: String, previous: String, next: String): Int
    @Query("DELETE FROM speaking_records_cache WHERE profileId = :profile AND privacyEpoch = :epoch")
    abstract suspend fun deleteEpochCache(profile: String, epoch: Long)
    @Query("DELETE FROM speaking_records_journal WHERE profileId = :profile AND privacyEpoch = :epoch")
    abstract suspend fun deleteEpochJournal(profile: String, epoch: Long)
    @Query("UPDATE daily_preference_journal SET isolated = 1 WHERE profileId = :profile")
    abstract suspend fun isolateSettings(profile: String)
    @Query("UPDATE speaking_records_journal SET isolated = 1 WHERE profileId = :profile")
    abstract suspend fun isolateLearning(profile: String)
    @Query("DELETE FROM speaking_records_cache WHERE profileId = :profile")
    abstract suspend fun clearVisibleCache(profile: String)

    private suspend fun authorize(scope: OwnerContext) {
        val current = owner()
        require(current != null && current.profileId == scope.profileId.toString() && current.privacyEpoch == scope.privacyEpoch &&
            current.revision == scope.revision && current.status in setOf("provisional", "active")) { "Owner changed." }
    }

    @Transaction
    open suspend fun savePreference(scope: OwnerContext, value: LocalProgressPreference): Boolean {
        authorize(scope); require(value.profileId == scope.profileId.toString() && value.version >= 0)
        val previous = preference(value.profileId)
        if (previous != null) {
            if (value.version < previous.version) return false
            if (value.version == previous.version) { require(value == previous); return false }
        }
        putPreference(value); return true
    }

    @Transaction
    open suspend fun queuePreference(scope: OwnerContext, value: LocalProgressPreferenceJournal): Boolean {
        authorize(scope)
        require(value.profileId == scope.profileId.toString() && value.ownerRevision == scope.revision &&
            value.localSequence > 0 && value.state == "pending" && !value.isolated && LearningJson.hash(value.payloadJson) == value.payloadHash)
        val old = preferenceOperation(value.operationId)
        if (old != null) { require(old == value); return false }
        insertPreferenceOperation(value); return true
    }

    @Transaction
    open suspend fun saveCache(scope: OwnerContext, value: LocalProgressCache): Boolean {
        authorize(scope); require(value.profileId == scope.profileId.toString() && value.privacyEpoch == scope.privacyEpoch && value.revision >= 0)
        val previous = cache(value.profileId, value.privacyEpoch, value.kind, value.cacheKey)
        if (previous != null) {
            if (value.revision < previous.revision) return false
            if (value.revision == previous.revision) { require(value == previous); return false }
        }
        putCache(value); return true
    }

    @Transaction
    open suspend fun queue(scope: OwnerContext, value: LocalProgressJournal): Boolean {
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
            require(row.profileId == scope.profileId.toString() && row.ownerRevision == scope.revision && !row.isolated)
            return updatePreferenceState(id, expected, next) == 1
        }
        val row = requireNotNull(operation(id))
        require(row.profileId == scope.profileId.toString() && row.privacyEpoch == scope.privacyEpoch && row.ownerRevision == scope.revision && !row.isolated)
        return updateState(id, expected, next) == 1
    }
}
