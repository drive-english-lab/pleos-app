package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.serialization.json.*
import kotlinx.coroutines.withTimeoutOrNull

/** Foreground scoped flush only; reconnect, deletion priority and merge belong to ProfileSyncWorker. */
internal class SessionJournal(private val dao: LearningDao, private val api: LearningApi,
    private val contracts: LearningContractJson) {
    suspend fun flushAll(scope: LearningCallScope): LearningReply<Unit> {
        while (true) {
            val pending = dao.pendingSync(scope.owner.profileId.toString(), scope.owner.privacyEpoch)
            if (pending.isEmpty()) return LearningReply.Stored(scope, Unit)
            when (val reply = flush(scope, pending.map { UUID.fromString(it.recordId) }.toSet())) {
                is LearningReply.Failed -> return reply
                is LearningReply.Stored -> Unit
            }
        }
    }

    suspend fun serverVersion(owner: OwnerContext, recordId: UUID): Long? =
        dao.record(owner.profileId.toString(), owner.privacyEpoch, stableRecordId("server-record:" + recordId).toString())?.version

    /** Coverage observations independently update the checkpoint version. Never predict that CAS
     * from a client position or an observation receipt, whose record_id is the observation itself.
     */
    suspend fun latestCheckpoint(scope: LearningCallScope, id: String): LearningReply<WirePersonalReadCheckpoint> =
        withTimeoutOrNull(8_000) {
            var cursor: String? = null
            repeat(100) {
                when (val page = api.readSyncPage(scope, scope.owner.privacyEpoch, cursor, 50)) {
                    is LearningReply.Failed -> return@withTimeoutOrNull page
                    is LearningReply.Stored -> {
                        val value = page.value
                        if (value.resetRequired) return@withTimeoutOrNull LearningReply.Failed("SESSION_CHANGED", false)
                        val checkpoint = value.records.filterIsInstance<WirePersonalReadCheckpoint>().filter { it.recordId == id }.maxByOrNull { it.version }
                        if (checkpoint != null) {
                            val payload = buildJsonObject { put("record_id", id); put("version", checkpoint.version) }.toString()
                            dao.saveRead(scope.owner, readRecord(scope.owner, stableRecordId("server-record:$id"), "server_record_version", payload, checkpoint.version))
                            return@withTimeoutOrNull LearningReply.Stored(scope, checkpoint)
                        }
                        if (value.records.size < 50 || value.cursor == null || value.cursor == cursor)
                            return@withTimeoutOrNull LearningReply.Failed("CHECKPOINT_NOT_FOUND", false)
                        cursor = value.cursor
                    }
                }
            }
            LearningReply.Failed("SYNC_NOT_READY", true)
        } ?: LearningReply.Failed("SYNC_NOT_READY", true)

    suspend fun queue(owner: OwnerContext, recordId: UUID, event: WirePersonalSyncEvent, preserveInputSnapshot: Boolean = false) {
        val value = learningJson.encodeToJsonElement(WirePersonalSyncEvent.serializer(), event)
        contracts.validate("personal-data.openapi.json", contracts.schema("personal-data.openapi.json", "#/components/schemas/SyncEvent"), value)
        val payload = value.toString()
        dao.queueSync(owner, OutboxRecord(value.jsonObject.getValue("event_id").jsonPrimitive.content,
            owner.profileId.toString(), owner.privacyEpoch, owner.revision, recordId.toString(), payload, LearningJson.hash(payload)),
            if (preserveInputSnapshot) value.jsonObject.getValue("payload").toString() else null)
    }

    /** A failure preserves immutable events. Never flush another owner or unrelated record. */
    suspend fun flush(scope: LearningCallScope, recordIds: Set<UUID>): LearningReply<Unit> {
        if (recordIds.isEmpty()) return LearningReply.Stored(scope, Unit)
        while (true) {
            val rows = dao.pendingForRecords(scope.owner.profileId.toString(), scope.owner.privacyEpoch, recordIds.map(UUID::toString))
            if (rows.isEmpty()) return LearningReply.Stored(scope, Unit)
            if (rows.any { it.ownerRevision != scope.owner.revision }) return LearningReply.Failed("OWNER_CHANGED", false)
            val events = rows.map { row ->
                check(LearningJson.hash(row.payloadJson) == row.payloadHash)
                val value = learningJson.parseToJsonElement(row.payloadJson).jsonObject
                // Schema5 accepted attempts predate the event envelope; retain their exact input bytes.
                val wrapped = if (value["event_id"] == null) buildJsonObject {
                    put("event_id", row.eventId); put("kind", "attempt"); put("expected_record_version", JsonNull); put("payload", value)
                } else value
                learningJson.decodeFromJsonElement(WirePersonalSyncEvent.serializer(), wrapped)
            }
            // Stable for this exact ordered event batch; retry cannot silently change the payload.
            val operation = stableRecordId("sync:" + rows.joinToString(":") { it.eventId + "/" + it.payloadHash })
            val request = WirePersonalSyncRequest(operationId = operation.toString(), profileContext = scope.owner.profileWire(), events = events, schemaVersion = "5")
            when (val reply = api.applySyncBatch(scope, request)) {
                is LearningReply.Failed -> return reply
                is LearningReply.Stored -> {
                    val receipts = reply.value.receipts
                    if (receipts.size != rows.size || receipts.map { it.eventId }.toSet() != rows.map { it.eventId }.toSet() ||
                        receipts.any { receipt -> rows.single { it.eventId == receipt.eventId }.recordId != receipt.recordId })
                        return LearningReply.Failed("INVALID_RESPONSE", false)
                    val payload = learningJson.encodeToJsonElement(WirePersonalSyncReceipt.serializer(), reply.value).toString()
                    dao.acknowledgeSync(scope.owner, rows, readRecord(scope.owner, operation, "sync_receipt", payload, 1))
                }
            }
        }
    }
}

internal fun stableRecordId(name: String): UUID = UUID.nameUUIDFromBytes(("drive-english/schema5/" + name).toByteArray(Charsets.UTF_8))
internal fun OwnerContext.profileWire() = WireCommonProfileContext(profileId.toString(), privacyEpoch)
internal fun OwnerContext.contextWire() = WireCommonClientContext(profileId.toString(), privacyEpoch, revision, presentationToken.toString())
internal fun readRecord(owner: OwnerContext, id: UUID, kind: String, payload: String, version: Long) =
    LearningRecord(owner.profileId.toString(), owner.privacyEpoch, id.toString(), kind, payload, version)
