package io.github.driveenglishlab.pleos.ui

import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.ReviewToolsState
import java.util.UUID
import io.github.driveenglishlab.pleos.data.ReviewNext2State
import io.github.driveenglishlab.pleos.data.ReviewNext3State
import io.github.driveenglishlab.pleos.data.AssessmentReviewState
import io.github.driveenglishlab.pleos.data.ProfileReadSnapshot
import io.github.driveenglishlab.pleos.data.DailyListeningState
import io.github.driveenglishlab.pleos.data.SpeakingRecordsState
import io.github.driveenglishlab.pleos.data.LearningPreferenceState
import io.github.driveenglishlab.pleos.data.LearningMaterialState
import io.github.driveenglishlab.pleos.data.LearningRecommendationState
import io.github.driveenglishlab.pleos.data.SourcePublicScript
import io.github.driveenglishlab.pleos.data.Source6ProfileSnapshot
import io.github.driveenglishlab.pleos.data.Source6SpeakingRecordsState
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.platform.SegmentRuntimeState
import io.github.driveenglishlab.pleos.platform.PresentationUiBinding
import io.github.driveenglishlab.pleos.platform.ArtifactWitness

enum class LoadStatus { NOT_REQUESTED, LOADING, READY, FAILED }
enum class ReaderLanguage { ENGLISH, KOREAN }
data class ReaderPresentation(val script: PublishedScript, val language: ReaderLanguage)
data class UiOrigin(val owner: OwnerContext?, val work: ListeningWorkRef?, val navigationGeneration: Long, val task: PracticeRef? = null, val lessonId: UUID? = null, val preparedWork: PreparedListeningWorkRef? = null)
data class ProductScreenState(val session: SessionView = SessionView(), val profileLabel: String = "기록 연결 확인 중",
    val catalog: List<CatalogArticle> = emptyList(), val catalogStatus: LoadStatus = LoadStatus.NOT_REQUESTED,
    val moreCatalog: Boolean = false, val selectedArticle: CatalogArticle? = null,
    val reader: ReaderPresentation? = null, val readerStatus: LoadStatus = LoadStatus.NOT_REQUESTED,
    val draft: SettingsDraft? = null, val appearance: DeviceAppearance = DeviceAppearance.SYSTEM,
    val appearanceDraft: DeviceAppearance = appearance, val busy: Boolean = false,
    val notice: String? = null, val recovery: String? = null, val noCandidateReason: String? = null,
    val settingsPending: Boolean = false, val selectionPending: Boolean = false,
    val formatPendingMode: ListeningMode? = null, val learning: LearningPresentation = LearningPresentation(), val history: ProfileReadSnapshot? = null,
    val historyStatus: LoadStatus = LoadStatus.NOT_REQUESTED, val deletionPending: Boolean = false,
    val dailyListening: DailyListeningState = DailyListeningState(), val listenOnlyActive: Boolean = false,
    val speakingRecords: SpeakingRecordsState = SpeakingRecordsState(),
    val learningPreference: LearningPreferenceState = LearningPreferenceState(), val learningMaterial: LearningMaterialState = LearningMaterialState(),
    val learningRecommendation: LearningRecommendationState = LearningRecommendationState(), val bandDraft: LearningBand? = null,
    val preparedCatalog: List<WirePreparationDiscovery> = emptyList(), val categoryConfig: WirePreparationCategoryPublicProjection? = null,
    val preparedSource: WirePreparationDiscovery? = null, val preparedScript: SourcePublicScript? = null,
    val segmentPlayback: SegmentRuntimeState = SegmentRuntimeState(), val followupWaiting: Boolean = false, val source6History: Source6ProfileSnapshot? = null, val source6SpeakingRecords: Source6SpeakingRecordsState = Source6SpeakingRecordsState(), val reviewTools: ReviewToolsState = ReviewToolsState(),
    val catalogViews: CatalogViewState = CatalogViewState(), val assessments: AssessmentReviewState = AssessmentReviewState(),
    val identityRecovery: IdentityRecoveryProjection? = null, val playbackRequest: ReviewNext2State = ReviewNext2State(),
    val playbackRequest3: ReviewNext3State = ReviewNext3State(), val originalOutputConfigured: Boolean = false) {
    val origin get() = UiOrigin(session.owner, session.listening, session.navigationGeneration, session.task, session.lessonId, session.preparedListening)
    val activePlaybackRequest get() = listOfNotNull(playbackRequest3.intent, playbackRequest.intent).firstOrNull {
        it.state in setOf(PlaybackRequestState.ACTIVE, PlaybackRequestState.EXPIRED) &&
            it.outcome in setOf(null, PlaybackRequestOutcome.PENDING, PlaybackRequestOutcome.EXPIRED_WAITING_EXPLICIT)
    } ?: playbackRequest3.intent ?: playbackRequest.intent
    val pendingOriginalTarget get() = if (session.owner != null && playbackRequest3.owner == session.owner)
        playbackRequest3.response?.pendingTarget ?: playbackRequest3.retainedParentAnchor?.pendingTarget else null
    val supportedPendingChoices get() = if (session.owner != null && playbackRequest3.owner == session.owner) playbackRequest3.supportedChoices else emptySet()
    val originalAlternativeAvailable get(): Boolean {
        val pending = pendingOriginalTarget ?: return false
        val mode = playbackRequest3.retainedParentAnchor?.parentListeningMode ?: activePlaybackRequest?.binding?.listeningMode
        return selectionPending && PendingChoiceKind.ORIGINAL in supportedPendingChoices && originalOutputConfigured && mode != ListeningMode.ORIGINAL_FULL &&
            (playbackRequest3.response?.outcomeProjection?.noCandidateReason != "original_unavailable" ||
                playbackRequest3.pendingChoiceRetention?.parentProjectionObservedAfterChild == true) && pending.originalAvailability == "ready" &&
            pending.originalComponent?.kind == "main_original" && pending.originalComponent?.status == "ready" &&
            (playbackRequest3.response?.outcomeProjection?.state in setOf("pending", "expired_waiting_explicit") || playbackRequest3.retainedParentAnchor != null)
    }
    val canRead get() = session.access.detailsOpen
    val mediaAllowed get() = session.ownerBinding == OwnerBindingStatus.VERIFIED && session.mediaConnected &&
        session.task == null && session.access.ownerValid && session.access.power == PowerAvailability.ALLOWED && (session.listening != null || session.preparedListening != null)
}

sealed interface UiIntent {
    val origin: UiOrigin
    data class CatalogViewChanged(override val origin: UiOrigin, val view: CatalogView) : UiIntent
    data class CatalogFilterChanged(override val origin: UiOrigin, val category: CatalogCategoryId?) : UiIntent
    data class MoreCatalogView(override val origin: UiOrigin) : UiIntent
    data class SelectCatalogArticle(override val origin: UiOrigin, val key: CatalogArticleKey, val originView: CatalogView) : UiIntent
    data class ReadAssessment(override val origin: UiOrigin, val lessonId: UUID, val attemptId: UUID, val entry: String) : UiIntent
    data class RequestAssessmentReview(override val origin: UiOrigin, val lessonId: UUID, val attemptId: UUID, val entry: String) : UiIntent
    data class RetryAssessmentReview(override val origin: UiOrigin, val lessonId: UUID, val attemptId: UUID, val entry: String) : UiIntent
    data class Band(override val origin: UiOrigin, val value: LearningBand) : UiIntent
    data class ReadRecommendation(override val origin: UiOrigin) : UiIntent
    data class Recommendation(override val origin: UiOrigin, val action: RecommendationAction, val explicitReview: Boolean = false) : UiIntent
    data class ListeningOnly(override val origin: UiOrigin, val enabled: Boolean) : UiIntent
    data class ShowOriginalFeedback(override val origin: UiOrigin, val lessonId: UUID, val attemptId: UUID) : UiIntent
    data class ShowSpeakingRecord(override val origin: UiOrigin, val lessonId: UUID) : UiIntent
    data class CloseSpeakingRecord(override val origin: UiOrigin) : UiIntent
    data class MoreSpeakingRecords(override val origin: UiOrigin) : UiIntent
    data class HideSpeakingRecord(override val origin: UiOrigin, val lessonId: UUID, val hidden: Boolean) : UiIntent
    data class RepracticeRecord(override val origin: UiOrigin, val lessonId: UUID) : UiIntent
    data class ReplayHistory(override val origin: UiOrigin, val selectionId: UUID, val checkpointId: UUID, val alternateMode: String? = null) : UiIntent
    data class ReadHistory(override val origin: UiOrigin) : UiIntent
    data class ResumePractice(override val origin: UiOrigin, val lessonId: UUID) : UiIntent
    data class DeleteHistory(override val origin: UiOrigin) : UiIntent
    data class RetryDeletion(override val origin: UiOrigin) : UiIntent
    data class Logout(override val origin: UiOrigin) : UiIntent
    data class StartPractice(override val origin: UiOrigin) : UiIntent
    data class StartAnswer(override val origin: UiOrigin) : UiIntent
    data class FinishAnswer(override val origin: UiOrigin) : UiIntent
    data class CancelAnswer(override val origin: UiOrigin) : UiIntent
    data class RetryFeedback(override val origin: UiOrigin, val readOnly: Boolean = false) : UiIntent
    data class RetryAnswer(override val origin: UiOrigin) : UiIntent
    data class FinishPractice(override val origin: UiOrigin, val declineRetry: Boolean = false) : UiIntent
    data class RequestHelp(override val origin: UiOrigin, val request: HelpRequest) : UiIntent
    data class CloseSupport(override val origin: UiOrigin) : UiIntent
    data class SupportWitness(override val origin: UiOrigin, val witness: ArtifactWitness) : UiIntent
    data class OpenArticleQuestion(override val origin: UiOrigin) : UiIntent
    data class ReturnFromQuestion(override val origin: UiOrigin) : UiIntent
    data class RetryQuestion(override val origin: UiOrigin) : UiIntent
    data class LoadSentences(override val origin: UiOrigin) : UiIntent
    data class OpenSentence(override val origin: UiOrigin, val sentenceId: UUID) : UiIntent
    data class SpeakSentence(override val origin: UiOrigin) : UiIntent
    data class ShowSentenceMeaning(override val origin: UiOrigin) : UiIntent
    data class CloseReviewTool(override val origin: UiOrigin) : UiIntent
    data class PreviewSettings(override val origin: UiOrigin) : UiIntent
    data class RetryFollowup(override val origin: UiOrigin) : UiIntent
    data class SkipFollowup(override val origin: UiOrigin) : UiIntent
    data class Refresh(override val origin: UiOrigin) : UiIntent
    data class Navigate(override val origin: UiOrigin, val screen: SessionScreen) : UiIntent
    data class SelectArticle(override val origin: UiOrigin, val articleId: UUID?, val freshStream: Boolean = false) : UiIntent
    data class RetrySelection(override val origin: UiOrigin) : UiIntent
    data class CancelSelection(override val origin: UiOrigin) : UiIntent
    data class ReadyAlternative(override val origin: UiOrigin) : UiIntent
    data class PendingOriginal(override val origin: UiOrigin) : UiIntent
    data class Resume(override val origin: UiOrigin) : UiIntent
    data class PlayPause(override val origin: UiOrigin, val wantPlay: Boolean) : UiIntent
    data class Stop(override val origin: UiOrigin) : UiIntent
    data class Seek(override val origin: UiOrigin, val offsetMs: Long) : UiIntent
    data class ReaderView(override val origin: UiOrigin, val language: ReaderLanguage) : UiIntent
    data class Topic(override val origin: UiOrigin, val topic: String) : UiIntent
    data class Mode(override val origin: UiOrigin, val mode: String) : UiIntent
    data class FollowupChoice(override val origin: UiOrigin, val policy: String) : UiIntent
    data class Appearance(override val origin: UiOrigin, val value: DeviceAppearance) : UiIntent
    data class ApplySettings(override val origin: UiOrigin) : UiIntent
    data class CancelSettings(override val origin: UiOrigin, val destination: SessionScreen? = null) : UiIntent
    data class ChangeFormat(override val origin: UiOrigin, val mode: ListeningMode) : UiIntent
    data class MoreCatalog(override val origin: UiOrigin) : UiIntent
}

/** Presentation only. Server progress, accepted input and output permissions stay in their owners. */
data class LearningPresentation(val owner: OwnerContext? = null, val articleTitle: String? = null, val practice: PracticeSession? = null,
    val channel: InteractionChannel = InteractionChannel.PARKED, val lastAttemptId: UUID? = null,
    val support: PresentationUiBinding? = null, val notice: String? = null,
    val questionOrigin: QuestionOrigin? = null, val questionStatus: LoadStatus = LoadStatus.NOT_REQUESTED,
    val questionAnswer: String? = null, val questionNotice: String? = null, val titleConfirmationUsed: Boolean = false,
    val microphonePermissionNeeded: Boolean = false)
