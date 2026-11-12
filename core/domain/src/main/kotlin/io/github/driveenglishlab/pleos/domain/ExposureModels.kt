package io.github.driveenglishlab.pleos.domain

import java.time.Instant
import java.util.UUID

enum class HistoryOrigin { KNOWN_NEW_PROFILE, HISTORY_RESET, LEGACY_OR_INCOMPLETE }
enum class ExposureStatus { EXPOSED, UNEXPOSED_VERIFIED, UNKNOWN }
enum class ExposureBasis { CONFIRMED_PRESENTATION, COMPLETE_RETAINED_HISTORY, HISTORY_GAP, UNRESOLVED_PRESENTATION }
enum class AssessmentBoundary { LESSON_START, ATTEMPT_ACCEPTANCE }

data class HistoryCoverage(val origin: HistoryOrigin, val version: Long, val historyGap: Boolean) {
    init { require(version >= 1); require(historyGap == (origin != HistoryOrigin.KNOWN_NEW_PROFILE)) }
}

/** Server-owned projection. No corresponding client setter or sync input exists. */
data class QuestionExampleExposure(
    val questionRef: BasicQuestionRef,
    val status: ExposureStatus,
    val basis: ExposureBasis,
    val exposureVersion: Long,
    val coverageVersion: Long,
    val confirmedPresentationId: UUID?,
) {
    init {
        require(exposureVersion >= 0 && coverageVersion >= 1)
        when (status) {
            ExposureStatus.EXPOSED -> require(basis == ExposureBasis.CONFIRMED_PRESENTATION && confirmedPresentationId != null)
            ExposureStatus.UNEXPOSED_VERIFIED -> require(basis == ExposureBasis.COMPLETE_RETAINED_HISTORY && confirmedPresentationId == null)
            ExposureStatus.UNKNOWN -> require(basis in setOf(ExposureBasis.HISTORY_GAP, ExposureBasis.UNRESOLVED_PRESENTATION) && confirmedPresentationId == null)
        }
    }
}

data class ExampleExposureAssessment(
    val exposure: QuestionExampleExposure,
    val assessmentId: UUID,
    val boundaryKind: AssessmentBoundary,
    val boundaryId: UUID,
    val historyLineageId: UUID,
    val boundarySequence: Long,
    val assessedAt: Instant,
    val revision: Long,
) {
    init { require(boundarySequence >= 1 && revision >= 1) }
}

enum class SupportRole { QUESTION_MEANING, CORE_EXPLANATION, SIMPLE_EXPLANATION, SENTENCE_STARTER, PARTIAL_CORRECTION, COMPLETE_EXAMPLE }
enum class SupportLanguage { KO, EN, MIXED }
enum class SupportOrigin { PUBLISHED_TASK, VALIDATED_FEEDBACK }

/** Prepared reviewed content. Constructing/reading it never records delivery. */
data class SupportArtifact(
    val supportRef: String,
    val kind: HelpKind?,
    val role: SupportRole,
    val language: SupportLanguage,
    val text: String,
    val textHash: String,
    val origin: SupportOrigin,
    val feedbackAttemptId: UUID?,
) {
    init {
        requireReference(supportRef)
        require(text.codePointCount(0, text.length) in 1..600)
        require(textHash.matches(Regex("[a-f0-9]{64}")))
        require((origin == SupportOrigin.PUBLISHED_TASK) == (feedbackAttemptId == null))
        when (role) {
            SupportRole.QUESTION_MEANING -> require(kind == null && language in setOf(SupportLanguage.KO, SupportLanguage.EN))
            SupportRole.CORE_EXPLANATION -> require(kind == HelpKind.FACT_EXPLANATION && language in setOf(SupportLanguage.KO, SupportLanguage.EN))
            SupportRole.SIMPLE_EXPLANATION -> require(kind == HelpKind.FACT_EXPLANATION && language == SupportLanguage.EN)
            SupportRole.SENTENCE_STARTER -> require(kind == HelpKind.SENTENCE_STARTER && language == SupportLanguage.EN)
            SupportRole.PARTIAL_CORRECTION -> require(kind == HelpKind.PARTIAL_CORRECTION)
            SupportRole.COMPLETE_EXAMPLE -> require(kind == HelpKind.COMPLETE_EXAMPLE && language == SupportLanguage.EN)
        }
    }
}

data class PracticeSupportManifest(val taskId: UUID, val questionRef: BasicQuestionRef, val supports: List<SupportArtifact>) {
    init {
        require(supports.size in 5..16 && supports.map { it.supportRef }.distinct().size == supports.size)
        val defaults = setOf(SupportRole.QUESTION_MEANING to SupportLanguage.KO, SupportRole.CORE_EXPLANATION to SupportLanguage.KO,
            SupportRole.SIMPLE_EXPLANATION to SupportLanguage.EN, SupportRole.SENTENCE_STARTER to SupportLanguage.EN,
            SupportRole.COMPLETE_EXAMPLE to SupportLanguage.EN)
        require(supports.map { it.role to it.language }.containsAll(defaults))
    }

    fun find(role: SupportRole, language: SupportLanguage): SupportArtifact? =
        supports.singleOrNull { it.role == role && it.language == language }
}

enum class InteractionChannel { PARKED, GLEO }
enum class PresentationStatus { PREPARED, COMPLETED, PARTIAL_OR_UNKNOWN, NOT_PRESENTED_VERIFIED }
enum class ObservationOutcome { COMPLETED, PARTIAL_OR_UNKNOWN, NOT_PRESENTED_VERIFIED }
enum class ObservationMode { PARKED_VISIBLE, VOICE_OUTPUT_COMPLETED, EXPLICIT_EXAMPLE_CONFIRMATION, INDETERMINATE, CANCELLED_BEFORE_OUTPUT }

data class PracticePresentationPrepare(
    val operationId: UUID, val profileContext: ProfileContext, val presentationId: UUID,
    val clientContext: OwnerContext, val interactionChannel: InteractionChannel,
    val contentRef: PracticeContentRef, val lessonId: UUID?, val supportRefs: Set<String>,
) {
    init {
        require(supportRefs.size in 1..4); supportRefs.forEach(::requireReference)
        require(clientContext.profileId == profileContext.profileId && clientContext.privacyEpoch == profileContext.privacyEpoch)
    }
}

data class PracticePresentationRecord(
    val presentationId: UUID, val contentRef: PracticeContentRef, val lessonId: UUID?,
    val interactionChannel: InteractionChannel, val artifacts: List<SupportArtifact>,
    val issuedSequence: Long, val preparedAt: Instant, val status: PresentationStatus, val lastObservationId: UUID?,
) {
    init {
        require(issuedSequence >= 1 && artifacts.size in 1..4)
        require(artifacts.distinct().size == artifacts.size)
        require((status == PresentationStatus.PREPARED) == (lastObservationId == null))
    }
}

data class PracticePresentationTicket(
    val operationId: UUID, val profileContext: ProfileContext, val clientContext: OwnerContext,
    val presentation: PracticePresentationRecord, val exposure: QuestionExampleExposure,
) {
    init { require(presentation.status == PresentationStatus.PREPARED) }
}

data class PracticePresentationObservation(
    val operationId: UUID, val profileContext: ProfileContext, val observationId: UUID,
    val clientContext: OwnerContext, val outputInstanceId: UUID, val outcome: ObservationOutcome,
    val mode: ObservationMode, val adapterEvidenceRef: String,
) {
    init {
        requireReference(adapterEvidenceRef)
        when (outcome) {
            ObservationOutcome.COMPLETED -> require(mode in setOf(ObservationMode.PARKED_VISIBLE,
                ObservationMode.VOICE_OUTPUT_COMPLETED, ObservationMode.EXPLICIT_EXAMPLE_CONFIRMATION))
            ObservationOutcome.PARTIAL_OR_UNKNOWN -> require(mode == ObservationMode.INDETERMINATE)
            ObservationOutcome.NOT_PRESENTED_VERIFIED -> require(mode == ObservationMode.CANCELLED_BEFORE_OUTPUT)
        }
    }
}

data class PracticePresentationReceipt(
    val operationId: UUID, val profileContext: ProfileContext, val presentation: PracticePresentationRecord,
    val exposure: QuestionExampleExposure,
) {
    init { require(presentation.status != PresentationStatus.PREPARED) }
}

enum class SelectionReason { AUTOMATIC_UNHEARD, EXPLICIT_ARTICLE, EXPLICIT_REPLAY, EXPLICIT_FORMAT_CHANGE }
data class FormatChangeSource(val selectionId: UUID, val checkpointId: UUID)
data class SelectionRecord(
    val selectionId: UUID, val streamId: UUID, val ordinal: Long, val previousSelectionId: UUID?,
    val contentRef: ContentRef, val settingsSnapshot: LearningSettings, val selectedAt: Instant,
    val selectionReason: SelectionReason, val formatChangeSource: FormatChangeSource?,
) {
    init { require(ordinal >= 1); require((selectionReason == SelectionReason.EXPLICIT_FORMAT_CHANGE) == (formatChangeSource != null)) }
}

enum class MainCoverage { SEQUENTIAL, PARTIAL, UNKNOWN }
enum class PauseReason { NONE, USER, SYSTEM, PRIVACY, VOICE_FAILURE }
data class CheckpointInput(
    val checkpointId: UUID, val contentRef: ContentRef, val phase: ListeningStage, val currentAssetId: UUID?,
    val positionMs: Long, val durationMs: Long?, val playIntent: Boolean, val pauseReason: PauseReason,
    val streamId: UUID, val selectionId: UUID,
) {
    init {
        require(positionMs >= 0)
        if (phase == ListeningStage.ARTICLE_OFFER) require(currentAssetId == null && durationMs == null && positionMs == 0L)
        else require(currentAssetId != null && durationMs != null && durationMs >= 1 && positionMs <= durationMs)
    }
}

/** Separate read projection prevents serializing coverage/run as client completion claims. */
data class CheckpointRecord(val input: CheckpointInput, val activeRunId: UUID?, val mainCoverageStatus: MainCoverage)
data class ReadCheckpoint(val recordId: UUID, val version: Long, val payload: CheckpointRecord) {
    init { require(version >= 1); require(recordId == payload.input.checkpointId) }
}
data class FormatChangeRequest(
    val operationId: UUID, val profileContext: ProfileContext, val streamId: UUID, val expectedStreamVersion: Long,
    val expectedHeadSelectionId: UUID, val sourceSelectionId: UUID, val sourceCheckpointId: UUID,
    val expectedCheckpointVersion: Long, val checkpointId: UUID, val targetListeningMode: ListeningMode,
) {
    init { require(expectedStreamVersion >= 1 && expectedCheckpointVersion >= 1) }
}
data class FormatChangeResult(
    val operationId: UUID, val profileContext: ProfileContext, val streamId: UUID, val streamVersion: Long,
    val selection: SelectionRecord, val checkpoint: ReadCheckpoint,
) {
    init {
        require(streamVersion >= 1 && selection.selectionReason == SelectionReason.EXPLICIT_FORMAT_CHANGE)
        val saved = checkpoint.payload
        require(saved.input.phase == ListeningStage.MAIN && saved.input.positionMs == 0L && saved.activeRunId == null &&
            saved.mainCoverageStatus == MainCoverage.UNKNOWN)
        require(selection.streamId == streamId && saved.input.streamId == streamId && saved.input.selectionId == selection.selectionId)
    }
}

enum class AttemptTurn { FIRST, RETRY }
/** Immutable client input, deliberately without status, assessment or lesson terminal fields. */
data class AttemptInput(
    val attemptId: UUID, val lessonId: UUID, val contentRef: PracticeContentRef, val turn: AttemptTurn,
    val previousAttemptId: UUID?, val answerText: String, val capture: Capture, val helpReceived: HelpSnapshot,
) {
    init {
        require(answerText.codePointCount(0, answerText.length) in 1..1200 && answerText.isNotBlank())
        require((turn == AttemptTurn.FIRST) == (previousAttemptId == null))
    }
}

data class AttemptRecord(val input: AttemptInput, val exampleExposure: ExampleExposureAssessment, val englishAttemptStatus: EnglishAttemptStatus) {
    init { require(exampleExposure.boundaryKind == AssessmentBoundary.ATTEMPT_ACCEPTANCE && exampleExposure.boundaryId == input.attemptId) }
}
