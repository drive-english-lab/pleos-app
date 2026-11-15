package io.github.driveenglishlab.pleos.domain

import java.util.UUID

enum class OwnerBindingStatus { UNRESOLVED, PROVISIONAL_LOCAL, VERIFIED, TRANSITION }
enum class SessionOperationKind { PROFILE, CATALOG, SELECTION, SETTINGS, READER, RESTORE, PRACTICE, QUESTION, FEEDBACK, SUPPORT, HISTORY, PREFERENCE }

/** Identity projection of an admitted ListeningFrame, not a second checkpoint store. */
data class ListeningWorkRef(val streamId: UUID, val selectionId: UUID, val checkpointId: UUID,
    val content: ContentRef, val stage: ListeningStage, val assetId: UUID?, val durationMs: Long?,
    val runId: UUID?, val checkpointVersion: Long) {
    init {
        require(checkpointVersion > 0)
        if (stage == ListeningStage.ARTICLE_OFFER) require(assetId == null && durationMs == null)
        else require(assetId != null && durationMs != null && durationMs > 0)
    }
    fun sameWork(other: ListeningWorkRef?): Boolean = other != null && streamId == other.streamId &&
        selectionId == other.selectionId && checkpointId == other.checkpointId && content == other.content &&
        stage == other.stage && assetId == other.assetId
    fun accepts(key: NewsPlaybackKey): Boolean = checkpointId == key.checkpointId && selectionId == key.selectionId &&
        content == key.content && assetId == key.assetId && durationMs == key.durationMs &&
        stage == if (key.phase == "main") ListeningStage.MAIN else ListeningStage.FOLLOWUP
}

data class SessionOperationTicket(val id: UUID, val kind: SessionOperationKind, val owner: OwnerContext?,
    val work: ListeningWorkRef?, val task: PracticeRef?, val lessonId: UUID?, val navigationGeneration: Long,
    val intentGeneration: Long, val preparedWork: PreparedListeningWorkRef? = null)

/** Carries original request identity through asynchronous repository work. */
data class RepositoryCompletion<T>(val ticket: SessionOperationTicket, val value: T)

/** Prepared source6 projection; no legacy bundle, asset ID, duration, or millisecond position. */
data class PreparedListeningWorkRef(val streamId: UUID, val selectionId: UUID, val checkpointId: UUID,
    val content: SegmentContentRef, val cursor: PlaybackCursor6.Segment, val checkpointVersion: Long, val runId: UUID? = null) {
    init { require(checkpointVersion >= 1) }
    val stage: ListeningStage get() = ListeningStage.valueOf(cursor.phase.name)
    fun sameWork(other: PreparedListeningWorkRef?): Boolean = other != null && streamId == other.streamId && selectionId == other.selectionId &&
        checkpointId == other.checkpointId && content == other.content && cursor.phase == other.cursor.phase && cursor.scriptId == other.cursor.scriptId &&
        cursor.scriptHash == other.cursor.scriptHash && cursor.manifestId == other.cursor.manifestId && cursor.manifestVersion == other.cursor.manifestVersion && cursor.manifestHash == other.cursor.manifestHash
    fun accepts(program: SegmentProgram): Boolean = selectionId == program.selectionId && content.segmentSourceKey() == program.source &&
        cursor.phase == program.phase && cursor.scriptId == program.manifest.scriptId && cursor.scriptHash == program.manifest.scriptHash &&
        cursor.manifestId == program.manifest.manifestId && cursor.manifestVersion == program.manifest.segmentsVersion && cursor.manifestHash == program.manifest.manifestHash
}

data class PreparedReturnPoint(val work: PreparedListeningWorkRef, val playIntent: PlaybackIntent) {
    /** R4 returns to the interrupted segment start; no legacy five-second subtraction. */
    fun afterConversation() = this
}

fun PreparedContentRef.segmentSourceKey(): SegmentSourceKey {
    val canonical = "{\"article_id\":\"$articleId\",\"listening_mode\":\"${listeningMode.name.lowercase()}\",\"main_component_id\":\"$mainComponentId\",\"main_script_id\":\"$mainScriptId\",\"main_text_hash\":\"$mainTextHash\",\"material_generation_id\":\"$materialGenerationId\",\"source_hash\":\"$sourceHash\",\"source_kind\":\"prepared_components\",\"source_snapshot_id\":\"$sourceSnapshotId\"}"
    return SegmentSourceKey("prepared_components", segmentSha256(canonical))
}

fun ContentRef.segmentSourceKey(): SegmentSourceKey {
    val canonical = "{\"legacy_ref\":{\"article_id\":\"$articleId\",\"bundle_id\":\"$bundleId\",\"listening_mode\":\"${listeningMode.name.lowercase()}\",\"listening_script_id\":\"$listeningScriptId\",\"source_snapshot_id\":\"$sourceSnapshotId\"},\"source_kind\":\"legacy_bundle\"}"
    return SegmentSourceKey("legacy_bundle", segmentSha256(canonical))
}
fun SegmentContentRef.segmentSourceKey(): SegmentSourceKey = when (this) {
    is ContentRef -> segmentSourceKey()
    is PreparedContentRef -> segmentSourceKey()
}
