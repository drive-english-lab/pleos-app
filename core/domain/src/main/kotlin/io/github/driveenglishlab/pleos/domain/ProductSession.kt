package io.github.driveenglishlab.pleos.domain

import java.util.UUID

enum class OwnerBindingStatus { UNRESOLVED, PROVISIONAL_LOCAL, VERIFIED, TRANSITION }
enum class SessionOperationKind { PROFILE, CATALOG, SELECTION, SETTINGS, READER, RESTORE }

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
    val intentGeneration: Long)

/** Carries original request identity through asynchronous repository work. */
data class RepositoryCompletion<T>(val ticket: SessionOperationTicket, val value: T)
