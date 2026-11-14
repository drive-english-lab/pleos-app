package io.github.driveenglishlab.pleos.domain

import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ServicePolicyTest {
    private val owner = OwnerContext(UUID.randomUUID(), 2, 7, UUID.randomUUID())
    private val process = UUID.randomUUID()
    private val context = UUID.randomUUID()
    private val accepted = Instant.parse("2026-11-15T01:00:00Z")
    private fun binding(channel: PlaybackRequestChannel = PlaybackRequestChannel.APP) = PlaybackRequestBinding(owner, UUID.randomUUID(), context,
        process, PlaybackRequestKind.EXPLICIT_ARTICLE, channel, UUID.randomUUID(), UUID.randomUUID(), ListeningMode.BRIEFING, 100, 100 + channel.budgetMs)
    private fun admission(binding: PlaybackRequestBinding) = PlaybackAdmission(binding.operationId, "a".repeat(64), accepted, binding.kind,
        UUID.randomUUID(), accepted.plusMillis(binding.channel.budgetMs))
    private fun guard(elapsed: Long = 100, utc: Instant = accepted) = PlaybackActivationGuard(owner, context, process, elapsed, utc, true)

    @Test fun retryCannotChangeAdmissionDeadlineAndPollsNeverExtendOriginalInputWindow() {
        val binding = binding()
        val receipt = admission(binding)
        val initial = PlaybackRequestPolicy.admit(PlaybackRequestIntent(binding), receipt)
        assertEquals(initial, PlaybackRequestPolicy.admit(initial, receipt))
        assertThrows(IllegalArgumentException::class.java) { PlaybackRequestPolicy.admit(initial, receipt.copy(acceptedAt = accepted.plusMillis(1))) }
        var polling = initial
        repeat(3) { polling = requireNotNull(PlaybackRequestPolicy.reservePoll(polling, guard(), maxPolls = 3)) }
        assertNull(PlaybackRequestPolicy.reservePoll(polling, guard(), maxPolls = 3))
        assertEquals(binding.localDeadlineElapsedMs, polling.binding.localDeadlineElapsedMs)
        assertEquals(receipt.activationDeadlineAt, polling.admission?.activationDeadlineAt)
        assertNull(PlaybackRequestPolicy.reservePoll(initial, guard(10_100, accepted.plusSeconds(10))))
    }

    @Test fun lateSelectedAndRestorationOnlyUpdateStateUntilExplicitNewInput() {
        val binding = binding()
        val selected = PlaybackRequestPolicy.observe(PlaybackRequestPolicy.admit(PlaybackRequestIntent(binding), admission(binding)), 2, PlaybackRequestOutcome.SELECTED)
        val late = PlaybackRequestPolicy.claim(selected, guard(10_101, accepted.plusSeconds(11)), UUID.randomUUID())
        assertTrue(late is PlaybackActivationDecision.StateOnly)
        assertEquals(PlaybackRequestState.EXPIRED, (late as PlaybackActivationDecision.StateOnly).intent.state)
        val restored = PlaybackRequestPolicy.restored(selected)
        assertTrue(PlaybackRequestPolicy.claim(restored, guard(), UUID.randomUUID()) is PlaybackActivationDecision.StateOnly)
        assertTrue(PlaybackRequestPolicy.claim(selected, guard().copy(processToken = UUID.randomUUID()), UUID.randomUUID()) is PlaybackActivationDecision.StateOnly)
        assertTrue(PlaybackRequestPolicy.claim(selected, guard().copy(contextToken = UUID.randomUUID()), UUID.randomUUID()) is PlaybackActivationDecision.StateOnly)
    }

    @Test fun cancelledPendingCannotBeResurrectedByReadyResponseAndActivationIsOnceOnly() {
        val binding = binding(PlaybackRequestChannel.GLEO)
        val admitted = PlaybackRequestPolicy.admit(PlaybackRequestIntent(binding), admission(binding))
        val cancelled = PlaybackRequestPolicy.cancel(admitted)
        val late = PlaybackRequestPolicy.observe(cancelled, 2, PlaybackRequestOutcome.RESUMED)
        assertEquals(PlaybackRequestState.CANCELLED, late.state)
        assertTrue(PlaybackRequestPolicy.claim(late, guard(), UUID.randomUUID()) is PlaybackActivationDecision.StateOnly)
        val selected = PlaybackRequestPolicy.observe(admitted, 2, PlaybackRequestOutcome.SELECTED)
        val once = PlaybackRequestPolicy.claim(selected, guard(), UUID.randomUUID()) as PlaybackActivationDecision.Claimed
        assertTrue(PlaybackRequestPolicy.claim(once.intent, guard(), UUID.randomUUID()) is PlaybackActivationDecision.StateOnly)
    }

    @Test fun viewFilterChangesExposeNoWorkMutationAndKeepOriginalPlaybackIntentFacts() {
        val binding = binding()
        val pending = PlaybackRequestPolicy.admit(PlaybackRequestIntent(binding), admission(binding))
        val before = CatalogViewState(owner = owner)
        val after = CatalogViewPolicy.filter(before, CatalogView.ISSUES, CatalogCategoryId.SCIENCE)
        assertEquals(CatalogView.ISSUES, after.view)
        assertEquals(CatalogCategoryId.SCIENCE, after.categoryFilter)
        assertEquals(admission(binding).activationDeadlineAt, pending.admission?.activationDeadlineAt)
        assertEquals(CatalogView.INTERESTS, CatalogViewPolicy.automaticSelectionView())
        assertEquals(PlaybackRequestState.ACTIVE, pending.state)
    }

    @Test fun recoveryDoesNotTreatMissingCredentialsOrLocalCacheAsServerConfirmedIdentity() {
        val missing = IdentityRecoveryPolicy.project(IdentityRecoveryEvidence(owner, ProtectedIdentityEvidence.ABSENT))
        assertEquals(IdentityRecoveryState.IDENTITY_UNAVAILABLE, missing.state)
        assertEquals(IdentityRecoveryState.RECOVERY_RETRYABLE,
            IdentityRecoveryPolicy.project(IdentityRecoveryEvidence(owner, ProtectedIdentityEvidence.READ_RETRYABLE)).state)
        assertEquals(IdentityRecoveryState.ACCOUNT_SIGN_IN_REQUIRED,
            IdentityRecoveryPolicy.project(IdentityRecoveryEvidence(owner, ProtectedIdentityEvidence.ABSENT, accountSignInRequired = true)).state)
        val acknowledged = IdentityRecoveryEvidence(owner, ProtectedIdentityEvidence.AVAILABLE, verifiedServerBinding = ProfileContext(owner.profileId, owner.privacyEpoch), currentServerAcknowledgement = true)
        assertEquals(IdentityRecoveryState.RESTORED, IdentityRecoveryPolicy.project(acknowledged).state)
        val pending = IdentityRecoveryPolicy.project(acknowledged.copy(pendingOutbox = true))
        assertEquals(IdentityRecoveryState.SYNC_PENDING, pending.state)
        assertEquals(IdentitySyncState.OUTBOX_PENDING, pending.syncState)
        assertEquals(IdentityRecoveryState.SYNC_PENDING, IdentityRecoveryPolicy.project(acknowledged.copy(currentServerAcknowledgement = false)).state)
    }

    @Test fun independentCountsAndTimesCannotBeFilledWithPopularityZeroOrFutureEvidence() {
        assertThrows(IllegalArgumentException::class.java) { CatalogIssueEvidence(false, emptyList(), emptySet(), 0,
            OriginIndependence.CONFIRMED, null, accepted, "issues_v1") }
        assertThrows(IllegalArgumentException::class.java) { CatalogIssueEvidence(true, listOf(CatalogHeadlineObservation(UUID.randomUUID(), "headlines", 1,
            accepted.plusSeconds(1), "a".repeat(64))), emptySet(), null, OriginIndependence.PENDING, null, accepted, "issues_v1") }
        val unknown = IndependentEstimate(EstimateStatus.UNKNOWN, null, null, null, null, null, null, null, 0)
        assertNull(IndependentEstimate.total(unknown, unknown))
    }
}
