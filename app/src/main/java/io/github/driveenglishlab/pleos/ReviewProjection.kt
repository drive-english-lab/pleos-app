package io.github.driveenglishlab.pleos

import io.github.driveenglishlab.pleos.data.*
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.Json

/** Display projection only; original server context/return bytes remain in ReviewRepository. */
internal fun WireReviewListeningContext.domainReview(legacy: ListeningWorkRef? = null): ReviewContext {
    val profile = ProfileContext(UUID.fromString(profileId), learningEpoch)
    val selection = UUID.fromString(selectionId)
    val original = returnSnapshot?.let { source ->
        val cursor = when (val cursor = source.cursor) {
            is WireReviewSegmentCursor6 -> PlaybackCursor6.Segment(UUID.fromString(cursor.scriptId), cursor.scriptHash,
                UUID.fromString(cursor.manifestId), cursor.manifestVersion.toInt(), cursor.manifestHash,
                SegmentPhase.valueOf(cursor.phase.uppercase(Locale.ROOT)), cursor.segmentId?.let(UUID::fromString), cursor.segmentIndex?.toInt(),
                cursor.playIntent, PauseReason.valueOf(cursor.pauseReason.uppercase(Locale.ROOT)))
            is WireReviewLegacyTimeCursor6 -> legacy?.takeIf { it.checkpointId.toString() == cursor.originalCheckpointId }?.let {
                PlaybackCursor6.LegacyTime(it.checkpointId, cursor.originalPositionMs, cursor.originalDurationMs, it.content,
                    ListeningStage.valueOf(cursor.phase.uppercase(Locale.ROOT)), MainCoverage.UNKNOWN, it.runId)
            }
        }
        cursor?.let { ReviewReturnSnapshot(profile, selection, UUID.fromString(source.checkpointId), source.checkpointVersion,
            source.lessonId?.let(UUID::fromString), SegmentPhase.valueOf(source.phase.uppercase(Locale.ROOT)), it, source.playIntent,
            PauseReason.valueOf(source.pauseReason.uppercase(Locale.ROOT)), source.resumeVersion) }
    }
    return ReviewContext(profile, selection, version, ReviewOrigin.valueOf(origin.uppercase(Locale.ROOT)), ReviewEndPolicy.valueOf(endPolicy.uppercase(Locale.ROOT)),
        offerId?.let(UUID::fromString), cancelledOfferId?.let(UUID::fromString), sourceSelectionId?.let(UUID::fromString),
        previousResumeRef?.let { ReviewResumeRef(if (it.kind == "practice") ReviewResumeKind.LESSON else ReviewResumeKind.CHECKPOINT, UUID.fromString(it.recordId)) },
        original, practiceEntry?.let { ReviewPracticeEntry(UUID.fromString(it.entryId), UUID.fromString(it.lessonId), it.visibility == "visible") })
}
internal fun WireSource6ListeningContext.domainReview(codecs: IncrementalContractJson): ReviewContext =
    codecs.decodeReview("ListeningContext", Json.encodeToString(WireSource6ListeningContext.serializer(), this), WireReviewListeningContext.serializer()).domainReview()
