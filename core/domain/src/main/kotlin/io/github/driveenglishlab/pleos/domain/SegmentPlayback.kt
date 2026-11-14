package io.github.driveenglishlab.pleos.domain

import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** The data layer derives this from the exact validated Ref6 tagged union. */
data class SegmentSourceKey(val kind: String, val canonicalHash: String) {
    init { require(kind in setOf("legacy_bundle", "prepared_components", "settings_demo")); requireSegmentHash(canonicalHash) }
}
enum class SegmentLanguage { EN, KO }
enum class SegmentPhase { MAIN, FOLLOWUP, ARTICLE_OFFER }
enum class SegmentPurpose { MAIN, FOLLOWUP, SENTENCE_HELP, SETTINGS_PREVIEW, PRACTICE_HELP }

data class SpeechSegment(val segmentId: UUID, val order: Int, val sentenceId: UUID,
    val startCodePoint: Int, val endCodePoint: Int, val textHash: String) {
    init { require(order >= 0 && startCodePoint >= 0 && endCodePoint > startCodePoint); requireSegmentHash(textHash) }
}

data class SegmentManifest(val manifestId: UUID, val scriptId: UUID, val scriptHash: String,
    val segmentsVersion: Int, val manifestHash: String, val language: SegmentLanguage, val segments: List<SpeechSegment>) {
    init {
        require(segmentsVersion >= 1 && segments.isNotEmpty())
        requireSegmentHash(scriptHash); requireSegmentHash(manifestHash)
        require(segments.map { it.segmentId }.distinct().size == segments.size)
        require(segments.map { it.order } == segments.indices.toList())
    }
    /** Canonical JSON is UTF-8/sorted keys/no insignificant whitespace, excluding manifest_hash. */
    fun canonicalPayload(): String = "{\"language\":\"${language.name.lowercase()}\",\"manifest_id\":\"$manifestId\",\"script_hash\":\"$scriptHash\",\"script_id\":\"$scriptId\",\"segments\":[" +
        segments.joinToString(",") { "{\"end_cp\":${it.endCodePoint},\"order\":${it.order},\"segment_id\":\"${it.segmentId}\",\"sentence_id\":\"${it.sentenceId}\",\"start_cp\":${it.startCodePoint},\"text_hash\":\"${it.textHash}\"}" } +
        "],\"segments_version\":$segmentsVersion}"

    fun validate(script: String) {
        require(segmentSha256(script) == scriptHash && segmentSha256(canonicalPayload()) == manifestHash)
        require(segments.first().startCodePoint == 0 && segments.last().endCodePoint == script.codePointCount(0, script.length))
        var next = 0
        val finishedSentences = mutableSetOf<UUID>()
        var sentence: UUID? = null
        for (segment in segments) {
            require(segment.startCodePoint == next)
            if (sentence != segment.sentenceId) {
                sentence?.let { finishedSentences += it }
                require(segment.sentenceId !in finishedSentences)
                sentence = segment.sentenceId
            }
            require(segmentSha256(segmentText(script, segment)) == segment.textHash)
            next = segment.endCodePoint
        }
    }
    fun sentenceSegments(sentenceId: UUID, start: Int, end: Int, orderedIds: List<UUID>): List<SpeechSegment> {
        val actual = segments.filter { it.sentenceId == sentenceId }
        require(actual.isNotEmpty() && actual.map { it.segmentId } == orderedIds && actual.first().startCodePoint == start && actual.last().endCodePoint == end)
        return actual
    }
}

fun segmentText(script: String, segment: SpeechSegment): String {
    require(segment.endCodePoint <= script.codePointCount(0, script.length))
    return script.substring(script.offsetByCodePoints(0, segment.startCodePoint), script.offsetByCodePoints(0, segment.endCodePoint))
}
fun segmentSha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
private fun requireSegmentHash(value: String) { require(value.matches(Regex("[a-f0-9]{64}"))) }

sealed interface PlaybackCursor6 {
    data class Segment(val scriptId: UUID, val scriptHash: String, val manifestId: UUID, val manifestVersion: Int,
        val manifestHash: String, val phase: SegmentPhase, val segmentId: UUID?, val segmentIndex: Int?,
        val playIntent: Boolean, val pauseReason: PauseReason, val resumeFrom: String = "start") : PlaybackCursor6 {
        init {
            requireSegmentHash(scriptHash); requireSegmentHash(manifestHash); require(manifestVersion >= 1 && resumeFrom == "start")
            if (phase == SegmentPhase.ARTICLE_OFFER) require(segmentId == null && segmentIndex == null)
            else require(segmentId != null && segmentIndex != null && segmentIndex >= 0)
        }
        fun matches(manifest: SegmentManifest): Boolean = scriptId == manifest.scriptId && scriptHash == manifest.scriptHash &&
            manifestId == manifest.manifestId && manifestVersion == manifest.segmentsVersion && manifestHash == manifest.manifestHash &&
            (phase == SegmentPhase.ARTICLE_OFFER || manifest.segments.getOrNull(requireNotNull(segmentIndex))?.segmentId == segmentId)
    }
    /** Keep old facts as old facts. No guessed mapping to segment IDs or new completion proof. */
    data class LegacyTime(val originalCheckpointId: UUID, val originalPositionMs: Long, val originalDurationMs: Long?,
        val originalContent: ContentRef, val phase: ListeningStage, val mainCoverage: MainCoverage, val originalRunId: UUID?) : PlaybackCursor6 {
        init { require(originalPositionMs >= 0 && (originalDurationMs == null || originalDurationMs >= 1 && originalPositionMs <= originalDurationMs)) }
    }
}

data class SegmentProgram(val owner: OwnerContext, val selectionId: UUID, val source: SegmentSourceKey,
    val manifest: SegmentManifest, val script: String, val phase: SegmentPhase, val purpose: SegmentPurpose,
    val segmentIndices: List<Int> = manifest.segments.indices.toList()) {
    init {
        require(phase != SegmentPhase.ARTICLE_OFFER && segmentIndices.isNotEmpty() && segmentIndices.distinct() == segmentIndices &&
            segmentIndices.all { it in manifest.segments.indices } && segmentIndices.zipWithNext().all { it.second == it.first + 1 })
        require((source.kind == "settings_demo") == (purpose == SegmentPurpose.SETTINGS_PREVIEW))
        require(purpose != SegmentPurpose.MAIN || phase == SegmentPhase.MAIN)
        require(purpose != SegmentPurpose.FOLLOWUP || phase == SegmentPhase.FOLLOWUP)
        manifest.validate(script)
    }
    override fun toString() = "SegmentProgram(source=redacted, purpose=$purpose)"
}

data class SegmentRun(val runId: UUID, val generation: Long, val requestId: UUID, val owner: OwnerContext, val selectionId: UUID,
    val source: SegmentSourceKey, val phase: SegmentPhase, val purpose: SegmentPurpose, val scriptId: UUID, val scriptHash: String,
    val manifestId: UUID, val manifestVersion: Int, val manifestHash: String, val segmentId: UUID, val segmentIndex: Int,
    val segmentTextHash: String, val outputLeaseId: UUID) {
    init { require(generation >= 1 && manifestVersion >= 1 && segmentIndex >= 0); requireSegmentHash(scriptHash); requireSegmentHash(manifestHash); requireSegmentHash(segmentTextHash) }
}
enum class SegmentSpeechEventKind { STARTED, COMPLETED, STOPPED, ERROR, UNKNOWN }
data class SegmentSpeechEvent(val eventId: UUID, val run: SegmentRun, val kind: SegmentSpeechEventKind, val evidenceRef: String) {
    init { requireReference(evidenceRef) }
}
data class SegmentCompletionEvidence(val eventId: UUID, val requestId: UUID, val generation: Long, val segmentId: UUID, val index: Int, val evidenceRef: String)

data class SequentialCompletion(val profile: ProfileContext, val selectionId: UUID, val runId: UUID, val source: SegmentSourceKey,
    val scriptId: UUID, val scriptHash: String, val manifestId: UUID, val manifestVersion: Int, val manifestHash: String,
    val language: SegmentLanguage, val phase: SegmentPhase, val purpose: SegmentPurpose, val segmentCount: Int,
    val sequentialOrigin: Boolean, val nextRequiredIndex: Int = 0, val broken: Boolean = false,
    val completed: List<SegmentCompletionEvidence> = emptyList(), val englishMainCompletionRecorded: Boolean = false) {
    init { require(segmentCount >= 1 && nextRequiredIndex in 0..segmentCount && completed.map { it.eventId }.distinct().size == completed.size) }
    val complete get() = sequentialOrigin && !broken && nextRequiredIndex == segmentCount && language == SegmentLanguage.EN && phase == SegmentPhase.MAIN && purpose == SegmentPurpose.MAIN
    fun manuallyMoved() = copy(broken = true)
    fun completed(event: SegmentSpeechEvent): SequentialCompletion {
        require(event.kind == SegmentSpeechEventKind.COMPLETED)
        val run = event.run
        require(ProfileContext(run.owner.profileId, run.owner.privacyEpoch) == profile && run.selectionId == selectionId && run.runId == runId && run.source == source &&
            run.scriptId == scriptId && run.scriptHash == scriptHash && run.manifestId == manifestId && run.manifestVersion == manifestVersion && run.manifestHash == manifestHash &&
            run.phase == phase && run.purpose == purpose && run.segmentIndex in 0 until segmentCount)
        val evidence = SegmentCompletionEvidence(event.eventId, run.requestId, run.generation, run.segmentId, run.segmentIndex, event.evidenceRef)
        completed.singleOrNull { it.eventId == event.eventId }?.let { require(it == evidence); return this }
        val gap = broken || run.segmentIndex > nextRequiredIndex
        val frontier = if (!gap && sequentialOrigin && run.segmentIndex == nextRequiredIndex) nextRequiredIndex + 1 else nextRequiredIndex
        return copy(nextRequiredIndex = frontier, broken = gap, completed = completed + evidence)
    }
    companion object {
        fun start(program: SegmentProgram, runId: UUID, index: Int) = SequentialCompletion(ProfileContext(program.owner.profileId, program.owner.privacyEpoch), program.selectionId, runId,
            program.source, program.manifest.scriptId, program.manifest.scriptHash, program.manifest.manifestId, program.manifest.segmentsVersion,
            program.manifest.manifestHash, program.manifest.language, program.phase, program.purpose, program.manifest.segments.size,
            sequentialOrigin = index == 0 && program.segmentIndices == program.manifest.segments.indices.toList())
    }
}

data class SegmentPlaybackSnapshot(val program: SegmentProgram, val cursor: PlaybackCursor6.Segment, val runId: UUID,
    val generation: Long, val ledger: SequentialCompletion, val activeRequest: SegmentRun? = null, val bindingAcknowledged: Boolean = false) {
    init { require(generation >= 1 && cursor.matches(program.manifest)) }
}

sealed interface SegmentSpeechAdmission {
    data object Preparing : SegmentSpeechAdmission
    data class Unavailable(val reason: String) : SegmentSpeechAdmission
}
interface SegmentSpeechPort {
    val events: Flow<SegmentSpeechEvent>
    suspend fun request(run: SegmentRun, text: String, language: SegmentLanguage): SegmentSpeechAdmission
    suspend fun stop(run: SegmentRun)
}
interface SegmentJournalPort {
    suspend fun save(snapshot: SegmentPlaybackSnapshot)
    /** Retry the same durable start/interrupt outbox before physical synthesis. */
    suspend fun prepareOutput(snapshot: SegmentPlaybackSnapshot): SegmentRun?
    suspend fun observed(event: SegmentSpeechEvent, snapshot: SegmentPlaybackSnapshot, recordEnglishCompletion: Boolean)
}
interface SegmentOutputLeasePort {
    suspend fun acquire(owner: OwnerContext, purpose: SegmentPurpose): UUID?
    suspend fun owns(id: UUID, owner: OwnerContext, purpose: SegmentPurpose): Boolean
    /** Adopt the exact server-issued lease only while the provisional exclusive grant survives. */
    suspend fun adopt(previous: UUID, authoritative: UUID, owner: OwnerContext, purpose: SegmentPurpose): Boolean =
        previous == authoritative && owns(previous, owner, purpose)
    suspend fun release(id: UUID)
}
