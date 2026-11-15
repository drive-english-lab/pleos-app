package io.github.driveenglishlab.pleos.domain

import java.util.UUID

enum class ListeningMode { ORIGINAL_FULL, BRIEFING }
enum class Followup { EN_SUMMARY, KO_SUMMARY, KO_TRANSLATION, NONE }
enum class HelpKind { FACT_EXPLANATION, SENTENCE_STARTER, PARTIAL_CORRECTION, COMPLETE_EXAMPLE }
enum class CaptureFidelity { UNKNOWN, CONFIRMED_FORM, LITERAL_CONTRACT_VERIFIED }
enum class Judgement { SUFFICIENT, NEEDS_MORE, FACTUALLY_INCORRECT, UNASSESSABLE }

/** Lossless source6 main reference. Legacy and prepared keep their own real identities. */
sealed interface SegmentContentRef {
    val articleId: UUID
    val sourceSnapshotId: UUID
    val listeningMode: ListeningMode
}

data class ContentRef(
    override val articleId: UUID,
    val bundleId: UUID,
    override val sourceSnapshotId: UUID,
    val listeningScriptId: UUID,
    override val listeningMode: ListeningMode,
) : SegmentContentRef

data class BasicQuestionRef(val articleId: UUID, val basicQuestionVersionId: UUID)

sealed interface PracticeRef {
    val articleId: UUID
    val basicQuestionVersionId: UUID
    val taskIdentity: UUID
    val supportTaskIdentity: UUID? get() = taskIdentity
    val questionRef: BasicQuestionRef get() = BasicQuestionRef(articleId, basicQuestionVersionId)
}

data class PracticeContentRef(val content: ContentRef, val taskId: UUID, override val basicQuestionVersionId: UUID) : PracticeRef {
    override val articleId get() = content.articleId
    override val taskIdentity get() = taskId
}

enum class CapturePath { GLEO, PARKED_STT }
enum class SpokenLanguage { EN, KO, MIXED, UNKNOWN }
enum class EnglishEvidence { VERIFIED_PATH, SPECIFIC_CONFIRMATION, VERIFIED_NON_ENGLISH, UNVERIFIED }
/** Read-only server result. Never serialize this into a client submission. */
enum class EnglishAttemptStatus { VERIFIED_ENGLISH, NOT_ENGLISH, UNVERIFIED }

/** Claims retain the original speech language separately from normalized text and form. */
data class Capture(
    val path: CapturePath,
    val formFidelity: CaptureFidelity,
    val uncertainSpans: List<String>,
    val spokenLanguage: SpokenLanguage,
    val englishEvidence: EnglishEvidence,
    val languageEvidenceRef: String?,
) {
    init {
        require(uncertainSpans.size <= 8 && uncertainSpans.distinct().size == uncertainSpans.size)
        require(uncertainSpans.all { it.codePointCount(0, it.length) in 1..240 })
        when (englishEvidence) {
            EnglishEvidence.VERIFIED_PATH, EnglishEvidence.SPECIFIC_CONFIRMATION -> {
                require(spokenLanguage in setOf(SpokenLanguage.EN, SpokenLanguage.MIXED))
                requireReference(languageEvidenceRef)
            }
            EnglishEvidence.VERIFIED_NON_ENGLISH -> {
                require(spokenLanguage in setOf(SpokenLanguage.KO, SpokenLanguage.MIXED))
                requireReference(languageEvidenceRef)
            }
            EnglishEvidence.UNVERIFIED -> require(languageEvidenceRef == null)
        }
        if (spokenLanguage == SpokenLanguage.UNKNOWN) require(englishEvidence == EnglishEvidence.UNVERIFIED)
    }

    companion object {
        fun unverified(path: CapturePath) = Capture(path, CaptureFidelity.UNKNOWN, emptyList(),
            SpokenLanguage.UNKNOWN, EnglishEvidence.UNVERIFIED, null)
    }
}

internal fun requireReference(value: String?) {
    require(value != null && value.codePointCount(0, value.length) in 1..160)
}

data class ProfileContext(val profileId: UUID, val privacyEpoch: Long) {
    init { require(privacyEpoch >= 0) }
}

data class DialogueProgress(val hintMethodAnnounced: Boolean) {
    fun merge(other: DialogueProgress) = DialogueProgress(hintMethodAnnounced || other.hintMethodAnnounced)
    companion object { fun newLesson() = DialogueProgress(false) }
}

/** A witnessed completion fact, not a stale lesson-phase update or a live output grant. */
data class DialogueProgressObservation(
    val lessonId: UUID,
    val hintMethodAnnounced: Boolean,
    val outputInstanceId: UUID,
    val adapterEvidenceRef: String,
) {
    init { require(hintMethodAnnounced); requireReference(adapterEvidenceRef) }
}

data class DialogueProgressEvent(val eventId: UUID, val observation: DialogueProgressObservation)

enum class LessonInputPhase { QUESTION_WAIT, FEEDBACK_PENDING, RETRY_OFFER, SUSPENDED }
data class LessonInput(
    val lessonId: UUID, val contentRef: PracticeRef, val phase: LessonInputPhase,
    val helpReceived: HelpSnapshot, val returnCheckpointId: UUID?, val dialogueProgress: DialogueProgress,
)

sealed interface Capability {
    data class Verified(val evidenceRef: String) : Capability { init { requireReference(evidenceRef) } }
    data class Unavailable(val reason: String) : Capability
}
data class VoiceCapabilities(
    val outputCompletion: Capability = Capability.Unavailable("not_verified"),
    val exactOutputContent: Capability = Capability.Unavailable("not_verified"),
    val inputActivity: Capability = Capability.Unavailable("not_verified"),
    val proactiveSession: Capability = Capability.Unavailable("not_verified"),
    val originalEnglish: Capability = Capability.Unavailable("not_verified"),
    val literalForm: Capability = Capability.Unavailable("not_verified"),
    val microphoneArbitration: Capability = Capability.Unavailable("not_verified"),
)

data class LearningSettings(
    val topics: Set<String> = emptySet(),
    val listeningMode: ListeningMode = ListeningMode.ORIGINAL_FULL,
    val followup: Followup? = null,
    val explicitFields: Set<String> = emptySet(),
    val onboardingComplete: Boolean = false,
    val version: Long = 1,
) {
    init {
        require(topics.size <= 20 && topics.all { it.codePointCount(0, it.length) in 1..80 })
        require(explicitFields.size <= 3 && explicitFields.all {
            it in setOf("topics", "listening_mode", "followup_policy")
        })
        require(version >= 1)
        require(!onboardingComplete || followup != null)
    }
}

data class HelpSnapshot(val kinds: Set<HelpKind> = emptySet(), val refs: Set<String> = emptySet()) {
    init {
        require(kinds.size <= 4 && refs.size <= 16)
        require(refs.all { it.codePointCount(0, it.length) in 1..160 })
    }

    fun union(other: HelpSnapshot) = HelpSnapshot(kinds + other.kinds, refs + other.refs)
}

/** Every late result must match this context before it can become visible or audible. */
data class OwnerContext(
    val profileId: UUID,
    val privacyEpoch: Long,
    val revision: Long,
    val presentationToken: UUID,
) {
    init { require(privacyEpoch >= 0 && revision >= 0) }
}

enum class VehiclePosition { PARKED, DRIVE_STOPPED, MOVING_OR_REVERSE_NEUTRAL, UNKNOWN }
/** Raw-only compatibility: ALLOWED denotes actually observed AWAKE, never component approval. */
enum class PowerAvailability { UNKNOWN, ALLOWED, DISALLOWED, SUSPENDING, SUSPENDED }

data class SafetyState(
    val position: VehiclePosition = VehiclePosition.UNKNOWN,
    val uxKnown: Boolean = false,
    val detailsAllowed: Boolean = false,
    val directInputAllowed: Boolean = false,
    val systemBlocked: Boolean = false,
    val generation: Long = 0,
)

data class WindowState(
    val visible: Boolean = false,
    val focused: Boolean = false,
    val generation: Long = 0,
)

data class AccessState(
    val safety: SafetyState = SafetyState(),
    val window: WindowState = WindowState(),
    val power: PowerAvailability = PowerAvailability.UNKNOWN,
    val microphoneGranted: Boolean = false,
    val directProviderVerified: Boolean = false,
    val inputArbitrationVerified: Boolean = false,
    val audioAllowsReply: Boolean = false,
    val ownerValid: Boolean = false,
    val components: ComponentPolicies = ComponentPolicies(),
    val mediaRouteAvailable:Boolean=false,
    val mediaFocusAllowed:Boolean=false,
    val backgroundMediaAllowed:Boolean=false,
    val gleoRouteAvailable:Boolean=false,
    val gleoVehicleAllowed:Boolean=false,
    val parkedOutputAvailable:Boolean=false,
    val gleoPermissionGeneration:Long=0,
    val gleoReplyFocusAllowed:Boolean=false,
    val directReplyFocusAllowed:Boolean=false,
) {
    val suspending:Boolean get()=power in setOf(PowerAvailability.SUSPENDING,PowerAvailability.SUSPENDED)
    val detailsOpen:Boolean get()=FeatureAccessEvaluator.evaluate(FeatureScope.DETAILS,this).allowed
    val canCapture:Boolean get()=FeatureAccessEvaluator.evaluate(FeatureScope.DIRECT_CAPTURE,this).allowed
    val mediaReady:Boolean get()=FeatureAccessEvaluator.evaluate(FeatureScope.MEDIA,this,requireFocus=false).allowed
    val mediaAllowed:Boolean get()=FeatureAccessEvaluator.evaluate(FeatureScope.MEDIA,this).allowed
    val gleoReady:Boolean get()=FeatureAccessEvaluator.evaluate(FeatureScope.GLEO,this,requireFocus=false).allowed
    val gleoAllowed:Boolean get()=FeatureAccessEvaluator.evaluate(FeatureScope.GLEO,this).allowed
    val parkedReplyReady:Boolean get()=detailsOpen && components.audio.current(components.generation) && components.audio.allowed && parkedOutputAvailable
    val parkedReplyAllowed:Boolean get()=parkedReplyReady && directReplyFocusAllowed
    val persistenceAllowed:Boolean get()=FeatureAccessEvaluator.evaluate(FeatureScope.PERSISTENCE,this).allowed
    val noticeAllowed:Boolean get()=FeatureAccessEvaluator.evaluate(FeatureScope.NOTICE,this).allowed
}

enum class PlaybackIntent { PLAY, USER_PAUSED, STOPPED }
enum class ListeningStage { MAIN, FOLLOWUP, ARTICLE_OFFER }
data class PlaybackPosition(val assetId: UUID, val stage: ListeningStage, val positionMs: Long, val durationMs: Long) {
    init { require(durationMs > 0 && positionMs in 0..durationMs) }
}

data class ReturnPoint(val playback: PlaybackPosition, val intent: PlaybackIntent) {
    fun afterConversation(): ReturnPoint = copy(playback = playback.copy(positionMs = (playback.positionMs - 5_000).coerceAtLeast(0)))
}
