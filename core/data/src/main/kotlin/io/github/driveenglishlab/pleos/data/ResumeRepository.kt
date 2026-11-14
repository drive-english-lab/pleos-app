package io.github.driveenglishlab.pleos.data

import android.content.Context
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/** Read all original records without selecting a pointer or starting any real-time effect. */
data class ProfileReadSnapshot(val profile: WirePersonalProfileView, val records: List<WirePersonalReadRecord>, val cursor: String)

class ResumeRepository(private val api: LearningApi, private val database: LearningDatabase) {
    suspend fun snapshot(scope: LearningCallScope): LearningReply<ProfileReadSnapshot> = withTimeoutOrNull(8_000) {
        var cursor: String? = null
        val latest = linkedMapOf<Pair<String, String>, WirePersonalReadRecord>()
        repeat(100) {
            when (val page = api.readSyncPage(scope, scope.owner.privacyEpoch, cursor, 50)) {
                is LearningReply.Failed -> return@withTimeoutOrNull page
                is LearningReply.Stored -> {
                    val value = page.value
                    if (value.resetRequired) return@withTimeoutOrNull LearningReply.Failed("SESSION_CHANGED", false)
                    for (record in value.records) {
                        val encoded = learningJson.encodeToJsonElement(WirePersonalReadRecord.serializer(), record).jsonObject
                        val id = encoded.getValue("record_id").jsonPrimitive.content
                        val kind = encoded.getValue("kind").jsonPrimitive.content
                        val version = encoded.getValue("version").jsonPrimitive.long
                        val previous = latest[kind to id]?.let { learningJson.encodeToJsonElement(WirePersonalReadRecord.serializer(), it).jsonObject.getValue("version").jsonPrimitive.long } ?: 0
                        if (version >= previous) {
                            latest[kind to id] = record
                            database.learning().saveRead(scope.owner, readRecord(scope.owner, stableRecordId("sync-read:$kind:$id"), "sync_read_$kind", encoded.toString(), version))
                        }
                    }
                    val next = value.cursor ?: return@withTimeoutOrNull LearningReply.Failed("SYNC_NOT_READY", true)
                    if (value.records.size < 50 || next == cursor) return@withTimeoutOrNull LearningReply.Stored(scope, ProfileReadSnapshot(value.profile, latest.values.toList(), next))
                    cursor = next
                }
            }
        }
        LearningReply.Failed("SYNC_NOT_READY", true)
    } ?: LearningReply.Failed("SYNC_NOT_READY", true)

    /** References must already be acknowledged. Expected resume version is from this read. */
    suspend fun select(scope: LearningCallScope, snapshot: ProfileReadSnapshot, kind: String, recordId: UUID,
        operation: UUID = UUID.randomUUID()): LearningReply<WirePersonalProfileView> {
        require(kind in setOf("practice", "listening"))
        val exists = when(kind) {
            "practice" -> snapshot.records.filterIsInstance<WirePersonalReadLesson>().any { it.recordId == recordId.toString() &&
                (it.payload.state.phase != "completed" || it.payload.state.pendingFeedbackAttemptId != null) }
            else -> snapshot.records.filterIsInstance<WirePersonalReadCheckpoint>().any { it.recordId == recordId.toString() }
        }
        if (!exists || snapshot.profile.profileContext != scope.owner.profileWire()) return LearningReply.Failed("REFERENCE_NOT_ACKNOWLEDGED", false)
        return api.selectActiveResume(scope, WirePersonalResumeSelection("5", operation.toString(), scope.owner.profileWire(),
            snapshot.profile.resumeVersion, WirePersonalResumeRef(kind, recordId.toString())))
    }
}
