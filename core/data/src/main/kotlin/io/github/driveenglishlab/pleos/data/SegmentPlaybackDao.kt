package io.github.driveenglishlab.pleos.data

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.driveenglishlab.pleos.domain.OwnerContext

/** Current resume fact, independent from actual sequential-completion evidence. No source text. */
@Entity(tableName = "segment_state", primaryKeys = ["profileId", "privacyEpoch", "selectionId", "phase", "purpose", "cursorKind"])
data class LocalSegmentState(val profileId: String, val privacyEpoch: Long, val selectionId: String,
    val phase: String, val purpose: String, val cursorKind: String, val ownerRevision: Long,
    val sourceJson: String, val manifestJson: String?, val cursorJson: String, val runId: String?,
    val generation: Long, val ledgerJson: String?, val activeRequestJson: String?, val bindingAcknowledged: Boolean,
    val localVersion: Long)

/** Wire/intent bytes, IDs and CAS are immutable. Only delivery state and original receipt change. */
@Entity(tableName = "segment_journal", indices = [Index(value = ["profileId", "privacyEpoch", "isolated"])])
data class LocalSegmentOperation(@PrimaryKey val operationId: String, val profileId: String, val privacyEpoch: Long,
    val ownerRevision: Long, val selectionId: String, val runId: String, val kind: String,
    val payloadJson: String, val payloadHash: String, val state: String, val isolated: Boolean,
    val receiptJson: String?)

/** Local witnessed completion awaiting a server-derived record. No invented completed_at timestamp. */
@Entity(tableName = "segment_completion", primaryKeys = ["profileId", "privacyEpoch", "runId"])
data class LocalSegmentCompletion(val profileId: String, val privacyEpoch: Long, val runId: String,
    val selectionId: String, val scriptId: String, val manifestId: String, val proofJson: String,
    val serverRecordJson: String?)

@Dao
abstract class SegmentPlaybackDao {
    @Query("SELECT * FROM local_owner WHERE slot = 1")
    protected abstract suspend fun owner(): LocalOwner?
    @Query("SELECT * FROM segment_state WHERE profileId = :profile AND privacyEpoch = :epoch AND selectionId = :selection AND phase = :phase AND purpose = :purpose AND cursorKind = :kind")
    abstract suspend fun state(profile: String, epoch: Long, selection: String, phase: String, purpose: String, kind: String): LocalSegmentState?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putState(value: LocalSegmentState)
    @Query("SELECT * FROM segment_journal WHERE operationId = :id")
    abstract suspend fun operation(id: String): LocalSegmentOperation?
    @Query("SELECT * FROM segment_journal WHERE profileId = :profile AND privacyEpoch = :epoch AND isolated = 0 AND state = 'pending' ORDER BY rowid")
    abstract suspend fun pending(profile: String, epoch: Long): List<LocalSegmentOperation>
    @Query("SELECT COUNT(*) FROM segment_journal WHERE profileId = :profile AND privacyEpoch = :epoch AND runId = :run AND kind = 'observation'")
    abstract suspend fun observationCount(profile: String, epoch: Long, run: String): Long
    @Query("SELECT * FROM segment_journal WHERE profileId = :profile AND privacyEpoch = :epoch AND selectionId = :selection AND kind = 'cursor' AND state = 'acknowledged' AND isolated = 0 ORDER BY rowid DESC LIMIT 1")
    abstract suspend fun checkpoint(profile: String, epoch: Long, selection: String): LocalSegmentOperation?
    @Query("SELECT * FROM segment_journal WHERE profileId=:profile AND privacyEpoch=:epoch AND selectionId=:selection AND kind='start' AND state='acknowledged' AND isolated=0 ORDER BY rowid")
    abstract suspend fun acknowledgedStarts(profile:String,epoch:Long,selection:String):List<LocalSegmentOperation>
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertOperation(value: LocalSegmentOperation)
    @Query("UPDATE segment_journal SET state = 'acknowledged', receiptJson = :receipt WHERE operationId = :id AND payloadHash = :hash AND isolated = 0 AND state = 'pending'")
    protected abstract suspend fun acknowledgeRow(id: String, hash: String, receipt: String?): Int
    @Query("SELECT * FROM segment_completion WHERE profileId = :profile AND privacyEpoch = :epoch AND runId = :run")
    abstract suspend fun completion(profile: String, epoch: Long, run: String): LocalSegmentCompletion?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCompletion(value: LocalSegmentCompletion)
    @Query("UPDATE segment_completion SET serverRecordJson = :record WHERE profileId = :profile AND privacyEpoch = :epoch AND runId = :run AND serverRecordJson IS NULL")
    protected abstract suspend fun confirmCompletionRow(profile: String, epoch: Long, run: String, record: String): Int
    @Query("UPDATE segment_journal SET isolated = 1 WHERE profileId = :profile")
    abstract suspend fun isolate(profile: String)
    @Query("DELETE FROM segment_state WHERE profileId = :profile AND privacyEpoch = :epoch")
    abstract suspend fun deleteEpochState(profile: String, epoch: Long)
    @Query("DELETE FROM segment_journal WHERE profileId = :profile AND privacyEpoch = :epoch")
    abstract suspend fun deleteEpochJournal(profile: String, epoch: Long)
    @Query("DELETE FROM segment_completion WHERE profileId = :profile AND privacyEpoch = :epoch")
    abstract suspend fun deleteEpochCompletions(profile: String, epoch: Long)
    @Query("DELETE FROM segment_state WHERE profileId = :profile")
    abstract suspend fun clearVisibleState(profile: String)

    private suspend fun authorize(scope: OwnerContext) {
        val actual = owner()
        require(actual != null && actual.profileId == scope.profileId.toString() && actual.privacyEpoch == scope.privacyEpoch &&
            actual.revision == scope.revision && actual.status in setOf("active", "provisional")) { "Owner changed." }
    }
    private fun requireScope(scope: OwnerContext, value: LocalSegmentOperation) {
        require(value.profileId == scope.profileId.toString() && value.privacyEpoch == scope.privacyEpoch && value.ownerRevision == scope.revision &&
            !value.isolated && value.state == "pending" && LearningJson.hash(value.payloadJson) == value.payloadHash)
    }
    private suspend fun queue(scope: OwnerContext, value: LocalSegmentOperation) {
        requireScope(scope, value)
        val old = operation(value.operationId)
        if (old == null) insertOperation(value) else require(old.copy(state = "pending", receiptJson = null) == value)
    }
    @Transaction
    open suspend fun enqueue(scope: OwnerContext, value: LocalSegmentOperation) {
        authorize(scope); queue(scope, value)
    }
    @Transaction
    open suspend fun preserveLegacy(scope: OwnerContext, value: LocalSegmentState) {
        authorize(scope)
        require(value.profileId == scope.profileId.toString() && value.privacyEpoch == scope.privacyEpoch && value.ownerRevision == scope.revision && value.cursorKind == "legacy_time")
        val old = state(value.profileId, value.privacyEpoch, value.selectionId, value.phase, value.purpose, value.cursorKind)
        if (old == null) putState(value) else require(old == value)
    }
    /** Actual event receipt + state/frontier + local completion is one transaction. */
    @Transaction
    open suspend fun commit(scope: OwnerContext, value: LocalSegmentState, operations: List<LocalSegmentOperation>, completion: LocalSegmentCompletion? = null) {
        authorize(scope)
        require(value.profileId == scope.profileId.toString() && value.privacyEpoch == scope.privacyEpoch && value.ownerRevision == scope.revision && value.cursorKind == "segment")
        val old = state(value.profileId, value.privacyEpoch, value.selectionId, value.phase, value.purpose, value.cursorKind)
        require(value.localVersion == (old?.localVersion ?: 0) + 1)
        operations.forEach { queue(scope, it) }
        if (completion != null) {
            require(completion.profileId == value.profileId && completion.privacyEpoch == value.privacyEpoch && completion.runId == value.runId && completion.serverRecordJson == null)
            val previous = this.completion(value.profileId, value.privacyEpoch, completion.runId)
            if (previous == null) insertCompletion(completion) else require(previous.copy(serverRecordJson = null) == completion)
        }
        putState(value)
    }
    @Transaction
    open suspend fun acknowledge(scope: OwnerContext, sent: LocalSegmentOperation, receipt: String?) {
        authorize(scope); requireScope(scope, sent)
        val old = requireNotNull(operation(sent.operationId))
        if (old.state == "acknowledged") { require(old.copy(state = "pending", receiptJson = null) == sent && old.receiptJson == receipt); return }
        require(old == sent && acknowledgeRow(sent.operationId, sent.payloadHash, receipt) == 1)
    }
    /** Root has matched the real checkpoint ACK. Reduce only its exact current local phase marker. */
    @Transaction open suspend fun acknowledgePhaseContinuation(scope:OwnerContext,expected:LocalSegmentState,eventId:String,json:String):Boolean {
        authorize(scope)
        val actual=state(expected.profileId,expected.privacyEpoch,expected.selectionId,expected.phase,expected.purpose,expected.cursorKind)
        if(actual!=expected || expected.ownerRevision!=scope.revision || expected.profileId!=scope.profileId.toString() || expected.privacyEpoch!=scope.privacyEpoch)return false
        val before=kotlinx.serialization.json.Json.parseToJsonElement(requireNotNull(expected.ledgerJson)) as kotlinx.serialization.json.JsonObject
        val after=kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
        require(before.keys==setOf("local_state_version","ledger","continuation") && after.keys==before.keys && before["ledger"]==after["ledger"] && before["local_state_version"]==after["local_state_version"])
        val original=before["continuation"] as kotlinx.serialization.json.JsonObject
        val reduced=after["continuation"] as kotlinx.serialization.json.JsonObject
        require(original["completed_event_id"]==kotlinx.serialization.json.JsonPrimitive(eventId) && original["phase_end_pending"]==kotlinx.serialization.json.JsonPrimitive(true) &&
            reduced==kotlinx.serialization.json.JsonObject(original+("phase_end_pending" to kotlinx.serialization.json.JsonPrimitive(false))))
        val proof=operation(eventId)?:return false
        require(!proof.isolated && proof.kind=="observation" && proof.profileId==scope.profileId.toString() && proof.privacyEpoch==scope.privacyEpoch && proof.ownerRevision==scope.revision)
        putState(expected.copy(ledgerJson=json,localVersion=expected.localVersion+1));return true
    }
    @Transaction
    open suspend fun confirmCompletion(scope: OwnerContext, run: String, actualServerRecord: String) {
        authorize(scope)
        val old = completion(scope.profileId.toString(), scope.privacyEpoch, run) ?: return
        if (old.serverRecordJson != null) require(old.serverRecordJson == actualServerRecord)
        else require(confirmCompletionRow(old.profileId, old.privacyEpoch, run, actualServerRecord) == 1)
    }
}

/** Called only by the central Room3→4 migration; no old table/record is rewritten. */
fun createSegmentPlaybackTables(database: SupportSQLiteDatabase) {
    database.execSQL("CREATE TABLE IF NOT EXISTS segment_state (profileId TEXT NOT NULL, privacyEpoch INTEGER NOT NULL, selectionId TEXT NOT NULL, phase TEXT NOT NULL, purpose TEXT NOT NULL, cursorKind TEXT NOT NULL, ownerRevision INTEGER NOT NULL, sourceJson TEXT NOT NULL, manifestJson TEXT, cursorJson TEXT NOT NULL, runId TEXT, generation INTEGER NOT NULL, ledgerJson TEXT, activeRequestJson TEXT, bindingAcknowledged INTEGER NOT NULL, localVersion INTEGER NOT NULL, PRIMARY KEY(profileId, privacyEpoch, selectionId, phase, purpose, cursorKind))")
    database.execSQL("CREATE TABLE IF NOT EXISTS segment_journal (operationId TEXT NOT NULL PRIMARY KEY, profileId TEXT NOT NULL, privacyEpoch INTEGER NOT NULL, ownerRevision INTEGER NOT NULL, selectionId TEXT NOT NULL, runId TEXT NOT NULL, kind TEXT NOT NULL, payloadJson TEXT NOT NULL, payloadHash TEXT NOT NULL, state TEXT NOT NULL, isolated INTEGER NOT NULL, receiptJson TEXT)")
    database.execSQL("CREATE INDEX IF NOT EXISTS index_segment_journal_profileId_privacyEpoch_isolated ON segment_journal(profileId, privacyEpoch, isolated)")
    database.execSQL("CREATE TABLE IF NOT EXISTS segment_completion (profileId TEXT NOT NULL, privacyEpoch INTEGER NOT NULL, runId TEXT NOT NULL, selectionId TEXT NOT NULL, scriptId TEXT NOT NULL, manifestId TEXT NOT NULL, proofJson TEXT NOT NULL, serverRecordJson TEXT, PRIMARY KEY(profileId, privacyEpoch, runId))")
}
