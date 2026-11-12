package io.github.driveenglishlab.pleos.domain

import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LearningPolicyTest {
    private fun owner() = OwnerContext(UUID.randomUUID(), 0, 1, UUID.randomUUID())
    private fun task() = PracticeRef(ContentRef(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ListeningMode.ORIGINAL_FULL), UUID.randomUUID(), UUID.randomUUID())
    private fun allowed() = AccessState(
        SafetyState(VehiclePosition.PARKED, true, true, true, false, 3),
        WindowState(true, true, 4), PowerAvailability.ALLOWED,
        microphoneGranted = true, directProviderVerified = true, inputArbitrationVerified = true,
        audioAllowsReply = true, ownerValid = true,
    )

    @Test fun eightFollowupCombinationsUseActualMain() {
        assertEquals(listOf("original_en_summary", "original_ko_summary", "original_ko_translation", null),
            Followup.entries.map { ListeningPolicy.followupRole(ListeningMode.ORIGINAL_FULL, it) })
        assertEquals(listOf(null, "briefing_ko_summary", "briefing_ko_translation", null),
            Followup.entries.map { ListeningPolicy.followupRole(ListeningMode.BRIEFING, it) })
    }
    @Test fun missingSelectionIsDifferentFromNone() {
        assertThrows(IllegalArgumentException::class.java) { LearningSettings(onboardingComplete = true) }
        assertThrows(IllegalArgumentException::class.java) { ListeningPolicy.followupRole(ListeningMode.ORIGINAL_FULL, null) }
        assertNull(ListeningPolicy.followupRole(ListeningMode.ORIGINAL_FULL, Followup.NONE))
    }
    @Test fun unknownAndStoppedDriveNeverUnlockInput() {
        assertFalse(AccessState().canCapture)
        VehiclePosition.entries.filter { it != VehiclePosition.PARKED }.forEach {
            val access = allowed().copy(safety = allowed().safety.copy(position = it))
            assertFalse(access.detailsOpen); assertFalse(access.canCapture)
        }
        assertFalse(allowed().copy(safety = allowed().safety.copy(uxKnown = false)).detailsOpen)
        assertFalse(allowed().copy(safety = allowed().safety.copy(systemBlocked = true)).detailsOpen)
        assertFalse(allowed().copy(power = PowerAvailability.UNKNOWN).canCapture)
    }
    @Test fun microphoneNeedsExplicitStartFinishAndMatchingFinal() {
        val input = InputLease(); val owner = owner(); val task = task(); val access = allowed()
        val lease = input.start(owner, task, UUID.randomUUID(), access)!!
        assertNull(input.start(owner, task, lease.lessonId, access))
        assertNull(input.finalText(lease.captureId, "A city opened a library.", access, owner, task, Capture.unverified(CapturePath.PARKED_STT)))
        assertTrue(input.finish(lease.captureId)); assertFalse(input.finish(lease.captureId))
        assertEquals("A city opened a library.", input.finalText(lease.captureId, "A city opened a library.", access, owner, task, Capture.unverified(CapturePath.PARKED_STT))?.text)
        assertNull(input.finalText(lease.captureId, "Duplicate", access, owner, task, Capture.unverified(CapturePath.PARKED_STT)))
    }
    @Test fun restrictionBeforeFinalDiscardsAndParkReturnDoesNotRestart() {
        val input = InputLease(); val owner = owner(); val task = task(); val access = allowed()
        val lease = input.start(owner, task, UUID.randomUUID(), access)!!; input.finish(lease.captureId)
        input.recheck(access.copy(window = WindowState(false, false, 5)), owner, task)
        assertNull(input.finalText(lease.captureId, "Late answer", access, owner, task, Capture.unverified(CapturePath.PARKED_STT)))
        assertFalse(input.finish(lease.captureId))
    }
    @Test fun latePermissionGrantCreatesNoCapture() {
        val input = InputLease(); val owner = owner(); val task = task()
        assertNull(input.start(owner, task, UUID.randomUUID(), allowed().copy(microphoneGranted = false)))
        input.recheck(allowed(), owner, task)
        assertFalse(input.finish(UUID.randomUUID()))
    }
    @Test fun ownerAndGenerationChangesInvalidateFinal() {
        for (change in listOf<(AccessState) -> AccessState>(
            { it.copy(safety = it.safety.copy(generation = 99)) },
            { it.copy(window = it.window.copy(generation = 99)) },
            { it.copy(inputArbitrationVerified = false) },
        )) {
            val input = InputLease(); val o = owner(); val t = task(); val a = allowed()
            val lease = input.start(o, t, UUID.randomUUID(), a)!!; input.finish(lease.captureId)
            assertNull(input.finalText(lease.captureId, "A real answer", change(a), o, t, Capture.unverified(CapturePath.PARKED_STT)))
        }
        val input = InputLease(); val o = owner(); val t = task(); val lease = input.start(o, t, UUID.randomUUID(), allowed())!!
        input.finish(lease.captureId)
        assertNull(input.finalText(lease.captureId, "Old owner", allowed(), owner(), t, Capture.unverified(CapturePath.PARKED_STT)))
    }
    @Test fun noTimerStartsAtScreenEntryOrSentCallback() {
        val clock = DialogueClock(); val o = owner(); val token = UUID.randomUUID()
        clock.expectOutput(WaitKind.ARTICLE_OFFER, token, o)
        assertNull(clock.tick(100_000, true, o))
        assertFalse(clock.outputCompleted(UUID.randomUUID(), o, 100_000))
        assertTrue(clock.outputCompleted(token, o, 100_000))
        assertNull(clock.tick(107_999, true, o))
        assertEquals(WaitAction.NEXT_ARTICLE, clock.tick(108_000, true, o))
        assertNull(clock.tick(200_000, true, o))
    }
    @Test fun busyInputAndInterruptionDoNotAdvance() {
        val clock = DialogueClock(); val o = owner(); val token = UUID.randomUUID()
        clock.expectOutput(WaitKind.ARTICLE_OFFER, token, o); clock.outputCompleted(token, o, 0)
        assertNull(clock.tick(7_000, true, o)); assertNull(clock.tick(7_000, false, o))
        assertNull(clock.tick(100_000, false, o)); assertNull(clock.tick(100_000, true, o))
        assertNull(clock.tick(100_999, true, o))
        assertEquals(WaitAction.NEXT_ARTICLE, clock.tick(101_000, true, o))
    }
    @Test fun questionWaitAndHintThenPauseUseTwoRealOutputEnds() {
        val clock = DialogueClock(); val o = owner(); val question = UUID.randomUUID()
        clock.newQuestion(); clock.expectOutput(WaitKind.FIRST_ANSWER, question, o); clock.outputCompleted(question, o, 0)
        assertEquals(WaitAction.ANNOUNCE_HINT_METHOD, clock.tick(20_000, true, o))
        assertNull(clock.tick(80_000, true, o))
        val hint = UUID.randomUUID(); clock.expectOutput(WaitKind.HINT_METHOD, hint, o); clock.outputCompleted(hint, o, 80_000)
        assertEquals(WaitAction.SAVE_AND_PAUSE, clock.tick(90_000, true, o))
    }
    @Test fun thinkingAfterHintGivesTwentyWithoutRepeatingHintMethod() {
        val clock = DialogueClock(); val o = owner(); val hint = UUID.randomUUID()
        clock.expectOutput(WaitKind.HINT_METHOD, hint, o); clock.outputCompleted(hint, o, 0)
        val thinking = UUID.randomUUID(); clock.thinkingOrHelp(thinking, o)
        assertNull(clock.tick(100_000, true, o))
        clock.outputCompleted(thinking, o, 100_000)
        assertNull(clock.tick(119_999, true, o))
        assertEquals(WaitAction.SAVE_AND_PAUSE, clock.tick(120_000, true, o))
    }
    @Test fun ownerChangeCancelsWaitingAndOutput() {
        val clock = DialogueClock(); val old = owner(); val token = UUID.randomUUID()
        clock.expectOutput(WaitKind.RETRY, token, old); clock.outputCompleted(token, old, 0)
        assertNull(clock.tick(9_000, true, owner()))
        assertNull(clock.tick(20_000, true, old))
    }
    @Test fun stopWordInAnswerIsNotAnOperatingCommand() {
        assertEquals(ClearIntent.ANSWER, ConversationPolicy.classify("The next step is to stop wasting energy."))
        assertEquals(ClearIntent.STOP, ConversationPolicy.classify("그만"))
    }
    @Test fun conversationRestoresSameAssetStageAndUserPause() {
        val asset = UUID.randomUUID()
        val point = ReturnPoint(PlaybackPosition(asset, ListeningStage.FOLLOWUP, 12_000, 30_000), PlaybackIntent.USER_PAUSED)
        val restored = ConversationPolicy.returnFromQuestion(point)
        assertEquals(asset, restored.playback.assetId); assertEquals(ListeningStage.FOLLOWUP, restored.playback.stage)
        assertEquals(7_000L, restored.playback.positionMs); assertEquals(PlaybackIntent.USER_PAUSED, restored.intent)
    }
    @Test fun helpDoesNotAutomaticallyRevealExampleOrInventFormCorrection() {
        assertEquals(SupportAction.FACT_EXPLANATION, PracticePolicy.help(HelpRequest.HINT, HelpSnapshot()))
        val helped = HelpSnapshot(setOf(HelpKind.FACT_EXPLANATION))
        assertEquals(SupportAction.SENTENCE_STARTER, PracticePolicy.help(HelpRequest.HINT, helped))
        assertEquals(SupportAction.COMPLETE_EXAMPLE, PracticePolicy.help(HelpRequest.EXAMPLE, helped))
        assertFalse(PracticePolicy.mayCorrectForm(CaptureFidelity.UNKNOWN, emptyList(), true))
        assertFalse(PracticePolicy.mayCorrectForm(CaptureFidelity.CONFIRMED_FORM, listOf("uncertain"), true))
    }
    @Test fun ambiguousTitlesAskOnlyOnceAndPreserveOriginalWork() {
        val a = ArticleIdentity(UUID.randomUUID(), "A new library", "새 도서관", "Paper A", "2026-11-12")
        val b = a.copy(articleId = UUID.randomUUID(), publisher = "Paper B")
        assertTrue(ConversationPolicy.resolveTitle("A new library", listOf(a, b), false) is TitleResolution.AskOnce)
        assertEquals(TitleResolution.KeepCurrentWork, ConversationPolicy.resolveTitle("A new library", listOf(a, b), true))
        assertEquals(TitleResolution.Selected(a), ConversationPolicy.resolveTitle("A new library", listOf(a, b), true, publisher = "Paper A"))
        assertEquals(TitleResolution.KeepCurrentWork, ConversationPolicy.resolveTitle("Unknown news", listOf(a), false))
    }
    @Test fun qaReturnsToSamePracticeOrReannouncesEntireArticleOffer() {
        val ref = task(); val lesson = UUID.randomUUID()
        assertEquals(ConversationReturn.SameQuestion(lesson, ref), ConversationPolicy.returnTo(QuestionOrigin.Practice(lesson, ref)))
        assertEquals(ConversationReturn.ReannounceOffer(ref.content), ConversationPolicy.returnTo(QuestionOrigin.ArticleOffer(ref.content)))
    }
    @Test fun hintProgressionUsesDeliveredFactsAndDoesNotAutomaticallyOfferAnExample() {
        assertEquals(SupportAction.FACT_EXPLANATION, PracticePolicy.help(HelpRequest.HINT, HelpSnapshot()))
        assertEquals(SupportAction.SENTENCE_STARTER, PracticePolicy.help(HelpRequest.HINT, HelpSnapshot(setOf(HelpKind.FACT_EXPLANATION))))
        assertEquals(SupportAction.FACT_EXPLANATION, PracticePolicy.help(HelpRequest.HINT, HelpSnapshot(refs = setOf("question-meaning-only"))))
        assertFalse(PracticePolicy.mayOfferExampleAfterRetry(false, true))
        assertTrue(PracticePolicy.mayOfferExampleAfterRetry(true, true))
    }
    @Test fun emptyFinalReturnsToQuestionWithoutSubmittingOrGettingStuck() = runBlocking {
        val coordinator = SessionCoordinator(); coordinator.activate(owner(), task(), UUID.randomUUID())
        coordinator.updateAccess(allowed()); val lease = coordinator.startAnswer()!!; coordinator.finishAnswer(lease.captureId)
        assertNull(coordinator.acceptFinal(lease.captureId, "   ", Capture.unverified(CapturePath.PARKED_STT)) { fail("Blank text must not be persisted.") })
        assertEquals(InputPhase.IDLE, coordinator.snapshot().inputPhase)
        assertEquals(SessionScreen.QUESTION, coordinator.snapshot().screen)
        assertNotNull(coordinator.startAnswer())
    }
    @Test fun storageFailureDoesNotBecomeSavedOrKeepSubmitting() = runBlocking {
        val coordinator = SessionCoordinator(); val o = owner(); val t = task()
        coordinator.activate(o, t, UUID.randomUUID()); coordinator.updateAccess(allowed())
        val lease = coordinator.startAnswer()!!; coordinator.finishAnswer(lease.captureId)
        var rejected = false
        try { coordinator.acceptFinal(lease.captureId, "A city opened a library.", Capture.unverified(CapturePath.PARKED_STT)) { throw IllegalStateException("disk failure") } }
        catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected); assertNull(coordinator.snapshot().pendingAnswer)
        assertEquals(SessionDataState.FAILED, coordinator.snapshot().dataState)
        assertEquals(InputPhase.IDLE, coordinator.snapshot().inputPhase)
        assertEquals(t, coordinator.snapshot().task)
    }
    @Test fun acceptedAnswerPersistsBeforeSendAndSurvivesLaterRestriction() = runBlocking {
        val coordinator = SessionCoordinator(); val o = owner(); val t = task()
        coordinator.activate(o, t, UUID.randomUUID()); coordinator.updateAccess(allowed())
        val lease = coordinator.startAnswer()!!; coordinator.finishAnswer(lease.captureId)
        var persisted = false
        val answer = coordinator.acceptFinal(lease.captureId, "The city opened a library.", Capture.unverified(CapturePath.PARKED_STT)) { persisted = true }
        assertTrue(persisted); assertNotNull(answer)
        assertNull(coordinator.startAnswer()) // A pending accepted turn cannot open a second capture.
        coordinator.updateAccess(AccessState())
        assertEquals(answer, coordinator.snapshot().pendingAnswer)
        coordinator.activate(owner(), t, UUID.randomUUID())
        assertNull(coordinator.snapshot().pendingAnswer)
        assertFalse(coordinator.canPresent(o, t))
    }
}
