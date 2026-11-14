package io.github.driveenglishlab.pleos.data

import android.content.Context
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class ListeningFrame(val owner: OwnerContext, val streamId: UUID, val streamVersion: Long,
    val settingsVersion: Long, val resumeVersion: Long, val selection: WirePersonalSelectionRecord?,
    val checkpoint: WirePersonalReadCheckpoint?, val serverHeadSelection: WirePersonalSelectionRecord? = null) {
    val headSelection: WirePersonalSelectionRecord? get() = serverHeadSelection ?: selection
    init {
        require(streamVersion >= 0 && settingsVersion >= 1 && resumeVersion >= 0)
        require((selection == null) == (checkpoint == null))
        require(serverHeadSelection == null || serverHeadSelection.streamId == streamId.toString())
        if (selection != null && checkpoint != null) require(streamVersion >= 1 && selection.streamId == streamId.toString() &&
            checkpoint.payload.streamId == streamId.toString() && checkpoint.payload.selectionId == selection.selectionId &&
            checkpoint.payload.contentRef == selection.contentRef && checkpoint.recordId == checkpoint.payload.checkpointId)
    }
}
sealed interface ListeningResult {
    data class Ready(val frame: ListeningFrame) : ListeningResult
    data class NoCandidate(val reason: String, val frame: ListeningFrame) : ListeningResult
    data class Failed(val operationId: UUID, val code: String) : ListeningResult
    data object Busy : ListeningResult
    data object Superseded : ListeningResult
    data object Closed : ListeningResult
}

/** Foreground transaction client. Data receipts never start audio or mark media as played. */
class ListeningRepository internal constructor(private val api: LearningApi, private val dao: LearningDao,
    contracts: LearningContractJson, private val isCurrent: suspend (OwnerContext) -> Boolean,
    private val transition: ListeningTransitionPort = ListeningTransitionPort { false }) {
    constructor(context: Context, api: LearningApi, database: LearningDatabase, isCurrent: suspend (OwnerContext) -> Boolean,
        transition: ListeningTransitionPort = ListeningTransitionPort { false }) :
        this(api,database.learning(),LearningContractJson(context),isCurrent,transition)
    private val lane = Mutex()
    private val journal = SessionJournal(dao,api,contracts)
    private var frame: ListeningFrame? = null
    private var pending: UUID? = null

    suspend fun snapshot(): ListeningFrame? = lane.withLock { frame }
    suspend fun attach(restored: ListeningFrame) {
        require(isCurrent(restored.owner)); lane.withLock { saveFrame(restored.owner, restored); pending = null; frame = restored }
    }
    suspend fun interrupt() = lane.withLock { pending = null }

    suspend fun createStream(scope: LearningCallScope, settingsVersion: Long, resumeVersion: Long,
        streamId: UUID = UUID.randomUUID(), startedAt: Instant = Instant.now()): ListeningResult {
        if (!isCurrent(scope.owner)) return ListeningResult.Superseded
        val reserved = lane.withLock { if (frame != null || pending != null) false else { pending = streamId; true } }
        if (!reserved) return ListeningResult.Failed(streamId,"SAVE_CURRENT_WORK_FIRST")
        try {
            val event = WirePersonalStreamEvent(streamId.toString(),"stream",JsonNull,WirePersonalStreamInput(streamId.toString(),startedAt.toString()))
            journal.queue(scope.owner,streamId,event)
            val value = ListeningFrame(scope.owner,streamId,0,settingsVersion,resumeVersion,null,null)
            if (!isCurrent(scope.owner)) return ListeningResult.Superseded
            return lane.withLock { if (pending != streamId) ListeningResult.Superseded else { saveFrame(value.owner, value); frame = value; ListeningResult.Ready(value) } }
        } finally { lane.withLock { if (pending == streamId) pending = null } }
    }

    suspend fun closeStream(scope: LearningCallScope, currentPoint: WirePersonalCheckpointInput?, operationId: UUID = UUID.randomUUID()): ListeningResult =
        mutate(scope,operationId) { original ->
            if (original.checkpoint != null) {
                val point = requireNotNull(currentPoint)
                require(point.checkpointId == original.checkpoint.recordId && point.contentRef == original.checkpoint.payload.contentRef &&
                    point.streamId == original.streamId.toString() && point.selectionId == original.selection?.selectionId)
                val saved = freezeAndSavePoint(scope, point)
                if (saved is LearningReply.Failed) return@mutate ListeningResult.Failed(operationId, saved.code)
            } else require(currentPoint == null)
            val synced = journal.flushAll(scope)
            if (synced is LearningReply.Failed) return@mutate ListeningResult.Failed(operationId,synced.code)
            if (!isCurrent(original.owner)) return@mutate ListeningResult.Superseded
            lane.withLock { if (frame != original || pending != operationId) ListeningResult.Superseded else { saveFrame(original.owner, null); frame = null; ListeningResult.Closed } }
        }

    suspend fun updateSettingsVersion(owner: OwnerContext, version: Long) = lane.withLock {
        require(version >= 1 && frame?.owner == owner)
        frame?.copy(settingsVersion = version)?.let { saveFrame(owner, it); frame = it }
    }

    suspend fun next(scope: LearningCallScope, currentPoint: WirePersonalCheckpointInput?,
        selectionKind: String = "automatic_unheard", requestedArticleId: UUID? = null,
        operationId: UUID = UUID.randomUUID()): ListeningResult = mutate(scope,operationId) { original ->
        if (original.checkpoint != null) {
            val point = requireNotNull(currentPoint)
            require(point.checkpointId == original.checkpoint.recordId && point.contentRef == original.checkpoint.payload.contentRef &&
                point.selectionId == original.selection?.selectionId && point.streamId == original.streamId.toString())
            val saved = freezeAndSavePoint(scope, point)
            if (saved is LearningReply.Failed) return@mutate ListeningResult.Failed(operationId, saved.code)
        } else require(currentPoint == null)
        val synced = journal.flushAll(scope)
        if (synced is LearningReply.Failed) return@mutate ListeningResult.Failed(operationId,synced.code)
        val streamVersion = maxOf(original.streamVersion,journal.serverVersion(scope.owner,original.streamId) ?: 0)
        if (streamVersion < 1) return@mutate ListeningResult.Failed(operationId,"STREAM_NOT_ACKNOWLEDGED")
        val request = WirePersonalNextArticleRequest("5",operationId.toString(),scope.owner.profileWire(),original.streamId.toString(),streamVersion,
            original.headSelection?.selectionId,original.settingsVersion,UUID.randomUUID().toString(),selectionKind,requestedArticleId?.toString())
        val frozen = frozenRequest(scope.owner,operationId,"listening_next_request",learningJson.encodeToJsonElement(WirePersonalNextArticleRequest.serializer(),request))
        sendNext(scope,original,operationId,learningJson.decodeFromJsonElement(WirePersonalNextArticleRequest.serializer(),frozen))
    }

    /** Explicit retry reads the original immutable request; it never manufactures a new selection. */
    suspend fun retryNext(scope: LearningCallScope, operationId: UUID): ListeningResult = mutate(scope,operationId) { original ->
        val row = dao.record(scope.owner.profileId.toString(),scope.owner.privacyEpoch,stableRecordId("request:" + operationId).toString())
            ?: return@mutate ListeningResult.Failed(operationId,"NO_SAVED_REQUEST")
        require(row.kind == "listening_next_request" && row.payloadSchemaVersion == 5)
        val request = learningJson.decodeFromString(WirePersonalNextArticleRequest.serializer(),row.payloadJson)
        if (request.streamId != original.streamId.toString() || request.previousSelectionId != original.headSelection?.selectionId)
            return@mutate ListeningResult.Superseded
        sendNext(scope,original,operationId,request)
    }

    private suspend fun sendNext(scope: LearningCallScope, original: ListeningFrame, operationId: UUID,
        request: WirePersonalNextArticleRequest): ListeningResult {
        return when (val reply = api.selectNextArticle(scope,request)) {
            is LearningReply.Failed -> ListeningResult.Failed(operationId,reply.code)
            is LearningReply.Stored -> {
                val value = reply.value
                if (value.streamId != original.streamId.toString() || value.streamVersion < request.expectedStreamVersion)
                    return ListeningResult.Failed(operationId,"INVALID_RESPONSE")
                saveReceipt(scope.owner,operationId,"listening_next_receipt",learningJson.encodeToJsonElement(WirePersonalNextArticleResponse.serializer(),value))
                if (value.status == "no_candidate") {
                    if (!stillCurrent(original,operationId)) return ListeningResult.Superseded
                    val updated = apply(original,original.copy(streamVersion = value.streamVersion),operationId)
                    if (updated is ListeningResult.Ready) ListeningResult.NoCandidate(requireNotNull(value.noCandidateReason),updated.frame) else updated
                } else {
                    val selection = requireNotNull(value.selection); val checkpoint = requireNotNull(value.checkpoint)
                    if (selection.previousSelectionId != request.previousSelectionId || checkpoint.recordId != request.checkpointId ||
                        selection.ordinal != (original.headSelection?.ordinal ?: 0) + 1)
                        return ListeningResult.Failed(operationId,"INVALID_RESPONSE")
                    apply(original,original.copy(streamVersion = value.streamVersion,selection = selection,checkpoint = checkpoint,serverHeadSelection = null),operationId)
                }
            }
        }
    }

    suspend fun startRun(scope: LearningCallScope, operationId: UUID = UUID.randomUUID()): ListeningResult = mutate(scope,operationId) { original ->
        val checkpoint = original.checkpoint ?: return@mutate ListeningResult.Failed(operationId,"NO_CHECKPOINT")
        val previous = savedRequest(scope.owner, operationId, "playback_run_request")
        val request = if (previous != null) learningJson.decodeFromJsonElement(WirePersonalPlaybackRunStart.serializer(), previous) else {
            val synced = journal.flushAll(scope)
            if (synced is LearningReply.Failed) return@mutate ListeningResult.Failed(operationId,synced.code)
            val current = journal.latestCheckpoint(scope, checkpoint.recordId)
            if (current is LearningReply.Failed) return@mutate ListeningResult.Failed(operationId, current.code)
            val latest = (current as LearningReply.Stored).value
            if (latest.payload.contentRef != checkpoint.payload.contentRef || latest.payload.selectionId != checkpoint.payload.selectionId ||
                latest.payload.phase != "main" || latest.payload.activeRunId != checkpoint.payload.activeRunId)
                return@mutate ListeningResult.Failed(operationId, "CHECKPOINT_CHANGED")
            val candidate = WirePersonalPlaybackRunStart("5",operationId.toString(),scope.owner.profileWire(),checkpoint.recordId,latest.version,checkpoint.payload.activeRunId)
            val frozen = frozenRequest(scope.owner,operationId,"playback_run_request",learningJson.encodeToJsonElement(WirePersonalPlaybackRunStart.serializer(),candidate))
            learningJson.decodeFromJsonElement(WirePersonalPlaybackRunStart.serializer(),frozen)
        }
        if (request.checkpointId != checkpoint.recordId || request.profileContext != scope.owner.profileWire())
            return@mutate ListeningResult.Superseded
        when (val reply = api.startPlaybackRun(scope,request)) {
            is LearningReply.Failed -> ListeningResult.Failed(operationId,reply.code)
            is LearningReply.Stored -> {
                val value = reply.value
                if (value.run.checkpointId != checkpoint.recordId || value.checkpoint.recordId != checkpoint.recordId ||
                    value.checkpoint.payload.contentRef != checkpoint.payload.contentRef || value.checkpoint.payload.activeRunId != value.run.runId)
                    return@mutate ListeningResult.Failed(operationId,"INVALID_RESPONSE")
                saveReceipt(scope.owner,operationId,"playback_run_receipt",learningJson.encodeToJsonElement(WirePersonalPlaybackRunReceipt.serializer(),value))
                if (checkpoint.version > value.checkpoint.version || checkpoint.payload.activeRunId !in setOf(request.expectedActiveRunId, value.run.runId))
                    return@mutate ListeningResult.Superseded
                apply(original,original.copy(checkpoint = value.checkpoint),operationId)
            }
        }
    }

    suspend fun observe(scope: LearningCallScope, value: WirePersonalPlaybackObservationInput) {
        val original = snapshot() ?: error("No listening work.")
        require(scope.owner == original.owner && isCurrent(scope.owner) && original.checkpoint?.payload?.activeRunId == value.runId)
        journal.queue(scope.owner,UUID.fromString(value.observationId),WirePersonalPlaybackObservationEvent(value.observationId,"playback_observation",JsonNull,value))
    }

    suspend fun changeFormat(scope: LearningCallScope, source: WirePersonalSelectionRecord, point: WirePersonalCheckpointInput,
        sourceCheckpointVersion: Long, targetMode: ListeningMode, operationId: UUID = UUID.randomUUID()): ListeningResult = mutate(scope,operationId) { original ->
        require(source.streamId == original.streamId.toString() && point.streamId == source.streamId && point.selectionId == source.selectionId &&
            point.contentRef == source.contentRef && sourceCheckpointVersion >= 1)
        val head = requireNotNull(original.headSelection)
        val previous = savedRequest(scope.owner, operationId, "format_change_request")
        val request = if (previous != null) learningJson.decodeFromJsonElement(WirePersonalFormatChangeRequest.serializer(), previous) else {
            val saved = freezeAndSavePoint(scope, point)
            if (saved is LearningReply.Failed) return@mutate ListeningResult.Failed(operationId, saved.code)
            val synced = journal.flushAll(scope)
            if (synced is LearningReply.Failed) return@mutate ListeningResult.Failed(operationId,synced.code)
            val candidate = WirePersonalFormatChangeRequest("5",operationId.toString(),scope.owner.profileWire(),original.streamId.toString(),original.streamVersion,
                head.selectionId,source.selectionId,point.checkpointId,
                maxOf(sourceCheckpointVersion,journal.serverVersion(scope.owner,UUID.fromString(point.checkpointId)) ?: 0),UUID.randomUUID().toString(),LearningJson.wire(targetMode))
            val frozen = frozenRequest(scope.owner,operationId,"format_change_request",learningJson.encodeToJsonElement(WirePersonalFormatChangeRequest.serializer(),candidate))
            learningJson.decodeFromJsonElement(WirePersonalFormatChangeRequest.serializer(),frozen)
        }
        if (request.profileContext != scope.owner.profileWire() || request.streamId != original.streamId.toString() ||
            request.expectedHeadSelectionId != head.selectionId || request.sourceSelectionId != source.selectionId ||
            request.sourceCheckpointId != point.checkpointId || request.targetListeningMode != LearningJson.wire(targetMode))
            return@mutate ListeningResult.Superseded
        when (val reply = api.changeCurrentArticleFormat(scope,request)) {
            is LearningReply.Failed -> ListeningResult.Failed(operationId,reply.code)
            is LearningReply.Stored -> {
                val value = reply.value; val selected = value.selection; val checkpoint = value.checkpoint
                val content = selected.contentRef
                if (value.streamId != original.streamId.toString() || value.streamVersion <= original.streamVersion ||
                    selected.previousSelectionId != head.selectionId || selected.ordinal != head.ordinal + 1 || selected.selectionReason != "explicit_format_change" ||
                    selected.formatChangeSource?.selectionId != source.selectionId || selected.formatChangeSource.checkpointId != point.checkpointId ||
                    content.articleId != source.contentRef.articleId || content.bundleId != source.contentRef.bundleId || content.sourceSnapshotId != source.contentRef.sourceSnapshotId ||
                    content.listeningMode != LearningJson.wire(targetMode) || selected.settingsSnapshot.followupPolicy != source.settingsSnapshot.followupPolicy ||
                    checkpoint.recordId != request.checkpointId || checkpoint.payload.positionMs != 0L || checkpoint.payload.phase != "main" ||
                    checkpoint.payload.activeRunId != null || checkpoint.payload.mainCoverageStatus != "unknown")
                    return@mutate ListeningResult.Failed(operationId,"INVALID_RESPONSE")
                saveReceipt(scope.owner,operationId,"format_change_receipt",learningJson.encodeToJsonElement(WirePersonalFormatChangeResponse.serializer(),value))
                apply(original,original.copy(streamVersion = value.streamVersion,selection = selected,checkpoint = checkpoint,serverHeadSelection = null),operationId)
            }
        }
    }

    /** An article-local main → followup → offer transition. It never selects another article. */
    suspend fun advanceAudioPhase(scope: LearningCallScope, followup: PublishedScript?, playIntent: Boolean,
        operationId: UUID = UUID.randomUUID()): ListeningResult = mutate(scope, operationId) { original ->
        val checkpoint = original.checkpoint ?: return@mutate ListeningResult.Failed(operationId, "NO_CHECKPOINT")
        if (followup != null && checkpoint.payload.phase != "main") return@mutate ListeningResult.Failed(operationId, "INVALID_PHASE")
        if (followup != null && (followup.bundleId.toString() != checkpoint.payload.contentRef.bundleId ||
                followup.baseScriptId?.toString() != checkpoint.payload.contentRef.listeningScriptId))
            return@mutate ListeningResult.Failed(operationId, "CONTENT_UNAVAILABLE")
        val previous = savedRequest(scope.owner, operationId, "audio_phase_request")
        val frozen = previous ?: run {
            val flushed = journal.flushAll(scope)
            if (flushed is LearningReply.Failed) return@mutate ListeningResult.Failed(operationId, flushed.code)
            val current = journal.latestCheckpoint(scope, checkpoint.recordId)
            if (current is LearningReply.Failed) return@mutate ListeningResult.Failed(operationId, current.code)
            val live = (current as LearningReply.Stored).value
            if (live.payload.contentRef != checkpoint.payload.contentRef || live.payload.selectionId != checkpoint.payload.selectionId ||
                live.payload.phase != checkpoint.payload.phase || live.payload.activeRunId != checkpoint.payload.activeRunId)
                return@mutate ListeningResult.Failed(operationId, "CHECKPOINT_CHANGED")
            val point = WirePersonalCheckpointInput(checkpoint.recordId, checkpoint.payload.contentRef,
                if (followup == null) "article_offer" else "followup", followup?.audioAssetId?.toString(), 0,
                followup?.durationMs, playIntent && followup != null, "none", original.streamId.toString(), requireNotNull(original.selection).selectionId)
            val event = WirePersonalCheckpointEvent(operationId.toString(), "checkpoint", live.version, point)
            frozenRequest(scope.owner, operationId, "audio_phase_request", learningJson.encodeToJsonElement(WirePersonalSyncEvent.serializer(), event))
        }
        val event = learningJson.decodeFromJsonElement(WirePersonalSyncEvent.serializer(), frozen) as WirePersonalCheckpointEvent
        if (event.payload.checkpointId != checkpoint.recordId || event.payload.contentRef != checkpoint.payload.contentRef)
            return@mutate ListeningResult.Superseded
        journal.queue(scope.owner, UUID.fromString(checkpoint.recordId), event, preserveInputSnapshot = true)
        val synced = journal.flushAll(scope)
        if (synced is LearningReply.Failed) return@mutate ListeningResult.Failed(operationId, synced.code)
        val live = journal.latestCheckpoint(scope, checkpoint.recordId)
        if (live is LearningReply.Failed) return@mutate ListeningResult.Failed(operationId, live.code)
        val latest = (live as LearningReply.Stored).value
        if (latest.payload.phase != event.payload.phase || latest.payload.currentAssetId != event.payload.currentAssetId)
            return@mutate ListeningResult.Failed(operationId, "CHECKPOINT_CHANGED")
        apply(original, original.copy(checkpoint = latest), operationId)
    }

    suspend fun selectResume(scope: LearningCallScope, operationId: UUID = UUID.randomUUID()): ListeningResult = mutate(scope,operationId) { original ->
        val checkpoint = original.checkpoint ?: return@mutate ListeningResult.Failed(operationId,"NO_CHECKPOINT")
        val synced = journal.flushAll(scope)
        if (synced is LearningReply.Failed) return@mutate ListeningResult.Failed(operationId,synced.code)
        val request = WirePersonalResumeSelection("5",operationId.toString(),scope.owner.profileWire(),original.resumeVersion,WirePersonalResumeRef("listening",checkpoint.recordId))
        val frozen = frozenRequest(scope.owner,operationId,"resume_request",learningJson.encodeToJsonElement(WirePersonalResumeSelection.serializer(),request))
        when (val reply = api.selectActiveResume(scope,learningJson.decodeFromJsonElement(WirePersonalResumeSelection.serializer(),frozen))) {
            is LearningReply.Failed -> ListeningResult.Failed(operationId,reply.code)
            is LearningReply.Stored -> {
                if (reply.value.activeResume != request.activeResume || reply.value.resumeVersion <= original.resumeVersion)
                    return@mutate ListeningResult.Failed(operationId,"INVALID_RESPONSE")
                saveReceipt(scope.owner,operationId,"resume_receipt",learningJson.encodeToJsonElement(WirePersonalProfileView.serializer(),reply.value))
                apply(original,original.copy(resumeVersion = reply.value.resumeVersion),operationId)
            }
        }
    }

    /** Rebind stored business data to the same verified local owner; never restore live grants. */
    suspend fun restoreCached(scope: LearningCallScope): ListeningResult {
        if (!isCurrent(scope.owner)) return ListeningResult.Superseded
        val row = dao.record(scope.owner.profileId.toString(), scope.owner.privacyEpoch, stableRecordId("listening-frame").toString())
            ?: return ListeningResult.Closed
        if (row.kind != "listening_frame" || row.payloadSchemaVersion != 5) return ListeningResult.Failed(UUID.randomUUID(), "INVALID_LOCAL_STATE")
        if (row.payloadJson == "null") return ListeningResult.Closed
        val value = learningJson.parseToJsonElement(row.payloadJson).jsonObject
        if (value.getValue("owner_revision").jsonPrimitive.long != scope.owner.revision) return ListeningResult.Closed
        fun <T> decoded(name: String, serializer: kotlinx.serialization.KSerializer<T>): T? = value.getValue(name).let {
            if (it is JsonNull) null else learningJson.decodeFromJsonElement(serializer, it)
        }
        val restored = ListeningFrame(scope.owner, UUID.fromString(value.getValue("stream_id").jsonPrimitive.content),
            value.getValue("stream_version").jsonPrimitive.long, value.getValue("settings_version").jsonPrimitive.long,
            value.getValue("resume_version").jsonPrimitive.long, decoded("selection", WirePersonalSelectionRecord.serializer()),
            decoded("checkpoint", WirePersonalReadCheckpoint.serializer()), decoded("server_head_selection", WirePersonalSelectionRecord.serializer()))
        attach(restored)
        return ListeningResult.Ready(restored)
    }

    private suspend fun saveFrame(owner: OwnerContext, value: ListeningFrame?) {
        val id = stableRecordId("listening-frame")
        val old = dao.record(owner.profileId.toString(), owner.privacyEpoch, id.toString())
        val json = value?.let { frame -> buildJsonObject {
            put("owner_revision", owner.revision); put("stream_id", frame.streamId.toString()); put("stream_version", frame.streamVersion)
            put("settings_version", frame.settingsVersion); put("resume_version", frame.resumeVersion)
            put("selection", frame.selection?.let { learningJson.encodeToJsonElement(WirePersonalSelectionRecord.serializer(), it) } ?: JsonNull)
            put("checkpoint", frame.checkpoint?.let { learningJson.encodeToJsonElement(WirePersonalReadCheckpoint.serializer(), it) } ?: JsonNull)
            put("server_head_selection", frame.serverHeadSelection?.let { learningJson.encodeToJsonElement(WirePersonalSelectionRecord.serializer(), it) } ?: JsonNull)
        } } ?: JsonNull
        dao.saveRead(owner, readRecord(owner, id, "listening_frame", json.toString(), (old?.version ?: 0) + 1))
    }

    private suspend fun freezeAndSavePoint(scope: LearningCallScope, point: WirePersonalCheckpointInput): LearningReply<Unit> {
        if (!transition.freezeCurrent(scope.owner)) return LearningReply.Failed("TRANSITION_NOT_READY", false)
        val flushed = journal.flushAll(scope)
        if (flushed is LearningReply.Failed) return flushed
        val current = journal.latestCheckpoint(scope, point.checkpointId)
        if (current is LearningReply.Failed) return current
        val record = (current as LearningReply.Stored).value
        if (record.payload.contentRef != point.contentRef || record.payload.selectionId != point.selectionId || record.payload.streamId != point.streamId)
            return LearningReply.Failed("CHECKPOINT_CHANGED", false)
        if (!isCurrent(scope.owner)) return LearningReply.Failed("SESSION_CHANGED", false)
        // FREEZE drains the media writer. Prefer its final durable cursor over the UI's
        // earlier snapshot, but never import a cursor from another phase or asset.
        val local = dao.record(scope.owner.profileId.toString(), scope.owner.privacyEpoch,
            stableRecordId("media-point:${point.checkpointId}").toString())
        val frozen = local?.takeIf { it.kind == "media_checkpoint_position" && it.payloadSchemaVersion == 5 }
            ?.let { learningJson.decodeFromString(WirePersonalCheckpointInput.serializer(), it.payloadJson) }
            ?.takeIf { it.checkpointId == point.checkpointId && it.contentRef == point.contentRef &&
                it.streamId == point.streamId && it.selectionId == point.selectionId && it.phase == point.phase &&
                it.currentAssetId == point.currentAssetId && it.durationMs == point.durationMs }
        queueCheckpoint(scope.owner, point.copy(positionMs = frozen?.positionMs ?: point.positionMs), record.version)
        return LearningReply.Stored(scope, Unit)
    }

    private suspend fun queueCheckpoint(owner: OwnerContext, point: WirePersonalCheckpointInput, version: Long) {
        val value = learningJson.encodeToJsonElement(WirePersonalCheckpointInput.serializer(),point)
        val eventId = stableRecordId("checkpoint:" + point.checkpointId + ":" + version + ":" + LearningJson.hash(value.toString()))
        journal.queue(owner,UUID.fromString(point.checkpointId),WirePersonalCheckpointEvent(eventId.toString(),"checkpoint",version,point),preserveInputSnapshot = true)
    }
    private suspend fun savedRequest(owner: OwnerContext, operationId: UUID, kind: String): JsonElement? {
        val old = dao.record(owner.profileId.toString(), owner.privacyEpoch, stableRecordId("request:$operationId").toString()) ?: return null
        require(old.kind == kind && old.payloadSchemaVersion == 5)
        return learningJson.parseToJsonElement(old.payloadJson)
    }
    private suspend fun frozenRequest(owner: OwnerContext, operationId: UUID, kind: String, value: JsonElement): JsonElement {
        val id = stableRecordId("request:" + operationId)
        val old = dao.record(owner.profileId.toString(),owner.privacyEpoch,id.toString())
        if (old != null) { require(old.kind == kind && old.payloadSchemaVersion == 5); return learningJson.parseToJsonElement(old.payloadJson) }
        dao.saveRead(owner,readRecord(owner,id,kind,value.toString(),1)); return value
    }
    private suspend fun saveReceipt(owner: OwnerContext, id: UUID, kind: String, value: JsonElement) =
        dao.saveRead(owner,readRecord(owner,stableRecordId("receipt:" + id),kind,value.toString(),1))
    private suspend fun stillCurrent(original: ListeningFrame, operationId: UUID): Boolean = isCurrent(original.owner) && lane.withLock { frame == original && pending == operationId }
    private suspend fun apply(original: ListeningFrame, value: ListeningFrame, operationId: UUID): ListeningResult {
        if (!isCurrent(original.owner)) return ListeningResult.Superseded
        return lane.withLock { if (frame != original || pending != operationId) ListeningResult.Superseded else { saveFrame(value.owner, value); frame = value; ListeningResult.Ready(value) } }
    }
    private suspend fun mutate(scope: LearningCallScope, id: UUID, block: suspend (ListeningFrame) -> ListeningResult): ListeningResult {
        if (!isCurrent(scope.owner)) return ListeningResult.Superseded
        val original = lane.withLock {
            if (pending != null) return ListeningResult.Busy
            val value = frame ?: return ListeningResult.Superseded
            if (value.owner != scope.owner) return ListeningResult.Superseded
            pending = id; value
        }
        try { return block(original) }
        catch (error: CancellationException) { throw error }
        finally { lane.withLock { if (pending == id) pending = null } }
    }
}
