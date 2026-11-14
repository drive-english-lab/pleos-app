package io.github.driveenglishlab.pleos.domain

import java.util.UUID

enum class ReviewOrigin { REGULAR, HISTORY_REPLAY }
enum class ReviewEndPolicy { REGULAR_AUTO, MANUAL_HISTORY, MANUAL_CANCELLED }
enum class ReviewResumeKind { CHECKPOINT, LESSON }
data class ReviewResumeRef(val kind: ReviewResumeKind, val recordId: UUID)
enum class ReviewAuxiliaryKind { SENTENCE, PREVIEW, PRACTICE }
enum class ReviewEntryOrigin { NEWS, HISTORY, PRACTICE }

/** Exact original checkpoint facts, not a command to resume. Segment and legacy remain distinct. */
data class ReviewReturnSnapshot(val profile: ProfileContext, val selectionId: UUID, val checkpointId: UUID,
    val checkpointVersion: Long, val lessonId: UUID?, val phase: SegmentPhase, val cursor: PlaybackCursor6,
    val playIntent: Boolean, val pauseReason: PauseReason, val resumeVersion: Long) {
    init {
        require(checkpointVersion >= 1 && resumeVersion >= 0)
        when (cursor) {
            is PlaybackCursor6.Segment -> require(cursor.phase == phase)
            is PlaybackCursor6.LegacyTime -> require(cursor.phase.name == phase.name)
        }
    }
}

data class ReviewPracticeEntry(val entryId: UUID, val lessonId: UUID, val visible: Boolean)

/** A server context projection. Cache absence never implies REGULAR_AUTO. */
data class ReviewContext(val profile: ProfileContext, val selectionId: UUID, val version: Long,
    val origin: ReviewOrigin, val endPolicy: ReviewEndPolicy, val offerId: UUID?, val cancelledOfferId: UUID?,
    val sourceSelectionId: UUID?, val previousResumeRef: ReviewResumeRef?, val returnSnapshot: ReviewReturnSnapshot?,
    val practiceEntry: ReviewPracticeEntry?) {
    init {
        require(version >= 1)
        if (origin == ReviewOrigin.HISTORY_REPLAY) require(endPolicy == ReviewEndPolicy.MANUAL_HISTORY && sourceSelectionId != null)
        if (origin == ReviewOrigin.REGULAR) require(endPolicy != ReviewEndPolicy.MANUAL_HISTORY && sourceSelectionId == null)
        if (cancelledOfferId != null) require(offerId == cancelledOfferId && endPolicy != ReviewEndPolicy.REGULAR_AUTO)
        returnSnapshot?.let { require(it.profile == profile && it.selectionId == selectionId) }
    }
}

/** Durable local cancellation is an overlay until the matching server CAS is acknowledged.
 * It must be persisted before admitting auxiliary execution, even when material lookup fails.
 */
data class ReviewPolicyState(val context: ReviewContext?, val localCancelledOfferId: UUID? = null,
    val auxiliary: ReviewAuxiliaryKind? = null, val original: ReviewReturnSnapshot? = context?.returnSnapshot,
    val explicitResumeRequired: Boolean = false) {
    init {
        localCancelledOfferId?.let { require(context?.offerId == it) }
        original?.let { require(context != null && context.profile == it.profile && context.selectionId == it.selectionId) }
    }
    val effectiveEndPolicy: ReviewEndPolicy? get() = when {
        context?.origin == ReviewOrigin.HISTORY_REPLAY -> ReviewEndPolicy.MANUAL_HISTORY
        localCancelledOfferId != null || context?.cancelledOfferId != null -> ReviewEndPolicy.MANUAL_CANCELLED
        else -> context?.endPolicy
    }
    val canAutoOffer get() = context?.offerId != null && effectiveEndPolicy == ReviewEndPolicy.REGULAR_AUTO &&
        auxiliary == null && !explicitResumeRequired && context.practiceEntry?.visible != true
}

sealed interface ReviewContextEffect {
    data class PersistLocalCancellation(val profile: ProfileContext, val selectionId: UUID, val offerId: UUID, val expectedVersion: Long) : ReviewContextEffect
    data class SaveOriginalAndStopOutput(val snapshot: ReviewReturnSnapshot) : ReviewContextEffect
    data object StopCurrentAuxiliary : ReviewContextEffect
    data object WaitForExplicitOriginalResume : ReviewContextEffect
}
data class ReviewContextChange(val state: ReviewPolicyState, val effects: List<ReviewContextEffect> = emptyList())

object ReviewContextPolicy {
    fun enterAuxiliary(state: ReviewPolicyState, kind: ReviewAuxiliaryKind, snapshot: ReviewReturnSnapshot): ReviewContextChange {
        val context = requireNotNull(state.context)
        require(snapshot.profile == context.profile && snapshot.selectionId == context.selectionId)
        // First return point survives help switches; a second auxiliary never overwrites it.
        val original = state.original ?: snapshot
        val cancellation = if (snapshot.phase == SegmentPhase.ARTICLE_OFFER) context.offerId else null
        val effects = mutableListOf<ReviewContextEffect>()
        if (state.auxiliary != null) effects += ReviewContextEffect.StopCurrentAuxiliary
        if (cancellation != null && state.localCancelledOfferId != cancellation && context.cancelledOfferId != cancellation)
            effects += ReviewContextEffect.PersistLocalCancellation(context.profile, context.selectionId, cancellation, context.version)
        effects += ReviewContextEffect.SaveOriginalAndStopOutput(original)
        return ReviewContextChange(state.copy(localCancelledOfferId = cancellation ?: state.localCancelledOfferId,
            auxiliary = kind, original = original, explicitResumeRequired = true), effects)
    }
    fun closeAuxiliary(state: ReviewPolicyState): ReviewContextChange = ReviewContextChange(
        state.copy(auxiliary = null, explicitResumeRequired = true), listOf(ReviewContextEffect.StopCurrentAuxiliary, ReviewContextEffect.WaitForExplicitOriginalResume))

    /** Unknown/old client contexts stay manual. Restore never restores a visible modal or timer. */
    fun restored(context: ReviewContext?, localCancelledOfferId: UUID? = null): ReviewPolicyState = ReviewPolicyState(
        context, localCancelledOfferId,
        original = context?.returnSnapshot, explicitResumeRequired = true)

    /** A same-selection server read cannot undo history origin or a witnessed cancellation. */
    fun acknowledge(state: ReviewPolicyState, incoming: ReviewContext): ReviewPolicyState {
        val old = state.context
        if (old != null) {
            require(incoming.profile == old.profile && incoming.selectionId == old.selectionId && incoming.origin == old.origin &&
                incoming.sourceSelectionId == old.sourceSelectionId && incoming.version >= old.version)
            if (old.offerId != null) require(incoming.offerId == old.offerId)
            if (old.cancelledOfferId != null) require(incoming.cancelledOfferId == old.cancelledOfferId && incoming.endPolicy != ReviewEndPolicy.REGULAR_AUTO)
            if (incoming.version == old.version) require(incoming == old)
        }
        val local = state.localCancelledOfferId?.takeUnless { incoming.cancelledOfferId == it }
        return state.copy(context = incoming, localCancelledOfferId = local, original = state.original ?: incoming.returnSnapshot)
    }

    /** Only a new explicitly selected ordinary article may replace the manual context. */
    fun explicitRegularSelection(previous: ReviewPolicyState, incoming: ReviewContext): ReviewPolicyState {
        require(incoming.origin == ReviewOrigin.REGULAR && incoming.endPolicy == ReviewEndPolicy.REGULAR_AUTO && incoming.cancelledOfferId == null)
        previous.context?.let { require(incoming.profile == it.profile && incoming.selectionId != it.selectionId) }
        return ReviewPolicyState(incoming)
    }

    fun explicitOriginalResume(state: ReviewPolicyState, owner: OwnerContext, rightsAndMediaAllowed: Boolean): ReviewContextChange {
        val context = state.context ?: return ReviewContextChange(state)
        if (!rightsAndMediaAllowed || context.profile != ProfileContext(owner.profileId, owner.privacyEpoch) || state.auxiliary != null || context.practiceEntry?.visible == true)
            return ReviewContextChange(state)
        // Offer stays manual even after an explicit resume; the caller shows explicit choices.
        val stillManual = context.offerId != null && state.effectiveEndPolicy != ReviewEndPolicy.REGULAR_AUTO
        return ReviewContextChange(state.copy(explicitResumeRequired = stillManual))
    }
}
