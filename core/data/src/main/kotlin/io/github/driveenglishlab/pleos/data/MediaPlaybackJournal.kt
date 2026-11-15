package io.github.driveenglishlab.pleos.data

import android.content.Context
import androidx.room.withTransaction
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.serialization.json.*

/** Allocate sequence + immutable observation + local position in one Room transaction.
 * A crash cannot consume a sequence without its original event. Sync ACK and this transaction
 * serialize through the same database. A later command reads the server checkpoint CAS after
 * flushing observations: coverage can independently advance that server version.
 */
class MediaPlaybackJournal internal constructor(private val database: LearningDatabase,
    private val contracts: LearningContractJson) {
    constructor(context: Context, database: LearningDatabase) : this(database, LearningContractJson(context))

    suspend fun position(owner: OwnerContext, checkpointId: String): WirePersonalCheckpointInput? = database.withTransaction {
        val current = database.learning().currentOwner()
        if (current == null || current.profileId != owner.profileId.toString() || current.privacyEpoch != owner.privacyEpoch ||
            current.revision != owner.revision || current.status !in setOf("provisional", "active")) return@withTransaction null
        val record = database.learning().record(owner.profileId.toString(), owner.privacyEpoch, stableRecordId("media-point:$checkpointId").toString()) ?: return@withTransaction null
        require(record.kind == "media_checkpoint_position" && record.payloadSchemaVersion == 5)
        learningJson.decodeFromString(WirePersonalCheckpointInput.serializer(), record.payloadJson)
    }

    suspend fun persist(owner: OwnerContext, checkpointVersion: Long, point: WirePersonalCheckpointInput,
        runId: UUID?, interval: PlayedInterval?) = database.withTransaction {
        require(checkpointVersion >= 1 && (interval == null || point.phase == "main" && runId != null))
        require(point.phase in setOf("main", "followup") && point.currentAssetId != null &&
            point.durationMs != null && point.durationMs > 0 && point.positionMs in 0..point.durationMs)
        val dao = database.learning()
        if (interval != null) {
            require(interval.endMs <= requireNotNull(point.durationMs))
            val id = stableRecordId("media-sequence:$runId")
            val previous = dao.record(owner.profileId.toString(), owner.privacyEpoch, id.toString())
            require(previous == null || previous.kind == "media_run_sequence")
            val sequence = Math.addExact(previous?.version ?: 0, 1)
            val observation = UUID.randomUUID()
            val payload = WirePersonalPlaybackObservationInput(observation.toString(), requireNotNull(runId).toString(), sequence,
                "observed", WirePersonalPlaybackObservationInputRange(interval.startMs, interval.endMs))
            val event = WirePersonalPlaybackObservationEvent(observation.toString(), "playback_observation", JsonNull, payload)
            queue(owner, observation, event)
            dao.saveRead(owner, readRecord(owner, id, "media_run_sequence", buildJsonObject {
                put("run_id", runId.toString()); put("sequence_no", sequence)
            }.toString(), sequence))
        }
        val value = learningJson.encodeToJsonElement(WirePersonalCheckpointInput.serializer(), point)
        contracts.validate("personal-data.openapi.json", contracts.schema("personal-data.openapi.json", "#/components/schemas/CheckpointInput"), value)
        val id = stableRecordId("media-point:${point.checkpointId}")
        val old = dao.record(owner.profileId.toString(), owner.privacyEpoch, id.toString())
        dao.saveRead(owner, readRecord(owner, id, "media_checkpoint_position", value.toString(), (old?.version ?: 0) + 1))
    }

    private suspend fun queue(owner: OwnerContext, record: UUID, event: WirePersonalSyncEvent, snapshot: String? = null) {
        val value = learningJson.encodeToJsonElement(WirePersonalSyncEvent.serializer(), event)
        contracts.validate("personal-data.openapi.json", contracts.schema("personal-data.openapi.json", "#/components/schemas/SyncEvent"), value)
        val payload = value.toString()
        database.learning().queueSync(owner, OutboxRecord(value.jsonObject.getValue("event_id").jsonPrimitive.content,
            owner.profileId.toString(), owner.privacyEpoch, owner.revision, record.toString(), payload, LearningJson.hash(payload)), snapshot)
    }
}
