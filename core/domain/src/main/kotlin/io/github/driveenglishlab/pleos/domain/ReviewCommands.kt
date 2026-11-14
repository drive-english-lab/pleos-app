package io.github.driveenglishlab.pleos.domain

import java.util.UUID

/** Transient only. None of these output/timer grants is restored from Room/server context. */
data class ReviewAnnouncement(val owner: OwnerContext, val selectionId: UUID, val offerId: UUID,
    val generation: Long, val requestId: UUID) { init { require(generation >= 1) } }
data class ReviewOfferWait(val announcement: ReviewAnnouncement, val completedAtMs: Long, val deadlineMs: Long) {
    init { require(completedAtMs >= 0 && deadlineMs - completedAtMs == 8_000L) }
}
data class ReviewSettingsRuntime(val source: ReviewEntryOrigin, val originalPlayIntent: Boolean,
    val offerReannouncementRequired: Boolean, val owner: OwnerContext, val selectionId: UUID, val offerId: UUID)
data class ReviewOfferRuntime(val owner: OwnerContext, val selectionId: UUID, val offerId: UUID,
    val announcementGeneration: Long = 0, val expected: ReviewAnnouncement? = null,
    val wait: ReviewOfferWait? = null, val settings: ReviewSettingsRuntime? = null,
    val explicitResumeRequired: Boolean = false) {
    init { require(announcementGeneration >= 0 && !(expected != null && wait != null)) }
}
data class ReviewOfferGate(val owner: OwnerContext?, val policy: ReviewPolicyState, val phase: SegmentPhase,
    val outputAllowed: Boolean, val inputIdle: Boolean, val restricted: Boolean = false) {
    fun allows(runtime: ReviewOfferRuntime): Boolean {
        val context = policy.context ?: return false
        return owner == runtime.owner && context.profile == ProfileContext(runtime.owner.profileId, runtime.owner.privacyEpoch) &&
            context.selectionId == runtime.selectionId && context.offerId == runtime.offerId && phase == SegmentPhase.ARTICLE_OFFER &&
            policy.canAutoOffer && outputAllowed && inputIdle && !restricted && !runtime.explicitResumeRequired
    }
}
sealed interface ReviewOfferEvent {
    data class Announce(val requestId: UUID) : ReviewOfferEvent
    data class SettingsOpened(val source: ReviewEntryOrigin, val originalPlayIntent: Boolean) : ReviewOfferEvent
    data class SettingsUserLeft(val requestId: UUID) : ReviewOfferEvent
    data object SettingsForcedClosed : ReviewOfferEvent
    data class OutputCompleted(val announcement: ReviewAnnouncement, val completedAtMs: Long) : ReviewOfferEvent
    data class Tick(val nowMs: Long) : ReviewOfferEvent
    data object InputOrRestriction : ReviewOfferEvent
    data object ExplicitStop : ReviewOfferEvent
    data object Restored : ReviewOfferEvent
}
sealed interface ReviewOfferEffect {
    data object CancelWaitAndExpectedOutput : ReviewOfferEffect
    /** Must use the complete ordinary announcement, including the silence/next-news sentence. */
    data class AnnounceFullOffer(val announcement: ReviewAnnouncement) : ReviewOfferEffect
    data class RequestNextAuto(val announcement: ReviewAnnouncement, val expectedContextVersion: Long) : ReviewOfferEffect
}
data class ReviewOfferChange(val state: ReviewOfferRuntime, val effects: List<ReviewOfferEffect> = emptyList())

object ReviewOfferReducer {
    fun reduce(state: ReviewOfferRuntime, event: ReviewOfferEvent, gate: ReviewOfferGate): ReviewOfferChange = when (event) {
        is ReviewOfferEvent.SettingsOpened -> {
            val settings = ReviewSettingsRuntime(event.source, event.originalPlayIntent, gate.allows(state), state.owner, state.selectionId, state.offerId)
            ReviewOfferChange(state.copy(announcementGeneration = state.announcementGeneration + 1, expected = null, wait = null, settings = settings),
                listOf(ReviewOfferEffect.CancelWaitAndExpectedOutput))
        }
        is ReviewOfferEvent.SettingsUserLeft -> {
            val saved = state.settings
            val cleared = state.copy(settings = null, expected = null, wait = null)
            if (saved?.offerReannouncementRequired == true && saved.owner == state.owner && saved.selectionId == state.selectionId && saved.offerId == state.offerId && gate.allows(cleared))
                announce(cleared, event.requestId)
            else ReviewOfferChange(cleared, listOf(ReviewOfferEffect.CancelWaitAndExpectedOutput))
        }
        is ReviewOfferEvent.Announce -> if (state.settings == null && gate.allows(state)) announce(state, event.requestId) else cancelled(state)
        is ReviewOfferEvent.OutputCompleted -> {
            if (state.settings != null || state.expected != event.announcement || !gate.allows(state)) ReviewOfferChange(state)
            else {
                require(event.completedAtMs in 0..Long.MAX_VALUE - 8_000)
                ReviewOfferChange(state.copy(expected = null, wait = ReviewOfferWait(event.announcement, event.completedAtMs, event.completedAtMs + 8_000)))
            }
        }
        is ReviewOfferEvent.Tick -> {
            require(event.nowMs >= 0)
            val wait = state.wait
            if (state.settings != null || !gate.allows(state)) cancelled(state)
            else if (wait != null && event.nowMs >= wait.deadlineMs)
                ReviewOfferChange(state.copy(wait = null), listOf(ReviewOfferEffect.RequestNextAuto(wait.announcement, requireNotNull(gate.policy.context).version)))
            else ReviewOfferChange(state)
        }
        ReviewOfferEvent.SettingsForcedClosed, ReviewOfferEvent.ExplicitStop, ReviewOfferEvent.Restored, ReviewOfferEvent.InputOrRestriction ->
            ReviewOfferChange(state.copy(announcementGeneration = state.announcementGeneration + 1, expected = null, wait = null,
                settings = null, explicitResumeRequired = true), listOf(ReviewOfferEffect.CancelWaitAndExpectedOutput))
    }
    private fun announce(state: ReviewOfferRuntime, requestId: UUID): ReviewOfferChange {
        val token = ReviewAnnouncement(state.owner, state.selectionId, state.offerId, state.announcementGeneration + 1, requestId)
        return ReviewOfferChange(state.copy(announcementGeneration = token.generation, expected = token, wait = null),
            listOf(ReviewOfferEffect.CancelWaitAndExpectedOutput, ReviewOfferEffect.AnnounceFullOffer(token)))
    }
    private fun cancelled(state: ReviewOfferRuntime) = ReviewOfferChange(state.copy(expected = null, wait = null), listOf(ReviewOfferEffect.CancelWaitAndExpectedOutput))
}
