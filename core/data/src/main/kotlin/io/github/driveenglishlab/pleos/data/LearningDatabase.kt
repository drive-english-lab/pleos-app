package io.github.driveenglishlab.pleos.data

import android.content.Context
import androidx.room.withTransaction
import androidx.room.Dao
import androidx.room.ColumnInfo
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
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

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
                          val kind: String, val payloadJson: String, val version: Long,
                          @ColumnInfo(defaultValue = "3") val payloadSchemaVersion: Int = 5)

@Entity(tableName = "learning_outbox", indices = [Index(value = ["profileId", "privacyEpoch", "isolated"])])
data class OutboxRecord(@PrimaryKey val eventId: String, val profileId: String, val privacyEpoch: Long,
                        val ownerRevision: Long, val recordId: String, val payloadJson: String,
                        val payloadHash: String, val isolated: Boolean = false,
                        @ColumnInfo(defaultValue = "3") val payloadSchemaVersion: Int = 5,
                        @ColumnInfo(defaultValue = "'legacy'") val transport: String = "profile_sync")

@Entity(tableName = "owner_journal")
data class OwnerJournal(@PrimaryKey val operationId: String, val profileId: String,
                        val ownerRevision: Long, val kind: String, val status: String,
                        val isolatedSessionReference: String?)

@Entity(tableName = "history_coverage", primaryKeys = ["profileId", "privacyEpoch"])
data class LocalHistoryCoverage(val profileId: String, val privacyEpoch: Long, val origin: String,
                                val version: Long, val historyGap: Boolean)
@Entity(tableName = "practice_binding", primaryKeys = ["profileId", "privacyEpoch", "lessonId"])
data class PracticeBinding(val profileId: String, val privacyEpoch: Long, val lessonId: String,
                           val articleId: String, val basicQuestionVersionId: String, val taskId: String,
                           val hintMethodAnnounced: Boolean)
@Entity(tableName = "presentation_ticket", indices = [Index(value = ["profileId", "privacyEpoch"])])
data class LocalPresentationTicket(@PrimaryKey val presentationId: String, val profileId: String,
    val privacyEpoch: Long, val ownerRevision: Long, val contextJson: String, val payloadJson: String, val payloadHash: String)
@Entity(tableName = "presentation_observation", indices = [Index(value = ["profileId", "privacyEpoch"])])
data class LocalPresentationObservation(@PrimaryKey val observationId: String, val presentationId: String,
    val profileId: String, val privacyEpoch: Long, val outputInstanceId: String, val payloadJson: String, val payloadHash: String)
@Entity(tableName = "presentation_receipt")
data class LocalPresentationReceipt(@PrimaryKey val operationId: String, val observationId: String,
    val profileId: String, val privacyEpoch: Long, val payloadJson: String, val payloadHash: String)
@Entity(tableName = "dialogue_completion", indices = [Index(value = ["eventId"], unique = true), Index(value = ["profileId", "privacyEpoch", "lessonId"])])
data class LocalDialogueCompletion(@PrimaryKey val outputInstanceId: String, val eventId: String,
    val profileId: String, val privacyEpoch: Long, val lessonId: String, val payloadJson: String, val payloadHash: String)

@Dao
abstract class LearningDao {
    @Query("SELECT * FROM local_owner WHERE slot = 1") abstract suspend fun currentOwner(): LocalOwner?
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putOwner(owner: LocalOwner)
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertRecord(record: LearningRecord)
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertOutbox(record: OutboxRecord)
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertJournal(journal: OwnerJournal)
    @Query("SELECT * FROM owner_journal WHERE profileId = :profile AND kind = 'auth_creation' LIMIT 1")
    abstract suspend fun authCreation(profile: String): OwnerJournal?
    @Query("SELECT * FROM learning_record WHERE profileId = :profile AND privacyEpoch = :epoch AND recordId = :id")
    abstract suspend fun record(profile: String, epoch: Long, id: String): LearningRecord?
    @Query("SELECT * FROM learning_outbox WHERE profileId = :profile AND privacyEpoch = :epoch AND isolated = 0 AND payloadSchemaVersion = 5 ORDER BY rowid LIMIT 50")
    abstract suspend fun pending(profile: String, epoch: Long): List<OutboxRecord>
    @Query("SELECT * FROM learning_outbox WHERE profileId = :profile AND privacyEpoch = :epoch AND payloadSchemaVersion != 5 ORDER BY rowid LIMIT 50")
    abstract suspend fun legacyPending(profile: String, epoch: Long): List<OutboxRecord>
    @Query("SELECT * FROM learning_outbox WHERE eventId = :id")
    abstract suspend fun outboxEvent(id: String): OutboxRecord?
    @Query("SELECT * FROM learning_outbox WHERE profileId = :profile AND privacyEpoch = :epoch AND recordId IN (:records) AND isolated = 0 AND payloadSchemaVersion = 5 AND transport = 'profile_sync' ORDER BY rowid LIMIT 50")
    abstract suspend fun pendingForRecords(profile: String, epoch: Long, records: List<String>): List<OutboxRecord>
    @Query("SELECT * FROM learning_outbox WHERE profileId = :profile AND privacyEpoch = :epoch AND isolated = 0 AND payloadSchemaVersion = 5 AND transport = 'profile_sync' ORDER BY rowid LIMIT 50")
    abstract suspend fun pendingSync(profile: String, epoch: Long): List<OutboxRecord>
    @Query("DELETE FROM learning_outbox WHERE eventId = :id AND payloadHash = :hash AND isolated = 0 AND payloadSchemaVersion = 5")
    protected abstract suspend fun deleteAcknowledged(id: String, hash: String): Int
    @Query("UPDATE learning_record SET payloadJson = :payload, version = :version WHERE profileId = :profile AND privacyEpoch = :epoch AND recordId = :id AND kind = :kind AND version = :previousVersion")
    protected abstract suspend fun updateRead(profile: String, epoch: Long, id: String, kind: String, payload: String, version: Long, previousVersion: Long): Int
    @Query("SELECT * FROM history_coverage WHERE profileId = :profile AND privacyEpoch = :epoch")
    abstract suspend fun historyCoverage(profile: String, epoch: Long): LocalHistoryCoverage?
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putHistoryCoverage(value: LocalHistoryCoverage)
    @Query("SELECT * FROM learning_record WHERE profileId = :profile AND kind = 'profile_settings_command' ORDER BY rowid LIMIT 1")
    abstract suspend fun pendingProfileSettings(profile: String): LearningRecord?
    @Query("DELETE FROM learning_record WHERE profileId = :profile AND recordId = :id AND kind = 'profile_settings_command' AND payloadJson = :payload")
    abstract suspend fun acknowledgeProfileSettings(profile: String, id: String, payload: String): Int
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertBinding(binding: PracticeBinding)
    @Query("DELETE FROM learning_record WHERE profileId = :profile AND privacyEpoch = :epoch AND recordId = :id AND kind = 'lesson_command' AND payloadJson = :payload")
    abstract suspend fun acknowledgeLocalCommand(profile: String, epoch: Long, id: String, payload: String): Int
    @Query("SELECT * FROM learning_record WHERE profileId = :profile AND kind = 'lesson_command' LIMIT 1")
    abstract suspend fun pendingLocalCommand(profile: String): LearningRecord?
    @Query("SELECT * FROM practice_binding WHERE profileId = :profile AND privacyEpoch = :epoch AND lessonId = :lesson")
    abstract suspend fun binding(profile: String, epoch: Long, lesson: String): PracticeBinding?
    @Query("UPDATE practice_binding SET hintMethodAnnounced = 1 WHERE profileId = :profile AND privacyEpoch = :epoch AND lessonId = :lesson")
    abstract suspend fun setHintAnnounced(profile: String, epoch: Long, lesson: String): Int
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertTicket(ticket: LocalPresentationTicket)
    @Query("SELECT * FROM presentation_ticket WHERE profileId = :profile AND privacyEpoch = :epoch AND presentationId = :id")
    abstract suspend fun ticket(profile: String, epoch: Long, id: String): LocalPresentationTicket?
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertObservation(value: LocalPresentationObservation)
    @Query("SELECT * FROM presentation_observation WHERE observationId = :id") abstract suspend fun observation(id: String): LocalPresentationObservation?
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertReceipt(value: LocalPresentationReceipt)
    @Query("SELECT * FROM presentation_receipt WHERE operationId = :id") abstract suspend fun receipt(id: String): LocalPresentationReceipt?
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertDialogue(value: LocalDialogueCompletion)
    @Query("SELECT * FROM dialogue_completion WHERE outputInstanceId = :id") abstract suspend fun dialogue(id: String): LocalDialogueCompletion?
    @Query("UPDATE learning_outbox SET isolated = 1 WHERE profileId = :profile")
    abstract suspend fun isolate(profile: String)

    protected suspend fun requireOwner(owner: OwnerContext) {
        val current = currentOwner()
        require(current != null && current.profileId == owner.profileId.toString() && current.privacyEpoch == owner.privacyEpoch &&
            current.revision == owner.revision && current.status in setOf("provisional", "active")) { "Owner changed." }
    }

    @Transaction
    open suspend fun queueSync(owner: OwnerContext, event: OutboxRecord, inputSnapshot: String? = null): Boolean {
        requireOwner(owner)
        require(event.profileId == owner.profileId.toString() && event.privacyEpoch == owner.privacyEpoch &&
            event.ownerRevision == owner.revision && !event.isolated && event.payloadSchemaVersion == 5 && event.transport == "profile_sync")
        val previous = outboxEvent(event.eventId)
        if (previous != null) require(previous == event) else insertOutbox(event)
        if (inputSnapshot != null) {
            val id = stableRecordId("local-input:" + event.recordId).toString()
            val old = record(event.profileId,event.privacyEpoch,id)
            if (old?.payloadJson != inputSnapshot) saveRead(owner,LearningRecord(event.profileId,event.privacyEpoch,id,
                "local_sync_input",inputSnapshot,(old?.version ?: 0) + 1))
        }
        return previous == null
    }

    /** Server read projections/receipts are separate from immutable attempt input rows. */
    @Transaction
    open suspend fun saveRead(owner: OwnerContext, value: LearningRecord): Boolean {
        requireOwner(owner)
        require(value.profileId == owner.profileId.toString() && value.privacyEpoch == owner.privacyEpoch &&
            value.payloadSchemaVersion == 5 && value.version >= 1 && value.kind != "attempt")
        val old = record(value.profileId, value.privacyEpoch, value.recordId)
        if (old == null) { insertRecord(value); return true }
        require(old.kind == value.kind && old.payloadSchemaVersion == 5)
        if (value.version < old.version) return false
        if (value.version == old.version) { require(value == old); return false }
        require(updateRead(value.profileId, value.privacyEpoch, value.recordId, value.kind, value.payloadJson, value.version, old.version) == 1)
        return true
    }

    @Transaction
    open suspend fun acknowledgeSync(owner: OwnerContext, sent: List<OutboxRecord>, receipt: LearningRecord) {
        requireOwner(owner)
        require(sent.isNotEmpty() && sent.size <= 50 && sent.map { it.eventId }.distinct().size == sent.size)
        for (event in sent) {
            require(event.profileId == owner.profileId.toString() && event.privacyEpoch == owner.privacyEpoch && event.ownerRevision == owner.revision)
            require(outboxEvent(event.eventId) == event)
        }
        saveRead(owner, receipt)
        val receipts = org.json.JSONObject(receipt.payloadJson).getJSONArray("receipts")
        for (index in 0 until receipts.length()) {
            val value = receipts.getJSONObject(index)
            val recordId = value.getString("record_id"); val version = value.getLong("version")
            val payload = org.json.JSONObject().put("record_id", recordId).put("version", version).toString()
            saveRead(owner, LearningRecord(owner.profileId.toString(), owner.privacyEpoch,
                stableRecordId("server-record:" + recordId).toString(), "server_record_version", payload, version))
        }
        for (event in sent) require(deleteAcknowledged(event.eventId, event.payloadHash) == 1)
    }

    @Transaction
    open suspend fun saveAttemptResult(owner: OwnerContext, id: String, payload: String): Boolean {
        requireOwner(owner)
        val old = record(owner.profileId.toString(), owner.privacyEpoch, id)
        if (old != null) {
            require(old.kind == "attempt_result" && old.payloadSchemaVersion == 5)
            val previous = org.json.JSONObject(old.payloadJson); val next = org.json.JSONObject(payload)
            require(previous.getString("attempt_id") == next.getString("attempt_id"))
            if (previous.getJSONObject("lesson_state").getLong("version") > next.getJSONObject("lesson_state").getLong("version") ||
                previous.getJSONObject("example_exposure").getLong("revision") > next.getJSONObject("example_exposure").getLong("revision") ||
                !previous.isNull("feedback") && next.isNull("feedback")) return false
            if (old.payloadJson == payload) return false
        }
        return saveRead(owner, LearningRecord(owner.profileId.toString(), owner.privacyEpoch, id, "attempt_result", payload, (old?.version ?: 0) + 1))
    }

    @Transaction
    open suspend fun restoreDialogue(owner: OwnerContext, lessonId: UUID): DialogueProgress? {
        requireOwner(owner)
        return binding(owner.profileId.toString(), owner.privacyEpoch, lessonId.toString())?.let { DialogueProgress(it.hintMethodAnnounced) }
    }

    @Transaction
    open suspend fun createLesson(owner: OwnerContext, lesson: LessonInput): Boolean {
        requireOwner(owner)
        val profile = owner.profileId.toString(); val id = lesson.lessonId.toString()
        val payload = LearningJson.lesson(lesson).toString()
        val existing = record(profile, owner.privacyEpoch, id)
        if (existing != null) { require(existing.payloadJson == payload && existing.payloadSchemaVersion == 5); return false }
        // An imported true needs the append-only witnessed journal first, never a bare create flag.
        require(!lesson.dialogueProgress.hintMethodAnnounced)
        insertRecord(LearningRecord(profile, owner.privacyEpoch, id, "lesson", payload, 1))
        insertBinding(PracticeBinding(profile, owner.privacyEpoch, id, lesson.contentRef.articleId.toString(),
            lesson.contentRef.basicQuestionVersionId.toString(), lesson.contentRef.taskIdentity.toString(), false))
        return true
    }

    @Transaction
    open suspend fun importLessonBinding(owner: OwnerContext, lessonId: UUID, ref: PracticeContentRef, announced: Boolean) {
        requireOwner(owner)
        val profile = owner.profileId.toString(); val id = lessonId.toString()
        val old = binding(profile, owner.privacyEpoch, id)
        if (old == null) insertBinding(PracticeBinding(profile, owner.privacyEpoch, id, ref.content.articleId.toString(),
            ref.basicQuestionVersionId.toString(), ref.taskId.toString(), announced))
        else {
            require(old.articleId == ref.content.articleId.toString() && old.basicQuestionVersionId == ref.basicQuestionVersionId.toString() && old.taskId == ref.taskId.toString())
            if (announced) require(setHintAnnounced(profile, owner.privacyEpoch, id) == 1)
        }
    }

    @Transaction
    open suspend fun persistTicket(owner: OwnerContext, value: PracticePresentationTicket): Boolean {
        requireOwner(owner)
        require(value.profileContext == ProfileContext(owner.profileId, owner.privacyEpoch) && value.clientContext == owner)
        val payload = LearningJson.ticket(value).toString()
        val row = LocalPresentationTicket(value.presentation.presentationId.toString(), owner.profileId.toString(), owner.privacyEpoch,
            owner.revision, LearningJson.context(value.clientContext).toString(), payload, LearningJson.hash(payload))
        val old = ticket(row.profileId, row.privacyEpoch, row.presentationId)
        if (old != null) { require(old == row); return false }
        insertTicket(row)
        return true
    }

    @Transaction
    open suspend fun persistObservation(owner: OwnerContext, presentationId: UUID, value: PracticePresentationObservation): Boolean {
        requireOwner(owner)
        require(value.profileContext == ProfileContext(owner.profileId, owner.privacyEpoch))
        val ticket = requireNotNull(ticket(owner.profileId.toString(), owner.privacyEpoch, presentationId.toString()))
        require(ticket.ownerRevision == owner.revision && ticket.contextJson == LearningJson.context(value.clientContext).toString())
        val payload = LearningJson.observation(value).toString()
        val row = LocalPresentationObservation(value.observationId.toString(), presentationId.toString(), ticket.profileId,
            ticket.privacyEpoch, value.outputInstanceId.toString(), payload, LearningJson.hash(payload))
        val old = observation(row.observationId)
        if (old != null) { require(old == row); return false }
        insertObservation(row)
        insertOutbox(OutboxRecord(value.operationId.toString(), row.profileId, row.privacyEpoch, owner.revision,
            row.presentationId, payload, row.payloadHash, transport = "presentation_observation"))
        return true
    }

    @Transaction
    open suspend fun persistObservationReceipt(owner: OwnerContext, observationId: UUID, value: PracticePresentationReceipt): Boolean {
        requireOwner(owner)
        require(value.profileContext == ProfileContext(owner.profileId, owner.privacyEpoch))
        val observed = requireNotNull(observation(observationId.toString()))
        require(observed.profileId == owner.profileId.toString() && observed.privacyEpoch == owner.privacyEpoch &&
            observed.presentationId == value.presentation.presentationId.toString() &&
            value.presentation.lastObservationId == observationId &&
            org.json.JSONObject(observed.payloadJson).getString("operation_id") == value.operationId.toString())
        val payload = org.json.JSONObject().put("schema_version", "5").put("operation_id", value.operationId.toString())
            .put("profile_context", LearningJson.profile(value.profileContext)).put("presentation", LearningJson.presentation(value.presentation))
            .put("exposure", LearningJson.exposure(value.exposure)).toString()
        val row = LocalPresentationReceipt(value.operationId.toString(), observationId.toString(), observed.profileId, observed.privacyEpoch,
            payload, LearningJson.hash(payload))
        val old = receipt(row.operationId)
        if (old != null) { require(old == row); return false }
        insertReceipt(row)
        return true
    }

    @Transaction
    open suspend fun acknowledgePresentation(owner: OwnerContext, observationId: UUID, value: PracticePresentationReceipt) {
        requireOwner(owner)
        persistObservationReceipt(owner, observationId, value)
        val pending = outboxEvent(value.operationId.toString()) ?: return
        require(pending.profileId == owner.profileId.toString() && pending.privacyEpoch == owner.privacyEpoch &&
            pending.transport == "presentation_observation" && pending.ownerRevision == owner.revision)
        require(deleteAcknowledged(pending.eventId, pending.payloadHash) == 1)
    }

    @Transaction
    open suspend fun persistHintCompletion(owner: OwnerContext, event: DialogueProgressEvent): Boolean {
        requireOwner(owner)
        val profile = owner.profileId.toString(); val fact = event.observation
        requireNotNull(binding(profile, owner.privacyEpoch, fact.lessonId.toString()))
        val payload = LearningJson.dialogueEvent(event).toString()
        val row = LocalDialogueCompletion(fact.outputInstanceId.toString(), event.eventId.toString(), profile, owner.privacyEpoch,
            fact.lessonId.toString(), payload, LearningJson.hash(payload))
        val old = dialogue(row.outputInstanceId)
        if (old != null) { require(old == row); return false }
        insertDialogue(row)
        require(setHintAnnounced(profile, owner.privacyEpoch, row.lessonId) == 1)
        insertOutbox(OutboxRecord(row.eventId, profile, owner.privacyEpoch, owner.revision, row.lessonId, payload, row.payloadHash))
        return true
    }

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

    @Query("SELECT * FROM owner_journal WHERE kind IN ('logout','merge','history_deletion') AND status NOT IN ('complete','reprepared') ORDER BY rowid")
    abstract suspend fun pendingOwnerTransitions(): List<OwnerJournal>
    @Query("UPDATE owner_journal SET status = :status WHERE operationId = :id")
    abstract suspend fun updateOwnerJournal(id: String, status: String)

    @Transaction
    open suspend fun fenceOwner(expected: OwnerContext, operation: UUID, kind: String, reference: String?): LocalOwner {
        require(kind in setOf("logout", "merge", "history_deletion"))
        requireOwner(expected)
        val previous = requireNotNull(currentOwner())
        insertJournal(OwnerJournal(operation.toString(), previous.profileId, previous.revision, kind, "pending", reference))
        isolate(previous.profileId)
        return previous.copy(revision = previous.revision + 1, status = if (kind == "history_deletion") "delete_pending" else "transition")
            .also { putOwner(it) }
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

@Database(entities = [LocalOwner::class, LearningRecord::class, OutboxRecord::class, OwnerJournal::class,
    LocalHistoryCoverage::class, PracticeBinding::class, LocalPresentationTicket::class, LocalPresentationObservation::class,
    LocalPresentationReceipt::class, LocalDialogueCompletion::class,
    LocalProgressPreference::class, LocalProgressPreferenceJournal::class, LocalProgressCache::class, LocalProgressJournal::class,
    LocalAiLearningPreference::class, LocalAiLearningPreferenceJournal::class, LocalAiLearningCache::class, LocalAiLearningJournal::class,
    LocalReviewContext::class, LocalReviewOperation::class, LocalReviewObservation::class,
    LocalFreshPracticeIntent::class, LocalPreparationWaiter::class, LocalSettingsDraft::class, LocalSource6Record::class,
    LocalSegmentState::class, LocalSegmentOperation::class, LocalSegmentCompletion::class,
    LocalPlaybackRequest::class, LocalCatalogViewPage::class], version = 5, exportSchema = true)
abstract class LearningDatabase : RoomDatabase() {
    abstract fun learning(): LearningDao
    abstract fun progress(): LearningProgressDao
    abstract fun aiLearning(): AiLearningDao
    abstract fun review(): ReviewDao
    abstract fun segments(): SegmentPlaybackDao
    abstract fun serviceIntents(): ServiceIntentDao
}

@Singleton
class LearningStore internal constructor(private val database: LearningDatabase, private val practiceClock: OriginalAttemptClock) {
    @Inject constructor(database: LearningDatabase) : this(database, OriginalAttemptClock { OriginalPracticeTime.Unknown })
    suspend fun provisionalOwner(): LocalOwner = database.learning().provisionalOwner()

    /** Device-only acceptance. This does not claim a server ACK or bootstrap identity. */
    suspend fun acceptAnswer(answer: AcceptedAnswer, turn: String, previousAttemptId: UUID?): Boolean {
        require(turn in setOf("first", "retry") && (turn == "first") == (previousAttemptId == null))
        val lease = answer.lease
        val fixed = lease.task as? PracticeContentRef ?: error("Prepared answer requires source6 acceptance adapter")
        val payload = LearningJson.attempt(AttemptInput(lease.attemptId, lease.lessonId, fixed,
            AttemptTurn.valueOf(turn.uppercase(java.util.Locale.ROOT)), previousAttemptId, answer.text, answer.capture, answer.helpReceived)).toString()
        val hash = LearningJson.hash(payload)
        val owner = lease.owner
        return database.withTransaction {
            val accepted = database.learning().accept(owner,
            LearningRecord(owner.profileId.toString(), owner.privacyEpoch, lease.attemptId.toString(), "attempt", payload, 1),
            OutboxRecord(lease.attemptId.toString(), owner.profileId.toString(), owner.privacyEpoch,
                         owner.revision, lease.attemptId.toString(), payload, hash))
            if (accepted) journalPracticeTime(database, answer, practiceClock.atAcceptance(answer))
            CaptureConfirmationCommit.accept(database.review(),answer,CaptureConfirmationProtocol.LEGACY5)
            accepted
        }
    }

    suspend fun restoreDialogue(owner: OwnerContext, lessonId: UUID): DialogueProgress? = database.learning().restoreDialogue(owner, lessonId)

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
        Room.databaseBuilder(context, LearningDatabase::class.java, "learning.db").addMigrations(LEARNING_MIGRATION_1_2, LEARNING_MIGRATION_2_3, LEARNING_MIGRATION_3_4, LEARNING_MIGRATION_4_5).build()
}
