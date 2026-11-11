package io.github.driveenglishlab.pleos.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.driveenglishlab.pleos.domain.AcceptedAnswer
import io.github.driveenglishlab.pleos.domain.HelpSnapshot
import io.github.driveenglishlab.pleos.domain.OwnerContext
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "local_owner")
data class LocalOwner(
    @PrimaryKey val slot: Int = 1,
    val profileId: String,
    val privacyEpoch: Long,
    val revision: Long,
    val status: String,
)

@Entity(tableName = "learning_record", primaryKeys = ["profileId", "privacyEpoch", "recordId"])
data class LearningRecord(val profileId: String, val privacyEpoch: Long, val recordId: String,
                          val kind: String, val payloadJson: String, val version: Long)

@Entity(tableName = "learning_outbox", indices = [Index(value = ["profileId", "privacyEpoch", "isolated"])])
data class OutboxRecord(@PrimaryKey val eventId: String, val profileId: String, val privacyEpoch: Long,
                        val ownerRevision: Long, val recordId: String, val payloadJson: String,
                        val payloadHash: String, val isolated: Boolean = false)

@Entity(tableName = "owner_journal")
data class OwnerJournal(@PrimaryKey val operationId: String, val profileId: String,
                        val ownerRevision: Long, val kind: String, val status: String,
                        val isolatedSessionReference: String?)

@Dao
abstract class LearningDao {
    @Query("SELECT * FROM local_owner WHERE slot = 1") abstract suspend fun currentOwner(): LocalOwner?
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putOwner(owner: LocalOwner)
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertRecord(record: LearningRecord)
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertOutbox(record: OutboxRecord)
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertJournal(journal: OwnerJournal)
    @Query("SELECT * FROM learning_record WHERE profileId = :profile AND privacyEpoch = :epoch AND recordId = :id")
    abstract suspend fun record(profile: String, epoch: Long, id: String): LearningRecord?
    @Query("SELECT * FROM learning_outbox WHERE profileId = :profile AND privacyEpoch = :epoch AND isolated = 0 ORDER BY rowid LIMIT 50")
    abstract suspend fun pending(profile: String, epoch: Long): List<OutboxRecord>
    @Query("UPDATE learning_outbox SET isolated = 1 WHERE profileId = :profile")
    abstract suspend fun isolate(profile: String)

    @Transaction
    open suspend fun provisionalOwner(): LocalOwner {
        currentOwner()?.let { return it }
        return LocalOwner(profileId = UUID.randomUUID().toString(), privacyEpoch = 0,
                          revision = 1, status = "provisional").also { putOwner(it) }
    }

    @Transaction
    open suspend fun accept(owner: OwnerContext, record: LearningRecord, outbox: OutboxRecord): Boolean {
        val current = currentOwner()
        require(current != null && current.profileId == owner.profileId.toString() &&
            current.privacyEpoch == owner.privacyEpoch && current.revision == owner.revision &&
            current.status in setOf("provisional", "active")) { "Owner changed before acceptance." }
        require(record.profileId == current.profileId && record.privacyEpoch == current.privacyEpoch)
        require(outbox.profileId == record.profileId && outbox.privacyEpoch == record.privacyEpoch &&
            outbox.ownerRevision == current.revision && outbox.recordId == record.recordId && !outbox.isolated)
        val existing = record(record.profileId, record.privacyEpoch, record.recordId)
        if (existing != null) {
            require(existing == record) { "Attempt ID reused with a different payload." }
            return false
        }
        insertRecord(record)
        insertOutbox(outbox)
        return true
    }

    @Transaction
    open suspend fun detachForLogout(expectedRevision: Long, operationId: String,
                                     isolatedSessionReference: String?): LocalOwner {
        val previous = requireNotNull(currentOwner())
        require(previous.revision == expectedRevision)
        insertJournal(OwnerJournal(operationId, previous.profileId, previous.revision,
                                  "logout", "pending", isolatedSessionReference))
        isolate(previous.profileId)
        return LocalOwner(profileId = UUID.randomUUID().toString(), privacyEpoch = 0,
                          revision = previous.revision + 1, status = "provisional").also { putOwner(it) }
    }
}

@Database(entities = [LocalOwner::class, LearningRecord::class, OutboxRecord::class, OwnerJournal::class],
          version = 1, exportSchema = true)
abstract class LearningDatabase : RoomDatabase() {
    abstract fun learning(): LearningDao
}

@Singleton
class LearningStore @Inject constructor(private val database: LearningDatabase) {
    suspend fun provisionalOwner(): LocalOwner = database.learning().provisionalOwner()

    /** Device-only acceptance. This does not claim a server ACK or bootstrap identity. */
    suspend fun acceptAnswer(answer: AcceptedAnswer, help: HelpSnapshot,
                             turn: String, previousAttemptId: UUID?): Boolean {
        require(turn in setOf("first", "retry") && (turn == "first") == (previousAttemptId == null))
        val lease = answer.lease
        val content = lease.task.content
        val payload = JSONObject().put("attempt_id", lease.attemptId.toString())
            .put("lesson_id", lease.lessonId.toString())
            .put("content_ref", JSONObject().put("article_id", content.articleId.toString())
                .put("bundle_id", content.bundleId.toString()).put("source_snapshot_id", content.sourceSnapshotId.toString())
                .put("listening_mode", content.listeningMode.name.lowercase()).put("listening_script_id", content.listeningScriptId.toString()).put("task_id", lease.task.taskId.toString()))
            .put("turn", turn).put("previous_attempt_id", previousAttemptId?.toString() ?: JSONObject.NULL)
            .put("answer_text", answer.text)
            .put("capture", JSONObject().put("path", "parked_stt").put("form_fidelity", "unknown").put("uncertain_spans", JSONArray()))
            .put("help_received", JSONArray(help.kinds.map { it.name.lowercase() }.sorted()))
            .put("support_refs", JSONArray(help.refs.sorted())).toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val owner = lease.owner
        return database.learning().accept(owner,
            LearningRecord(owner.profileId.toString(), owner.privacyEpoch, lease.attemptId.toString(), "attempt", payload, 1),
            OutboxRecord(lease.attemptId.toString(), owner.profileId.toString(), owner.privacyEpoch,
                         owner.revision, lease.attemptId.toString(), payload, hash))
    }

    suspend fun outbox(owner: OwnerContext): List<OutboxRecord> {
        val current = database.learning().currentOwner()
        require(current != null && current.status == "active" && current.profileId == owner.profileId.toString() &&
                current.privacyEpoch == owner.privacyEpoch && current.revision == owner.revision)
        return database.learning().pending(current.profileId, current.privacyEpoch)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object LearningDataModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): LearningDatabase =
        Room.databaseBuilder(context, LearningDatabase::class.java, "learning.db").build()
}
