package io.github.driveenglishlab.pleos.domain

import java.util.UUID

enum class PracticeRequestState { DEVICE_ONLY, PROCESSING, PROCESSING_NOTICE, WAIT_EXPIRED, STORED, FAILED }

/** Read-only server anchors; a held input never advances a valid first/retry locally. */
data class PracticeProgress(val version: Long, val validFirstAttemptId: UUID?, val validRetryAttemptId: UUID?,
    val pendingAttemptId: UUID?, val pendingFeedbackAttemptId: UUID?, val phase: String,
    val contentSuccess: Boolean?, val retryOutcome: String?, val endReason: String?) {
    init { require(version >= 1) }
    val nextTurn: AttemptTurn? get() = when {
        pendingAttemptId != null || pendingFeedbackAttemptId != null || phase == "completed" -> null
        validFirstAttemptId == null -> AttemptTurn.FIRST
        validRetryAttemptId == null && phase in setOf("retry_offer", "question_wait", "suspended") -> AttemptTurn.RETRY
        else -> null
    }
}

/** Display-safe projection deliberately excludes optional examples/prepared artifacts. */
data class PracticeFeedback(val attemptId: UUID, val judgement: Judgement, val focus: String,
    val feedbackKo: String, val correctionText: String?, val englishAttemptStatus: EnglishAttemptStatus,
    val progress: PracticeProgress)

data class PracticeSession(val owner: OwnerContext, val lesson: LessonInput, val questionEn: String,
    val progress: PracticeProgress?, val requestState: PracticeRequestState = PracticeRequestState.DEVICE_ONLY,
    val feedback: PracticeFeedback? = null) {
    init { require(questionEn.isNotBlank() && questionEn.codePointCount(0, questionEn.length) in 1..500) }
}

/** Timeouts affect foreground waiting only; durable accepted input/results remain available. */
class PracticeRequestClock {
    private var beganAt: Long? = null
    fun start(nowMs: Long) { require(nowMs >= 0); beganAt = nowMs }
    fun clear() { beganAt = null }
    fun state(nowMs: Long): PracticeRequestState {
        val began = beganAt ?: return PracticeRequestState.DEVICE_ONLY
        require(nowMs >= began)
        return when {
            nowMs - began >= 10_000 -> PracticeRequestState.WAIT_EXPIRED
            nowMs - began >= 2_000 -> PracticeRequestState.PROCESSING_NOTICE
            else -> PracticeRequestState.PROCESSING
        }
    }
}

/** One explicit original-language confirmation for this capture, before immutable acceptance. */
class CaptureLanguageConfirmation(val captureId: UUID, val original: Capture) {
    private var requested = false
    private var resolved = false
    fun request(): Boolean {
        if (requested || original.englishEvidence != EnglishEvidence.UNVERIFIED) return false
        requested = true
        return true
    }
    fun confirm(sameCaptureId: UUID, language: SpokenLanguage, evidenceRef: String): Capture? {
        if (!requested || resolved || sameCaptureId != captureId) return null
        resolved = true
        if (language !in setOf(SpokenLanguage.EN, SpokenLanguage.MIXED)) return null
        // The external verifier must bind this evidence to the specific capture, never a generic yes.
        return original.copy(spokenLanguage = language, englishEvidence = EnglishEvidence.SPECIFIC_CONFIRMATION,
            languageEvidenceRef = evidenceRef)
    }
}

data class FeedbackPresentationWitness(val owner: OwnerContext, val lessonId: UUID, val attemptId: UUID,
    val mode: String, val outputInstanceId: UUID, val evidenceRef: String) {
    init { require(mode in setOf("parked_visible", "voice_output_completed", "explicit_user_action")); requireReference(evidenceRef) }
}
fun interface FeedbackPresentationVerifier { suspend fun verify(witness: FeedbackPresentationWitness): Boolean }
