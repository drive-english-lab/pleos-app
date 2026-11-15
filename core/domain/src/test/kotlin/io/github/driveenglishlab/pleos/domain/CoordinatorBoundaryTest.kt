package io.github.driveenglishlab.pleos.domain

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Synthetic event tests, not proof of real Gleo or vehicle permissions. */
class CoordinatorBoundaryTest {
    private val owner = OwnerContext(UUID.randomUUID(), 0, 1, UUID.randomUUID())
    private val task = PracticeContentRef(ContentRef(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        ListeningMode.ORIGINAL_FULL), UUID.randomUUID(), UUID.randomUUID())
    private val lesson = UUID.randomUUID()
    private fun allowed() = AccessState(SafetyState(VehiclePosition.PARKED, true, true, true), WindowState(true, true),
        PowerAvailability.ALLOWED, true, true, true, true, true).withSyntheticFeatureRoutes()
    private fun capabilities() = VoiceCapabilities(outputCompletion = Capability.Verified("test:actual-output-end"),
        exactOutputContent = Capability.Verified("test:exact-text"), inputActivity = Capability.Verified("test:input-state"))
    private fun output(kind: WaitKind) = OutputExpectation(UUID.randomUUID(), kind, owner, task, lesson, OutputRoute.GLEO)
    private suspend fun ready() = SessionCoordinator().also {
        it.activate(owner, task, lesson, DialogueProgress.newLesson()); it.updateAccess(allowed()); it.configureGleo(capabilities())
    }

    @Test fun unknownCapabilityAndSentCallbackNeverStartTheClock() = runBlocking {
        val c = SessionCoordinator(); c.activate(owner, task, lesson, DialogueProgress.newLesson()); c.updateAccess(allowed())
        val question = output(WaitKind.FIRST_ANSWER)
        assertFalse(c.expectOutput(question))
        c.configureGleo(capabilities()); assertTrue(c.expectOutput(question))
        assertNull(c.tick(100_000))
        assertFalse(c.outputCompleted(question.outputId, owner.copy(presentationToken = UUID.randomUUID()), 100_000, "wrong-token") { _, _ -> fail() })
        assertTrue(c.outputCompleted(question.outputId, owner, 100_000, "actual-end") { _, _ -> fail() })
        assertEquals(WaitAction.ANNOUNCE_HINT_METHOD, c.tick(120_000))
    }

    @Test fun methodCompletionCommitsLocallyBeforeTenSecondsAndDoesNotWaitForServerAck() = runBlocking {
        val c = ready(); val hint = output(WaitKind.HINT_METHOD); c.expectOutput(hint)
        var persisted = false
        assertTrue(c.outputCompleted(hint.outputId, owner, 1_000, "actual-method-end") { context, event ->
            assertEquals(owner, context); assertEquals(lesson, event.observation.lessonId)
            assertTrue(event.observation.hintMethodAnnounced); persisted = true
        })
        assertTrue(persisted); assertTrue(c.snapshot().dialogueProgress!!.hintMethodAnnounced)
        assertNull(c.tick(10_999)); assertEquals(WaitAction.SAVE_AND_PAUSE, c.tick(11_000))
    }

    @Test fun restoredBusinessFlagGetsNewQuestionTimeAndNoOldOutputGrant() = runBlocking {
        val c = ready(); val old = output(WaitKind.HINT_METHOD); c.expectOutput(old)
        c.activate(owner, task, lesson, DialogueProgress(true)); c.updateAccess(allowed())
        assertNull(c.tick(1_000_000))
        val question = output(WaitKind.FIRST_ANSWER); c.expectOutput(question)
        c.outputCompleted(question.outputId, owner, 1_000_000, "new-question-end") { _, _ -> fail() }
        assertNull(c.tick(1_019_999)); assertEquals(WaitAction.SAVE_AND_PAUSE, c.tick(1_020_000))
    }

    @Test fun lateMethodCompletionOnlyAddsBusinessFactWithoutReplacingNewQuestionWait() = runBlocking {
        val c = ready(); val hint = output(WaitKind.HINT_METHOD); c.expectOutput(hint)
        val question = output(WaitKind.FIRST_ANSWER); c.expectOutput(question)
        c.outputCompleted(question.outputId, owner, 1_000, "new-question-end") { _, _ -> fail() }
        var facts = 0
        assertFalse(c.outputCompleted(hint.outputId, owner, 900, "earlier-method-end") { _, _ -> facts++ })
        assertEquals(1, facts); assertTrue(c.snapshot().dialogueProgress!!.hintMethodAnnounced)
        assertNull(c.tick(10_900)); assertEquals(WaitAction.SAVE_AND_PAUSE, c.tick(21_000))
    }

    @Test fun actualSuspendReturnDoesNotReviveWaitOrInput() = runBlocking {
        val c = ready(); val question = output(WaitKind.FIRST_ANSWER); c.expectOutput(question)
        c.outputCompleted(question.outputId, owner, 0, "actual-end") { _, _ -> fail() }
        c.updateAccess(allowed().copy(power = PowerAvailability.SUSPENDED)); c.updateAccess(allowed())
        assertNull(c.tick(100_000)); assertEquals(InputPhase.IDLE, c.snapshot().inputPhase)
        assertNull(c.snapshot().expectedOutput)
    }

    @Test fun preparedMeaningDoesNotBecomeDeliveredFactHintOrConsumeAnAttempt() = runBlocking {
        val c = ready()
        val meaning = SupportArtifact("approved:meaning", null, SupportRole.QUESTION_MEANING, SupportLanguage.KO,
            "도시가 무엇을 열었나요?", "a".repeat(64), SupportOrigin.PUBLISHED_TASK, null)
        val profile = ProfileContext(owner.profileId, 0); val id = UUID.randomUUID()
        val ticket = PracticePresentationTicket(UUID.randomUUID(), profile, owner,
            PracticePresentationRecord(id, task, lesson, InteractionChannel.PARKED, listOf(meaning), 1,
                Instant.parse("2026-11-12T00:00:00Z"), PresentationStatus.PREPARED, null),
            QuestionExampleExposure(task.questionRef, ExposureStatus.UNKNOWN, ExposureBasis.HISTORY_GAP, 0, 1, null))
        assertTrue(c.preparedSupport(ticket)); assertTrue(c.snapshot().helpReceived.refs.isEmpty())
        val actual = PracticePresentationObservation(UUID.randomUUID(), profile, UUID.randomUUID(), owner, UUID.randomUUID(),
            ObservationOutcome.COMPLETED, ObservationMode.PARKED_VISIBLE, "actual-component-visible")
        assertFalse(c.observedSupport(id, actual.copy(outcome = ObservationOutcome.PARTIAL_OR_UNKNOWN, mode = ObservationMode.INDETERMINATE)))
        assertTrue(c.observedSupport(id, actual)); assertEquals(setOf(meaning.supportRef), c.snapshot().helpReceived.refs)
        assertTrue(c.snapshot().helpReceived.kinds.isEmpty()); assertNull(c.snapshot().pendingAnswer)
    }

    @Test fun ownerChangeRejectsOldHintFactAndOutput() = runBlocking {
        val c = ready(); val hint = output(WaitKind.HINT_METHOD); c.expectOutput(hint)
        c.activate(owner.copy(profileId = UUID.randomUUID(), revision = 2), task, UUID.randomUUID(), DialogueProgress.newLesson())
        assertFalse(c.outputCompleted(hint.outputId, owner, 0, "old-owner-end") { _, _ -> fail() })
        assertFalse(c.snapshot().dialogueProgress!!.hintMethodAnnounced)
    }

    @Test fun cancellationAfterDurableHintCommitReusesTheSameEventOnRedelivery() = runBlocking {
        val c = ready(); val hint = output(WaitKind.HINT_METHOD); c.expectOutput(hint)
        var stored: DialogueProgressEvent? = null
        try {
            c.outputCompleted(hint.outputId,owner,1_000,"same-actual-end") { _, event ->
                stored = event
                // Fault injection: the durable write completed, but its completion is lost.
                throw CancellationException("after durable commit")
            }
            fail("The persistence callback completion was cancelled.")
        } catch (_: CancellationException) { }
        assertNotNull(stored)
        assertFalse(c.snapshot().dialogueProgress!!.hintMethodAnnounced)
        assertTrue(c.outputCompleted(hint.outputId,owner,1_000,"same-actual-end") { _, event -> assertEquals(stored,event) })
        assertTrue(c.snapshot().dialogueProgress!!.hintMethodAnnounced)
        assertEquals(WaitAction.SAVE_AND_PAUSE,c.tick(11_000))
    }
}
