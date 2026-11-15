package io.github.driveenglishlab.pleos.domain

import java.util.UUID

enum class PracticeModalPhase { QUESTION, CAPTURING, PROCESSING, FEEDBACK, RETRY, HELP }
enum class PracticeModalVisibility { VISIBLE, CLOSED }
enum class PracticeModalCloseReason { CLOSE, BACK, SAVE_EXIT, FINISH, RESTRICTED, OPEN_SETTINGS, RESTORED, OWNER_CHANGED }
/** The existing lesson remains the business source; modal visibility never marks completion. */
data class PracticeModalLesson(val phase: PracticeModalPhase, val revision: Long, val attemptId: UUID? = null,
    val retryUsed: Boolean = false, val complete: Boolean = false) {
    init { require(revision >= 0 && phase !in setOf(PracticeModalPhase.CAPTURING, PracticeModalPhase.HELP)) }
}
data class PracticeModalToken(val owner: OwnerContext, val selectionId: UUID, val lessonId: UUID,
    val entryId: UUID, val generation: Long) { init { require(generation >= 1) } }
data class PracticeModalSession(val token: PracticeModalToken, val phase: PracticeModalPhase,
    val visibility: PracticeModalVisibility, val original: ReviewReturnSnapshot, val origin: ReviewEntryOrigin,
    val lesson: PracticeModalLesson, val parentState: PracticeModalPhase? = null, val captureToken: UUID? = null,
    val captureReturnState: PracticeModalPhase? = null, val helpRequestId: UUID? = null) {
    init {
        require(original.profile == ProfileContext(token.owner.profileId, token.owner.privacyEpoch) && original.selectionId == token.selectionId)
        require((phase == PracticeModalPhase.HELP) == (parentState != null && helpRequestId != null))
        require(parentState !in setOf(PracticeModalPhase.HELP, PracticeModalPhase.CAPTURING))
        if (phase == PracticeModalPhase.CAPTURING) require(captureToken != null && captureReturnState in setOf(PracticeModalPhase.QUESTION, PracticeModalPhase.RETRY))
        else require(captureToken == null && captureReturnState == null)
    }
    val visible get() = visibility == PracticeModalVisibility.VISIBLE
    val canReturnToHistory get() = origin == ReviewEntryOrigin.HISTORY
}

data class PracticeModalAdmission(val originalSaved: Boolean, val newsOutputStopped: Boolean,
    val offerCancellationDurable: Boolean, val previousWorkSaved: Boolean, val rightsAllowed: Boolean)
sealed interface PracticeModalEffect {
    data object StopCaptureAndDiscardUnsubmitted : PracticeModalEffect
    data object StopAuxiliaryOutput : PracticeModalEffect
    data object CancelExpectedOutputAndWait : PracticeModalEffect
    data class PersistClose(val token: PracticeModalToken, val reason: PracticeModalCloseReason) : PracticeModalEffect
    data class StartExplicitCapture(val token: PracticeModalToken, val captureToken: UUID) : PracticeModalEffect
    data class OpenRequestedHelp(val token: PracticeModalToken, val requestId: UUID) : PracticeModalEffect
    data class StoreOriginalLessonOnly(val token: PracticeModalToken, val lesson: PracticeModalLesson) : PracticeModalEffect
    data object ReturnToArticleAwaitExplicitChoice : PracticeModalEffect
}
data class PracticeModalChange(val session: PracticeModalSession, val effects: List<PracticeModalEffect> = emptyList())

sealed interface PracticeModalEvent {
    data class BeginCapture(val captureToken: UUID) : PracticeModalEvent
    data class CaptureCancelled(val captureToken: UUID) : PracticeModalEvent
    /** Existing capture admission has durably accepted this exact answer; request/queue is insufficient. */
    data class AnswerAccepted(val token: PracticeModalToken, val captureToken: UUID, val lesson: PracticeModalLesson) : PracticeModalEvent
    data class LessonUpdated(val token: PracticeModalToken, val lesson: PracticeModalLesson) : PracticeModalEvent
    data class RetryRequested(val lesson: PracticeModalLesson) : PracticeModalEvent
    data class HelpRequested(val requestId: UUID) : PracticeModalEvent
    data object HelpClosed : PracticeModalEvent
    data class Close(val reason: PracticeModalCloseReason) : PracticeModalEvent
    data object Restore : PracticeModalEvent
    data object RecheckAccess : PracticeModalEvent
}

object PracticeModalReducer {
    /** Call only for explicit entry after stop/save/local cancellation. No capture/output is issued. */
    fun open(token: PracticeModalToken, original: ReviewReturnSnapshot, origin: ReviewEntryOrigin,
        lesson: PracticeModalLesson, access: AccessState, currentOwner: OwnerContext?, admission: PracticeModalAdmission): PracticeModalSession? {
        if (currentOwner != token.owner || !access.detailsOpen || !admission.originalSaved || !admission.newsOutputStopped ||
            !admission.previousWorkSaved || !admission.rightsAllowed || original.phase == SegmentPhase.ARTICLE_OFFER && !admission.offerCancellationDurable) return null
        return PracticeModalSession(token, lesson.phase, PracticeModalVisibility.VISIBLE, original, origin, lesson)
    }

    fun reduce(state: PracticeModalSession, event: PracticeModalEvent, access: AccessState, currentOwner: OwnerContext?): PracticeModalChange {
        if (event is PracticeModalEvent.LessonUpdated || event is PracticeModalEvent.AnswerAccepted) {
            val token = if (event is PracticeModalEvent.LessonUpdated) event.token else (event as PracticeModalEvent.AnswerAccepted).token
            val lesson = if (event is PracticeModalEvent.LessonUpdated) event.lesson else (event as PracticeModalEvent.AnswerAccepted).lesson
            // Persistence belongs to the original lesson; an old entry callback cannot reopen the
            // modal, speak, navigate, acknowledge displayed feedback, or mutate a newer entry.
            if (token != state.token || !state.visible || currentOwner != state.token.owner)
                return PracticeModalChange(state, listOf(PracticeModalEffect.StoreOriginalLessonOnly(token, lesson)))
        }
        if (currentOwner != state.token.owner) return close(state, PracticeModalCloseReason.OWNER_CHANGED)
        if (event == PracticeModalEvent.Restore) return close(state, PracticeModalCloseReason.RESTORED)
        if (!access.detailsOpen) return close(state, PracticeModalCloseReason.RESTRICTED)
        if (event is PracticeModalEvent.Close) return close(state, event.reason)
        if (!state.visible) return PracticeModalChange(state)
        return when (event) {
            is PracticeModalEvent.BeginCapture -> {
                if (!access.canCapture || state.phase !in setOf(PracticeModalPhase.QUESTION, PracticeModalPhase.RETRY)) PracticeModalChange(state)
                else PracticeModalChange(state.copy(phase = PracticeModalPhase.CAPTURING, captureToken = event.captureToken, captureReturnState = state.phase),
                    listOf(PracticeModalEffect.CancelExpectedOutputAndWait, PracticeModalEffect.StartExplicitCapture(state.token, event.captureToken)))
            }
            is PracticeModalEvent.CaptureCancelled -> {
                if (state.captureToken != event.captureToken) PracticeModalChange(state)
                else PracticeModalChange(state.copy(phase = requireNotNull(state.captureReturnState), captureToken = null, captureReturnState = null),
                    listOf(PracticeModalEffect.StopCaptureAndDiscardUnsubmitted))
            }
            is PracticeModalEvent.AnswerAccepted -> {
                if (state.captureToken != event.captureToken || event.lesson.phase != PracticeModalPhase.PROCESSING || event.lesson.attemptId == null || event.lesson.revision < state.lesson.revision)
                    PracticeModalChange(state, listOf(PracticeModalEffect.StoreOriginalLessonOnly(event.token, event.lesson)))
                else PracticeModalChange(state.copy(phase = PracticeModalPhase.PROCESSING, lesson = event.lesson, captureToken = null, captureReturnState = null))
            }
            is PracticeModalEvent.LessonUpdated -> {
                if (event.lesson.revision < state.lesson.revision || event.lesson.revision == state.lesson.revision && event.lesson != state.lesson) PracticeModalChange(state)
                else if (state.phase == PracticeModalPhase.HELP) PracticeModalChange(state.copy(lesson = event.lesson, parentState = event.lesson.phase))
                else if (state.phase == PracticeModalPhase.CAPTURING) PracticeModalChange(state, listOf(PracticeModalEffect.StoreOriginalLessonOnly(event.token, event.lesson)))
                else PracticeModalChange(state.copy(phase = event.lesson.phase, lesson = event.lesson))
            }
            is PracticeModalEvent.RetryRequested -> {
                if (state.phase != PracticeModalPhase.FEEDBACK || state.lesson.retryUsed || event.lesson.phase != PracticeModalPhase.RETRY || event.lesson.revision < state.lesson.revision)
                    PracticeModalChange(state)
                else PracticeModalChange(state.copy(phase = PracticeModalPhase.RETRY, lesson = event.lesson))
            }
            is PracticeModalEvent.HelpRequested -> {
                val previous = if (state.phase == PracticeModalPhase.CAPTURING) requireNotNull(state.captureReturnState) else state.parentState ?: state.phase
                PracticeModalChange(state.copy(phase = PracticeModalPhase.HELP, parentState = previous, helpRequestId = event.requestId, captureToken = null, captureReturnState = null),
                    listOf(PracticeModalEffect.StopCaptureAndDiscardUnsubmitted, PracticeModalEffect.StopAuxiliaryOutput,
                        PracticeModalEffect.CancelExpectedOutputAndWait, PracticeModalEffect.OpenRequestedHelp(state.token, event.requestId)))
            }
            PracticeModalEvent.HelpClosed -> if (state.phase == PracticeModalPhase.HELP)
                PracticeModalChange(state.copy(phase = requireNotNull(state.parentState), parentState = null, helpRequestId = null), listOf(PracticeModalEffect.StopAuxiliaryOutput))
                else PracticeModalChange(state)
            PracticeModalEvent.RecheckAccess -> if (state.phase == PracticeModalPhase.CAPTURING && !access.canCapture)
                PracticeModalChange(state.copy(phase = requireNotNull(state.captureReturnState), captureToken = null, captureReturnState = null), listOf(PracticeModalEffect.StopCaptureAndDiscardUnsubmitted))
                else PracticeModalChange(state)
            is PracticeModalEvent.Close, PracticeModalEvent.Restore -> error("Handled above.")
        }
    }
    private fun close(state: PracticeModalSession, reason: PracticeModalCloseReason): PracticeModalChange {
        if (!state.visible) return PracticeModalChange(state)
        val phase = when (state.phase) {
            PracticeModalPhase.CAPTURING -> requireNotNull(state.captureReturnState)
            PracticeModalPhase.HELP -> requireNotNull(state.parentState)
            else -> state.phase
        }
        return PracticeModalChange(state.copy(phase = phase, visibility = PracticeModalVisibility.CLOSED, parentState = null, helpRequestId = null,
            captureToken = null, captureReturnState = null), listOf(PracticeModalEffect.StopCaptureAndDiscardUnsubmitted, PracticeModalEffect.StopAuxiliaryOutput,
            PracticeModalEffect.CancelExpectedOutputAndWait, PracticeModalEffect.PersistClose(state.token, reason), PracticeModalEffect.ReturnToArticleAwaitExplicitChoice))
    }
}
