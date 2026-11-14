package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID

fun WireCommonContentRef.toDomainContentRef() = ContentRef(UUID.fromString(articleId), UUID.fromString(bundleId),
    UUID.fromString(sourceSnapshotId), UUID.fromString(listeningScriptId), when (listeningMode) {
        "original_full" -> ListeningMode.ORIGINAL_FULL; "briefing" -> ListeningMode.BRIEFING; else -> error("Invalid listening format.")
    })

fun ListeningFrame.workRef(): ListeningWorkRef? {
    val record = checkpoint ?: return null
    val point = record.payload
    return ListeningWorkRef(streamId, UUID.fromString(point.selectionId), UUID.fromString(point.checkpointId), point.contentRef.toDomainContentRef(),
        when (point.phase) { "main" -> ListeningStage.MAIN; "followup" -> ListeningStage.FOLLOWUP; "article_offer" -> ListeningStage.ARTICLE_OFFER; else -> error("Invalid stage.") },
        point.currentAssetId?.let(UUID::fromString), point.durationMs, point.activeRunId?.let(UUID::fromString), record.version)
}

suspend fun CatalogRepository.readCheckpointScript(frame: ListeningFrame): CatalogRead<PublishedScript> {
    val point = frame.checkpoint?.payload ?: return CatalogRead.Unavailable("NO_CHECKPOINT", false)
    val ref = point.contentRef.toDomainContentRef()
    val result = when (point.phase) {
        "main" -> readMain(ref)
        "followup" -> {
            val followup = when (frame.selection?.settingsSnapshot?.followupPolicy) {
                "none" -> Followup.NONE; "en_summary" -> Followup.EN_SUMMARY
                "ko_summary" -> Followup.KO_SUMMARY; "ko_translation" -> Followup.KO_TRANSLATION
                else -> return CatalogRead.Unavailable("CONTENT_UNAVAILABLE", false)
            }
            val role = ListeningPolicy.followupRole(ref.listeningMode, followup) ?: return CatalogRead.Unavailable("CONTENT_UNAVAILABLE", false)
            readFollowup(ref, CatalogScriptRole.entries.single { it.wire == role })
        }
        else -> return CatalogRead.Unavailable("CONTENT_UNAVAILABLE", false)
    }
    if (result is CatalogRead.Stored && (result.value.audioAssetId.toString() != point.currentAssetId || result.value.durationMs != point.durationMs))
        return CatalogRead.Unavailable("INVALID_CONTENT", false)
    return result
}
