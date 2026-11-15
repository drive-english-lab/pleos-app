package io.github.driveenglishlab.pleos.data

import android.content.Context
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.*

/** Private durable intent. The adapter freezes the existing checkpoint sync CAS once per operation.
 * This is not an additional public API request and adds no fields to the source6 wire contract.
 */
data class SegmentCursorWrite(val operationId: UUID, val owner: OwnerContext, val selectionId: UUID,
    val source: WireSource6ContentRef6, val cursor: WireSource6PlaybackCursor6, val purpose: SegmentPurpose)

/** Actual source6 transport only. null/false means no matching ACK; never physical-output permission.
 * start must also project its returned checkpoint through the existing source6 read journal.
 */
interface SegmentCheckpointPort {
    suspend fun cursor(write: SegmentCursorWrite): WireSource6PersonalReadCheckpoint?
    suspend fun start(owner: OwnerContext, request: WireSource6PersonalPlaybackRunStart): WireSource6PersonalPlaybackRunReceipt?
    suspend fun observation(owner: OwnerContext, eventId: UUID, value: WireSource6SegmentObservationInput6): Boolean
}

/** Reconstructs immutable approved text + strict manifest, without fetching a replacement source.
 * The existing source/rights resolver must approve each use separately through the runtime gate.
 */
class SegmentPlaybackRepository(context: Context, private val dao: SegmentPlaybackDao,
    port: SegmentCheckpointPort, private val current: suspend (OwnerContext) -> Boolean) {
    private val codecs = IncrementalContractJson(context)
    private val sources = ConcurrentHashMap<SegmentSourceKey, String>()
    val journal = SegmentJournal(dao, codecs, port, current) { program ->
        requireNotNull(sources[program.source]) { "Exact segment source was not bound." }
    }

    fun program(owner: OwnerContext, selectionId: UUID, sourceJson: String, manifestJson: String, script: String,
        phase: SegmentPhase, purpose: SegmentPurpose, segmentIndices: List<Int>? = null): SegmentProgram {
        require(purpose != SegmentPurpose.SETTINGS_PREVIEW) { "Preview is local-only and cannot enter the learning journal." }
        val source = codecs.decodeSource6Content(sourceJson)
        val exactSource = codecs.encode("source6", "ContentRef6", source, WireSource6ContentRef6.serializer())
        val sourceKey = sourceKey(exactSource)
        val wire = codecs.decodeSource6("SegmentManifest", manifestJson, WireSource6SegmentManifest.serializer())
        val manifest = wire.toSegmentManifest()
        manifest.validate(script)
        // Main prepared refs pin text identity as well as source/generation. Followup components
        // have their own approved script identity and must be resolved by the shared source port.
        if (source is WireSource6PreparedContentRef6 && phase == SegmentPhase.MAIN) {
            require(source.mainScriptId == manifest.scriptId.toString() && source.mainTextHash == manifest.scriptHash)
        } else if (source is WireSource6LegacyContentRef6 && phase == SegmentPhase.MAIN) {
            require(source.legacyRef.listeningScriptId == manifest.scriptId.toString())
        }
        sources.putIfAbsent(sourceKey, exactSource)?.let { require(canonicalSource(it) == canonicalSource(exactSource)) }
        return SegmentProgram(owner, selectionId, sourceKey, manifest, script, phase, purpose,
            segmentIndices ?: manifest.segments.indices.toList())
    }

    /** A real current checkpoint may prove that the old phase CAS already succeeded before a
     * crash. Only its marker is reduced; completion/cleanup facts and physical-output holds survive. */
    suspend fun acknowledgeRestoredPhase(program:SegmentProgram,checkpoint:WireSource6PersonalReadCheckpoint):Boolean {
        if(!current(program.owner) || checkpoint.payload.selectionId!=program.selectionId.toString() ||
            checkpoint.payload.contentRef!=codecs.decodeSource6Content(requireNotNull(sources[program.source])))return false
        val cursor=checkpoint.payload.cursor as? WireSource6SegmentCursor6?:return false
        val advanced=when(program.phase){
            SegmentPhase.MAIN->cursor.phase in setOf("followup","article_offer")
            SegmentPhase.FOLLOWUP->cursor.phase=="article_offer"
            SegmentPhase.ARTICLE_OFFER->false
        }
        if(!advanced)return false
        val snapshot=restore(program)?:return false
        val pending=snapshot.continuation?.takeIf{it.phaseEndPending}?:return false
        val starts=dao.acknowledgedStarts(program.owner.profileId.toString(),program.owner.privacyEpoch,program.selectionId.toString())
        val original=starts.firstNotNullOfOrNull{row->row.receiptJson?.let{json->
            val value=codecs.decodeSource6("PersonalPlaybackRunReceipt",json,WireSource6PersonalPlaybackRunReceipt.serializer())
            value.takeIf{it.run.let{run->run is WireSource6SegmentRunRecord6 && run.requestId==pending.completedRun.requestId.toString() && run.runId==pending.completedRun.runId.toString()}}
        }}?:return false
        if(checkpoint.recordId!=original.checkpoint.recordId || checkpoint.version<=original.checkpoint.version || !current(program.owner))return false
        return journal.phaseAcknowledged(snapshot,pending.completedEventId)
    }

    /** Requires the same approved source and complete manifest. It never converts a legacy time. */
    suspend fun restore(program: SegmentProgram): SegmentPlaybackSnapshot? {
        if (!current(program.owner)) return null
        val row = dao.state(program.owner.profileId.toString(), program.owner.privacyEpoch, program.selectionId.toString(),
            program.phase.name.lowercase(), program.purpose.name.lowercase(), "segment") ?: return null
        if (!current(program.owner) || row.ownerRevision != program.owner.revision || sourceKey(row.sourceJson) != program.source) return null
        val manifest = codecs.decodeSource6("SegmentManifest", requireNotNull(row.manifestJson), WireSource6SegmentManifest.serializer()).toSegmentManifest()
        require(manifest == program.manifest)
        val cursor = codecs.decodeSource6("PlaybackCursor6", row.cursorJson, WireSource6PlaybackCursor6.serializer()) as? WireSource6SegmentCursor6 ?: return null
        val domainCursor = cursor.toSegmentCursor()
        require(domainCursor.matches(program.manifest) && domainCursor.phase == program.phase)
        val stored=decodeLocalSegmentState(requireNotNull(row.ledgerJson),program)
        val ledger=stored.first
        val continuation=stored.second
        require(ledger.runId.toString() == row.runId)
        for (evidence in ledger.completed) {
            val observation = dao.operation(evidence.eventId.toString()) ?: error("Missing original segment evidence.")
            require(observation.kind == "observation" && !observation.isolated && observation.profileId == row.profileId && observation.privacyEpoch == row.privacyEpoch)
            val exact = codecs.decodeSource6("SegmentObservationInput6", observation.payloadJson, WireSource6SegmentObservationInput6.serializer())
            require(exact.eventId == evidence.eventId.toString() && exact.requestId == evidence.requestId.toString() && exact.runId == row.runId &&
                exact.status == "completed" && exact.runGeneration == evidence.generation && exact.segmentId == evidence.segmentId.toString() && exact.segmentIndex == evidence.index.toLong() && exact.evidenceRef == evidence.evidenceRef &&
                exact.sourceRef==codecs.decodeSource6Content(row.sourceJson) && exact.phase==program.phase.name.lowercase() && exact.purpose==program.purpose.name.lowercase() &&
                exact.scriptId==program.manifest.scriptId.toString() && exact.scriptHash==program.manifest.scriptHash && exact.manifestId==program.manifest.manifestId.toString() &&
                exact.manifestHash==program.manifest.manifestHash && exact.segmentsVersion==program.manifest.segmentsVersion.toLong())
        }
        if(continuation!=null) {
            val run=continuation.completedRun
            val starts=dao.acknowledgedStarts(row.profileId,row.privacyEpoch,row.selectionId)
            require(starts.any{start->
                if(start.ownerRevision!=run.owner.revision || start.payloadHash!=LearningJson.hash(start.payloadJson) || start.receiptJson==null)false
                else {
                    val receipt=codecs.decodeSource6("PersonalPlaybackRunReceipt",start.receiptJson,WireSource6PersonalPlaybackRunReceipt.serializer())
                    val actual=receipt.run as? WireSource6SegmentRunRecord6
                    actual!=null && actual.runId==run.runId.toString() && actual.requestId==run.requestId.toString() && actual.leaseId==run.outputLeaseId.toString() &&
                        actual.runGeneration==run.generation && actual.profileId==row.profileId && actual.privacyEpoch==row.privacyEpoch && actual.sourceRef==codecs.decodeSource6Content(row.sourceJson) &&
                        actual.currentSegmentId==run.segmentId.toString() && actual.currentSegmentIndex==run.segmentIndex.toLong() &&
                        actual.scriptId==run.scriptId.toString() && actual.scriptHash==run.scriptHash && actual.manifestId==run.manifestId.toString() &&
                        actual.segmentsVersion==run.manifestVersion.toLong() && actual.manifestHash==run.manifestHash && actual.phase==run.phase.name.lowercase() && actual.purpose==run.purpose.name.lowercase()
                }
            })
        }
        // Process recreation restores facts only. Old queued/physical output never auto-resumes.
        return SegmentPlaybackSnapshot(program, domainCursor, UUID.fromString(requireNotNull(row.runId)), row.generation, ledger,continuation=continuation)
    }

    /** Preserve the original legacy cursor/ref/coverage bytes separately from segment restarts. */
    suspend fun preserveLegacy(owner: OwnerContext, selectionId: UUID, sourceJson: String, original: PlaybackCursor6.LegacyTime) {
        require(current(owner))
        val source = codecs.decodeSource6Content(sourceJson)
        val sourceText = codecs.encode("source6", "ContentRef6", source, WireSource6ContentRef6.serializer())
        val wire = WireSource6LegacyTimeCursor6("legacy_time", original.originalCheckpointId.toString(), original.originalPositionMs,
            original.originalDurationMs, original.phase.name.lowercase())
        val cursorText = codecs.encode("source6", "PlaybackCursor6", wire, WireSource6PlaybackCursor6.serializer())
        val originalFacts = buildJsonObject {
            put("content", LearningJson.content(original.originalContent).toString())
            put("main_coverage", original.mainCoverage.name.lowercase()); put("run_id", original.originalRunId?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        }.toString()
        dao.preserveLegacy(owner, LocalSegmentState(owner.profileId.toString(), owner.privacyEpoch, selectionId.toString(),
            original.phase.name.lowercase(), "main", "legacy_time", owner.revision, sourceText, null, cursorText,
            original.originalRunId?.toString(), 1, originalFacts, null, false, 1))
    }
}

internal fun WireSource6SegmentManifest.toSegmentManifest() = SegmentManifest(UUID.fromString(manifestId), UUID.fromString(scriptId), scriptHash,
    segmentsVersion.segmentInt(), manifestHash, SegmentLanguage.valueOf(language.uppercase()), segments.map {
        SpeechSegment(UUID.fromString(it.segmentId), it.order.segmentInt(), UUID.fromString(it.sentenceId), it.startCp.segmentInt(), it.endCp.segmentInt(), it.textHash)
    })
internal fun SegmentManifest.toWireSegmentManifest() = WireSource6SegmentManifest(manifestId.toString(), scriptId.toString(), scriptHash,
    segmentsVersion.toLong(), manifestHash, language.name.lowercase(), segments.map {
        WireSource6Segment(it.segmentId.toString(), it.order.toLong(), it.sentenceId.toString(), it.startCodePoint.toLong(), it.endCodePoint.toLong(), it.textHash)
    })
fun PlaybackCursor6.Segment.toWireSegmentCursor() = WireSource6SegmentCursor6("segment", scriptId.toString(), scriptHash,
    manifestId.toString(), manifestVersion.toLong(), manifestHash, phase.name.lowercase(), segmentId?.toString(), segmentIndex?.toLong(),
    resumeFrom, playIntent, pauseReason.name.lowercase())
fun WireSource6SegmentCursor6.toSegmentCursor() = PlaybackCursor6.Segment(UUID.fromString(scriptId), scriptHash, UUID.fromString(manifestId),
    manifestVersion.segmentInt(), manifestHash, SegmentPhase.valueOf(phase.uppercase()), segmentId?.let(UUID::fromString), segmentIndex?.segmentInt(),
    playIntent, PauseReason.valueOf(pauseReason.uppercase()), resumeFrom)
internal fun Long.segmentInt(): Int { require(this in 0L..Int.MAX_VALUE.toLong()); return toInt() }
internal fun canonicalSource(source: String): String = canonicalSegmentJson(Json.parseToJsonElement(source))
internal fun sourceKey(source: String): SegmentSourceKey {
    val value = Json.parseToJsonElement(source).jsonObject
    return SegmentSourceKey(value.getValue("source_kind").jsonPrimitive.content, segmentSha256(canonicalSegmentJson(value)))
}
internal fun canonicalSegmentJson(value: JsonElement): String = when (value) {
    is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { JsonPrimitive(it.key).toString() + ":" + canonicalSegmentJson(it.value) }
    is JsonArray -> value.joinToString(",", "[", "]") { canonicalSegmentJson(it) }
    else -> value.toString()
}
