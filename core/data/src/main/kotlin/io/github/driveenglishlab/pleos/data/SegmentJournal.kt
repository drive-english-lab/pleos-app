package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Single ordered lane for immutable cursor intents, run-start CAS and actual SDK observations.
 * Every ACK is current-owner checked again. A retry sends the original operation/request bytes.
 */
class SegmentJournal internal constructor(private val dao: SegmentPlaybackDao, private val codecs: IncrementalContractJson,
    private val port: SegmentCheckpointPort, private val current: suspend (OwnerContext) -> Boolean,
    private val exactSource: (SegmentProgram) -> String) : SegmentJournalPort {
    private val lane = Mutex()

    override suspend fun save(snapshot: SegmentPlaybackSnapshot) = lane.withLock {
        require(current(snapshot.program.owner))
        persist(snapshot)
    }

    override suspend fun observed(event: SegmentSpeechEvent, snapshot: SegmentPlaybackSnapshot, recordEnglishCompletion: Boolean) = lane.withLock {
        val owner = snapshot.program.owner
        require(current(owner) && event.run.owner == owner && event.run.runId == snapshot.runId && event.run.selectionId == snapshot.program.selectionId &&
            event.run.source == snapshot.program.source && event.run.scriptId == snapshot.program.manifest.scriptId &&
            event.run.manifestId == snapshot.program.manifest.manifestId && event.run.manifestHash == snapshot.program.manifest.manifestHash)
        val segment = snapshot.program.manifest.segments[event.run.segmentIndex]
        require(segment.segmentId == event.run.segmentId && segment.textHash == event.run.segmentTextHash)
        if(event.kind==SegmentSpeechEventKind.COMPLETED)snapshot.continuation?.let{require(it.completedEventId==event.eventId && it.completedRun==event.run)}
        val original = dao.operation(event.eventId.toString())
        // The durable event identity is exact. Never assign a new sequence number to a replay.
        if (original != null) {
            require(original.kind == "observation" && original.profileId == owner.profileId.toString() && original.privacyEpoch == owner.privacyEpoch &&
                original.ownerRevision == owner.revision && !original.isolated)
            val previous = codecs.decodeSource6("SegmentObservationInput6", original.payloadJson, WireSource6SegmentObservationInput6.serializer())
            require(previous == observation(event, source(snapshot.program), previous.sequenceNo))
            return@withLock
        }
        val sequence = dao.observationCount(owner.profileId.toString(), owner.privacyEpoch, event.run.runId.toString()) + 1
        val value = observation(event, source(snapshot.program), sequence)
        val payload = codecs.encode("source6", "SegmentObservationInput6", value, WireSource6SegmentObservationInput6.serializer())
        val row = operation(snapshot, event.eventId, "observation", payload)
        val completion = if (recordEnglishCompletion) {
            require(event.kind == SegmentSpeechEventKind.COMPLETED && snapshot.ledger.complete && snapshot.ledger.englishMainCompletionRecorded)
            LocalSegmentCompletion(owner.profileId.toString(), owner.privacyEpoch, snapshot.runId.toString(), snapshot.program.selectionId.toString(),
                snapshot.program.manifest.scriptId.toString(), snapshot.program.manifest.manifestId.toString(), encodeSegmentLedger(snapshot.ledger), null)
        } else null
        // Observation precedes its next cursor; remote playback cannot skip the missing event.
        persist(snapshot, listOf(row), completion)
    }

    override suspend fun prepareOutput(snapshot: SegmentPlaybackSnapshot): SegmentRun? = lane.withLock {
        val desired = snapshot.activeRequest ?: return@withLock null
        val owner = snapshot.program.owner
        if (!current(owner)) return@withLock null
        // Physical speech never starts behind an unacknowledged interruption/observation/cursor.
        if (!flushLocked(owner)) return@withLock null
        val operationId = desired.requestId.toString()
        var pending = dao.operation(operationId)
        if (pending == null) {
            val checkpointRow = dao.checkpoint(owner.profileId.toString(), owner.privacyEpoch, snapshot.program.selectionId.toString()) ?: return@withLock null
            val checkpoint = codecs.decodeSource6("PersonalReadCheckpoint", requireNotNull(checkpointRow.receiptJson), WireSource6PersonalReadCheckpoint.serializer())
            validateCheckpoint(snapshot, checkpoint)
            val request = WireSource6PersonalPlaybackRunStart("6", operationId,
                WireSource6CommonProfileContext(owner.profileId.toString(), owner.privacyEpoch), checkpoint.payload.checkpointId,
                checkpoint.version, checkpoint.payload.activeRunId)
            val payload = codecs.encode("source6", "PersonalPlaybackRunStart", request, WireSource6PersonalPlaybackRunStart.serializer())
            pending = operation(snapshot, desired.requestId, "start", payload)
            dao.enqueue(owner, pending)
        }
        require(pending.kind == "start" && pending.ownerRevision == owner.revision && pending.profileId == owner.profileId.toString() &&
            pending.privacyEpoch == owner.privacyEpoch && !pending.isolated)
        val receipt = if (pending.state == "acknowledged") {
            codecs.decodeSource6("PersonalPlaybackRunReceipt", requireNotNull(pending.receiptJson), WireSource6PersonalPlaybackRunReceipt.serializer())
        } else {
            val request = codecs.decodeSource6("PersonalPlaybackRunStart", pending.payloadJson, WireSource6PersonalPlaybackRunStart.serializer())
            val received = port.start(owner, request) ?: return@withLock null
            if (!current(owner)) return@withLock null
            val receiptJson = codecs.encode("source6", "PersonalPlaybackRunReceipt", received, WireSource6PersonalPlaybackRunReceipt.serializer())
            validateReceipt(snapshot, request, received)
            dao.acknowledge(owner, pending, receiptJson)
            received
        }
        if (!current(owner)) return@withLock null
        val request = codecs.decodeSource6("PersonalPlaybackRunStart", pending.payloadJson, WireSource6PersonalPlaybackRunStart.serializer())
        validateReceipt(snapshot, request, receipt)
    }

    /** Retry delivery only, never begin physical output or invent a completion. */
    suspend fun flush(owner: OwnerContext): Boolean = lane.withLock { flushLocked(owner) }

    private suspend fun flushLocked(owner: OwnerContext): Boolean {
        if (!current(owner)) return false
        for (row in dao.pending(owner.profileId.toString(), owner.privacyEpoch)) {
            if (!current(owner) || row.ownerRevision != owner.revision) return false
            when (row.kind) {
                "cursor" -> {
                    val input = Json.parseToJsonElement(row.payloadJson).jsonObject
                    val selection = UUID.fromString(input.getValue("selection_id").jsonPrimitive.content)
                    val source = codecs.decodeSource6Content(input.getValue("source_ref").toString())
                    val cursor = codecs.decodeSource6("PlaybackCursor6", input.getValue("cursor").toString(), WireSource6PlaybackCursor6.serializer())
                    val purpose = SegmentPurpose.valueOf(input.getValue("purpose").jsonPrimitive.content.uppercase())
                    val checkpoint = port.cursor(SegmentCursorWrite(UUID.fromString(row.operationId), owner, selection, source, cursor, purpose)) ?: return false
                    if (!current(owner)) return false
                    require(checkpoint.kind == "checkpoint" && checkpoint.version >= 1 && checkpoint.recordId == checkpoint.payload.checkpointId &&
                        checkpoint.payload.selectionId == selection.toString() && checkpoint.payload.contentRef == source && checkpoint.payload.cursor == cursor)
                    val receipt = codecs.encode("source6", "PersonalReadCheckpoint", checkpoint, WireSource6PersonalReadCheckpoint.serializer())
                    dao.acknowledge(owner, row, receipt)
                }
                "observation" -> {
                    val value = codecs.decodeSource6("SegmentObservationInput6", row.payloadJson, WireSource6SegmentObservationInput6.serializer())
                    require(value.observationId == row.operationId && value.eventId == row.operationId)
                    if (!port.observation(owner, UUID.fromString(row.operationId), value) || !current(owner)) return false
                    dao.acknowledge(owner, row, null)
                }
                // A pending start is resolved only by its original explicit request. Later writes
                // must wait for this uncertain result instead of rebasing its checkpoint CAS.
                "start" -> {
                    val request = codecs.decodeSource6("PersonalPlaybackRunStart", row.payloadJson, WireSource6PersonalPlaybackRunStart.serializer())
                    val result = port.start(owner, request) ?: return false
                    if (!current(owner)) return false
                    require(result.operationId == row.operationId && result.profileContext == request.profileContext)
                    val receipt = codecs.encode("source6", "PersonalPlaybackRunReceipt", result, WireSource6PersonalPlaybackRunReceipt.serializer())
                    dao.acknowledge(owner, row, receipt)
                }
                else -> error("Unknown segment journal entry.")
            }
        }
        return true
    }

    private fun validateReceipt(snapshot: SegmentPlaybackSnapshot, request: WireSource6PersonalPlaybackRunStart,
        receipt: WireSource6PersonalPlaybackRunReceipt): SegmentRun {
        require(receipt.schemaVersion == "6" && receipt.operationId == request.operationId && receipt.profileContext == request.profileContext)
        val wire = receipt.run as? WireSource6SegmentRunRecord6 ?: error("No segment run binding.")
        val program = snapshot.program
        val wanted = requireNotNull(snapshot.activeRequest)
        val checkpoint = receipt.checkpoint
        require(checkpoint.payload.checkpointId == request.checkpointId && checkpoint.recordId == request.checkpointId && checkpoint.version > request.expectedCheckpointVersion &&
            checkpoint.payload.activeRunId == wire.runId && checkpoint.payload.selectionId == program.selectionId.toString() && checkpoint.payload.contentRef == source(program))
        require(wire.kind == "segment" && wire.profileId == program.owner.profileId.toString() && wire.privacyEpoch == program.owner.privacyEpoch &&
            wire.sourceRef == source(program) && wire.phase == program.phase.name.lowercase() && wire.purpose == program.purpose.name.lowercase() &&
            wire.scriptId == program.manifest.scriptId.toString() && wire.scriptHash == program.manifest.scriptHash &&
            wire.manifestId == program.manifest.manifestId.toString() && wire.manifestHash == program.manifest.manifestHash &&
            wire.segmentsVersion == program.manifest.segmentsVersion.toLong() && wire.currentSegmentId == wanted.segmentId.toString() && wire.currentSegmentIndex == wanted.segmentIndex.toLong())
        val checkpointCursor = checkpoint.payload.cursor as? WireSource6SegmentCursor6 ?: error("No exact segment cursor.")
        require(checkpointCursor.toSegmentCursor() == snapshot.cursor)
        return SegmentRun(UUID.fromString(wire.runId), wire.runGeneration, UUID.fromString(wire.requestId), program.owner, program.selectionId,
            program.source, program.phase, program.purpose, UUID.fromString(wire.scriptId), wire.scriptHash, UUID.fromString(wire.manifestId),
            wire.segmentsVersion.segmentInt(), wire.manifestHash, UUID.fromString(wire.currentSegmentId), wire.currentSegmentIndex.segmentInt(),
            program.manifest.segments[wanted.segmentIndex].textHash, UUID.fromString(wire.leaseId))
    }

    private fun validateCheckpoint(snapshot: SegmentPlaybackSnapshot, checkpoint: WireSource6PersonalReadCheckpoint) {
        require(checkpoint.kind == "checkpoint" && checkpoint.version >= 1 && checkpoint.payload.selectionId == snapshot.program.selectionId.toString() &&
            checkpoint.payload.contentRef == source(snapshot.program) && checkpoint.payload.cursor == snapshot.cursor.toWireSegmentCursor())
    }

    private suspend fun persist(snapshot: SegmentPlaybackSnapshot, first: List<LocalSegmentOperation> = emptyList(), completion: LocalSegmentCompletion? = null) {
        val program = snapshot.program
        val owner = program.owner
        val old = dao.state(owner.profileId.toString(), owner.privacyEpoch, program.selectionId.toString(), program.phase.name.lowercase(), program.purpose.name.lowercase(), "segment")
        val sourceText = exactSource(program)
        val cursorText = codecs.encode("source6", "PlaybackCursor6", snapshot.cursor.toWireSegmentCursor(), WireSource6PlaybackCursor6.serializer())
        val manifestText = codecs.encode("source6", "SegmentManifest", program.manifest.toWireSegmentManifest(), WireSource6SegmentManifest.serializer())
        val row = LocalSegmentState(owner.profileId.toString(), owner.privacyEpoch, program.selectionId.toString(), program.phase.name.lowercase(), program.purpose.name.lowercase(),
            "segment", owner.revision, sourceText, manifestText, cursorText, snapshot.runId.toString(), snapshot.generation, encodeLocalSegmentState(snapshot.ledger,snapshot.continuation),
            snapshot.activeRequest?.let(::encodeSegmentRun), snapshot.bindingAcknowledged, (old?.localVersion ?: 0) + 1)
        val writes = first.toMutableList()
        if (old == null || old.cursorJson != cursorText || canonicalSource(old.sourceJson) != canonicalSource(sourceText) || old.ownerRevision != owner.revision) {
            val intent = buildJsonObject {
                put("selection_id", program.selectionId.toString()); put("source_ref", Json.parseToJsonElement(sourceText))
                put("cursor", Json.parseToJsonElement(cursorText)); put("purpose", program.purpose.name.lowercase())
            }.toString()
            writes += operation(snapshot, UUID.randomUUID(), "cursor", intent)
        }
        dao.commit(owner, row, writes, completion)
    }

    override suspend fun phaseAcknowledged(snapshot:SegmentPlaybackSnapshot,eventId:UUID):Boolean=lane.withLock {
        require(current(snapshot.program.owner))
        val row=dao.state(snapshot.program.owner.profileId.toString(),snapshot.program.owner.privacyEpoch,snapshot.program.selectionId.toString(),
            snapshot.program.phase.name.lowercase(),snapshot.program.purpose.name.lowercase(),"segment")?:return@withLock false
        if(row.ownerRevision!=snapshot.program.owner.revision || canonicalSource(row.sourceJson)!=canonicalSource(exactSource(snapshot.program)))return@withLock false
        val decoded=decodeLocalSegmentState(requireNotNull(row.ledgerJson),snapshot.program)
        val pending=decoded.second?.takeIf{it.completedEventId==eventId && it.phaseEndPending && it.completedRun==snapshot.continuation?.completedRun}?:return@withLock false
        dao.acknowledgePhaseContinuation(snapshot.program.owner,row,eventId.toString(),encodeLocalSegmentState(decoded.first,pending.copy(phaseEndPending=false)))
    }

    private fun source(program: SegmentProgram) = codecs.decodeSource6Content(exactSource(program))
    private fun operation(snapshot: SegmentPlaybackSnapshot, id: UUID, kind: String, payload: String): LocalSegmentOperation {
        val owner = snapshot.program.owner
        return LocalSegmentOperation(id.toString(), owner.profileId.toString(), owner.privacyEpoch, owner.revision,
            snapshot.program.selectionId.toString(), snapshot.runId.toString(), kind, payload, LearningJson.hash(payload), "pending", false, null)
    }
    private fun observation(event: SegmentSpeechEvent, source: WireSource6ContentRef6, sequence: Long) = event.run.let {
        WireSource6SegmentObservationInput6("segment", event.eventId.toString(), it.runId.toString(), sequence, event.eventId.toString(), it.generation,
            it.requestId.toString(), source, it.phase.name.lowercase(), it.scriptId.toString(), it.scriptHash, it.manifestId.toString(), it.manifestVersion.toLong(),
            it.manifestHash, it.segmentId.toString(), it.segmentIndex.toLong(), it.purpose.name.lowercase(), event.kind.name.lowercase(), event.evidenceRef)
    }
}

internal fun encodeSegmentRun(run: SegmentRun): String = buildJsonObject {
    put("run_id", run.runId.toString()); put("generation", run.generation); put("request_id", run.requestId.toString()); put("lease_id", run.outputLeaseId.toString())
    put("segment_id", run.segmentId.toString()); put("segment_index", run.segmentIndex); put("text_hash", run.segmentTextHash)
}.toString()

internal fun encodeSegmentLedger(value: SequentialCompletion): String = buildJsonObject {
    put("profile_id", value.profile.profileId.toString()); put("privacy_epoch", value.profile.privacyEpoch)
    put("selection_id", value.selectionId.toString()); put("run_id", value.runId.toString()); put("source_kind", value.source.kind); put("source_hash", value.source.canonicalHash)
    put("script_id", value.scriptId.toString()); put("script_hash", value.scriptHash); put("manifest_id", value.manifestId.toString()); put("manifest_version", value.manifestVersion); put("manifest_hash", value.manifestHash)
    put("language", value.language.name); put("phase", value.phase.name); put("purpose", value.purpose.name); put("segment_count", value.segmentCount)
    put("sequential_origin", value.sequentialOrigin); put("next_required_index", value.nextRequiredIndex); put("broken", value.broken); put("english_main_completion_recorded", value.englishMainCompletionRecorded)
    put("completed", JsonArray(value.completed.map { buildJsonObject {
        put("event_id", it.eventId.toString()); put("request_id", it.requestId.toString()); put("generation", it.generation)
        put("segment_id", it.segmentId.toString()); put("index", it.index); put("evidence_ref", it.evidenceRef)
    } }))
}.toString()

private fun decodeBareSegmentLedger(source: String, program: SegmentProgram): SequentialCompletion {
    val value = Json.parseToJsonElement(source).jsonObject
    fun text(key: String) = value.getValue(key).jsonPrimitive.content
    val result = SequentialCompletion(ProfileContext(UUID.fromString(text("profile_id")), value.getValue("privacy_epoch").jsonPrimitive.long),
        UUID.fromString(text("selection_id")), UUID.fromString(text("run_id")), SegmentSourceKey(text("source_kind"), text("source_hash")),
        UUID.fromString(text("script_id")), text("script_hash"), UUID.fromString(text("manifest_id")), value.getValue("manifest_version").jsonPrimitive.int,
        text("manifest_hash"), SegmentLanguage.valueOf(text("language")), SegmentPhase.valueOf(text("phase")), SegmentPurpose.valueOf(text("purpose")),
        value.getValue("segment_count").jsonPrimitive.int, value.getValue("sequential_origin").jsonPrimitive.boolean,
        value.getValue("next_required_index").jsonPrimitive.int, value.getValue("broken").jsonPrimitive.boolean,
        value.getValue("completed").jsonArray.map { entry -> entry.jsonObject.let {
            SegmentCompletionEvidence(UUID.fromString(it.getValue("event_id").jsonPrimitive.content), UUID.fromString(it.getValue("request_id").jsonPrimitive.content),
                it.getValue("generation").jsonPrimitive.long, UUID.fromString(it.getValue("segment_id").jsonPrimitive.content), it.getValue("index").jsonPrimitive.int,
                it.getValue("evidence_ref").jsonPrimitive.content)
        } }, value.getValue("english_main_completion_recorded").jsonPrimitive.boolean)
    require(result.profile == ProfileContext(program.owner.profileId, program.owner.privacyEpoch) && result.selectionId == program.selectionId && result.source == program.source &&
        result.scriptId == program.manifest.scriptId && result.scriptHash == program.manifest.scriptHash && result.manifestId == program.manifest.manifestId &&
        result.manifestVersion == program.manifest.segmentsVersion && result.manifestHash == program.manifest.manifestHash && result.language == program.manifest.language &&
        result.phase == program.phase && result.purpose == program.purpose && result.segmentCount == program.manifest.segments.size)
    require(result.completed.all { program.manifest.segments.getOrNull(it.index)?.segmentId == it.segmentId && it.generation >= 1 && it.evidenceRef.codePointCount(0, it.evidenceRef.length) in 1..160 })
    var witnessedPrefix = 0
    if (result.sequentialOrigin) for (entry in result.completed) {
        if (entry.index == witnessedPrefix) witnessedPrefix++
        else if (entry.index > witnessedPrefix) break
    }
    require(result.nextRequiredIndex <= witnessedPrefix && (result.sequentialOrigin || result.nextRequiredIndex == 0))
    require(!result.englishMainCompletionRecorded || result.sequentialOrigin && result.nextRequiredIndex == result.segmentCount && result.language == SegmentLanguage.EN && result.phase == SegmentPhase.MAIN && result.purpose == SegmentPurpose.MAIN)
    return result
}

internal fun encodeLocalSegmentState(ledger:SequentialCompletion,continuation:CompletionContinuation1?):String=buildJsonObject {
    put("local_state_version",2);put("ledger",Json.parseToJsonElement(encodeSegmentLedger(ledger)))
    put("continuation",continuation?.let(::encodeCompletionContinuation)?:JsonNull)
}.toString()
internal fun encodeCompletionContinuation(value:CompletionContinuation1):JsonObject=buildJsonObject {
    put("continuation_version",value.continuationVersion);put("completed_event_id",value.completedEventId.toString())
    put("completed_run",encodeCompletedSegmentRun(value.completedRun));put("next_segment_index",value.nextSegmentIndex?.let(::JsonPrimitive)?:JsonNull)
    put("phase_end_pending",value.phaseEndPending);put("state",value.state.name);put("reason_code",value.reasonCode?.let(::JsonPrimitive)?:JsonNull)
    put("requires_explicit_resume",value.requiresExplicitResume)
}
internal fun encodeCompletedSegmentRun(run:SegmentRun):JsonObject=buildJsonObject {
    put("run_id",run.runId.toString());put("generation",run.generation);put("request_id",run.requestId.toString())
    putJsonObject("owner"){put("profile_id",run.owner.profileId.toString());put("privacy_epoch",run.owner.privacyEpoch);put("revision",run.owner.revision);put("presentation_token",run.owner.presentationToken.toString())}
    put("selection_id",run.selectionId.toString());putJsonObject("source"){put("kind",run.source.kind);put("canonical_hash",run.source.canonicalHash)}
    put("phase",run.phase.name);put("purpose",run.purpose.name);put("script_id",run.scriptId.toString());put("script_hash",run.scriptHash)
    put("manifest_id",run.manifestId.toString());put("manifest_version",run.manifestVersion);put("manifest_hash",run.manifestHash)
    put("segment_id",run.segmentId.toString());put("segment_index",run.segmentIndex);put("segment_text_hash",run.segmentTextHash);put("output_lease_id",run.outputLeaseId.toString())
}
internal fun decodeSegmentLedger(source:String,program:SegmentProgram):SequentialCompletion=decodeLocalSegmentState(source,program).first
internal fun decodeLocalSegmentState(source:String,program:SegmentProgram):Pair<SequentialCompletion,CompletionContinuation1?> {
    val root=Json.parseToJsonElement(source).jsonObject
    if("local_state_version" !in root)return decodeBareSegmentLedger(source,program) to null
    require(root.keys==setOf("local_state_version","ledger","continuation") && root.getValue("local_state_version")==JsonPrimitive(2))
    val ledger=decodeBareSegmentLedger(root.getValue("ledger").toString(),program)
    val item=root.getValue("continuation");if(item===JsonNull)return ledger to null
    val value=item.jsonObject
    require(value.keys==setOf("continuation_version","completed_event_id","completed_run","next_segment_index","phase_end_pending","state","reason_code","requires_explicit_resume"))
    require(value.getValue("continuation_version")==JsonPrimitive(1))
    for(key in listOf("phase_end_pending","requires_explicit_resume"))require(value.getValue(key) in setOf(JsonPrimitive(true),JsonPrimitive(false)))
    value.getValue("next_segment_index").takeUnless{it===JsonNull}?.let{require(!it.jsonPrimitive.isString && it.jsonPrimitive.intOrNull!=null)}
    require(value.getValue("state").jsonPrimitive.isString)
    value.getValue("reason_code").takeUnless{it===JsonNull}?.let{require(it.jsonPrimitive.isString)}
    val run=decodeCompletedSegmentRun(value.getValue("completed_run").jsonObject)
    val owner=program.owner
    require(run.owner.profileId==owner.profileId && run.owner.privacyEpoch==owner.privacyEpoch && run.owner.revision==owner.revision &&
        run.selectionId==program.selectionId && run.source==program.source && run.phase==program.phase && run.purpose==program.purpose &&
        run.scriptId==program.manifest.scriptId && run.scriptHash==program.manifest.scriptHash && run.manifestId==program.manifest.manifestId &&
        run.manifestVersion==program.manifest.segmentsVersion && run.manifestHash==program.manifest.manifestHash && run.runId==ledger.runId)
    val segment=program.manifest.segments[run.segmentIndex];require(segment.segmentId==run.segmentId && segment.textHash==run.segmentTextHash)
    val result=CompletionContinuation1(UUID.fromString(value.getValue("completed_event_id").jsonPrimitive.content),run,
        value.getValue("next_segment_index").takeUnless{it===JsonNull}?.jsonPrimitive?.int,value.getValue("phase_end_pending").jsonPrimitive.boolean,
        OutputContinuationState.valueOf(value.getValue("state").jsonPrimitive.content),value.getValue("reason_code").takeUnless{it===JsonNull}?.jsonPrimitive?.content,
        value.getValue("requires_explicit_resume").jsonPrimitive.boolean,value.getValue("continuation_version").jsonPrimitive.int)
    val index=program.segmentIndices.indexOf(run.segmentIndex);require(index>=0)
    // A recorded manual move changes only the next explicit playback target. The original
    // completed event/run and cleanup hold remain exact; it cannot create sequential credit.
    require(result.nextSegmentIndex==program.segmentIndices.getOrNull(index+1) ||
        ledger.broken && !result.phaseEndPending && result.nextSegmentIndex in program.segmentIndices)
    require(!result.phaseEndPending || result.nextSegmentIndex==null)
    require(ledger.completed.any{it.eventId==result.completedEventId && it.requestId==run.requestId && it.generation==run.generation && it.segmentId==run.segmentId && it.index==run.segmentIndex})
    return ledger to result
}
private fun decodeCompletedSegmentRun(value:JsonObject):SegmentRun {
    require(value.keys==setOf("run_id","generation","request_id","owner","selection_id","source","phase","purpose","script_id","script_hash","manifest_id","manifest_version","manifest_hash","segment_id","segment_index","segment_text_hash","output_lease_id"))
    fun uuid(key:String):UUID {val p=value.getValue(key).jsonPrimitive;require(p.isString);return UUID.fromString(p.content)}
    fun text(key:String):String { val p=value.getValue(key).jsonPrimitive;require(p.isString);return p.content }
    for(key in listOf("generation","manifest_version","segment_index"))require(!value.getValue(key).jsonPrimitive.isString && value.getValue(key).jsonPrimitive.longOrNull!=null)
    val o=value.getValue("owner").jsonObject;require(o.keys==setOf("profile_id","privacy_epoch","revision","presentation_token"))
    for(key in listOf("privacy_epoch","revision"))require(!o.getValue(key).jsonPrimitive.isString && o.getValue(key).jsonPrimitive.longOrNull!=null)
    for(key in listOf("profile_id","presentation_token"))require(o.getValue(key).jsonPrimitive.isString)
    val source=value.getValue("source").jsonObject;require(source.keys==setOf("kind","canonical_hash"))
    for(key in listOf("kind","canonical_hash"))require(source.getValue(key).jsonPrimitive.isString)
    return SegmentRun(uuid("run_id"),value.getValue("generation").jsonPrimitive.long,uuid("request_id"),
        OwnerContext(UUID.fromString(o.getValue("profile_id").jsonPrimitive.content),o.getValue("privacy_epoch").jsonPrimitive.long,
            o.getValue("revision").jsonPrimitive.long,UUID.fromString(o.getValue("presentation_token").jsonPrimitive.content)),uuid("selection_id"),
        SegmentSourceKey(source.getValue("kind").jsonPrimitive.content,source.getValue("canonical_hash").jsonPrimitive.content),
        SegmentPhase.valueOf(text("phase")),SegmentPurpose.valueOf(text("purpose")),uuid("script_id"),text("script_hash"),uuid("manifest_id"),
        value.getValue("manifest_version").jsonPrimitive.int,text("manifest_hash"),uuid("segment_id"),value.getValue("segment_index").jsonPrimitive.int,text("segment_text_hash"),uuid("output_lease_id"))
}
