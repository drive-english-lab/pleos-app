package io.github.driveenglishlab.pleos.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

enum class OutputRoute { GLEO, PARKED_TTS }
enum class VoiceInputActivity { IDLE, CAPTURING, PROCESSING }
data class OutputExpectation(val outputId: UUID, val kind: WaitKind, val context: OwnerContext,
    val task: PracticeContentRef, val lessonId: UUID?, val route: OutputRoute)

enum class SessionScreen(val specificationId: String?) {
    CONNECTING("S12"), ONBOARDING("S01"), NEWS("S02"), PLAYER("S03"), ARTICLE_INFO("S04"),
    QUESTION("S05"), CAPTURE("S06"), FEEDBACK("S07"), HISTORY("S08"), SETTINGS("S09"),
    ARTICLE_OFFER("S10"), ARTICLE_QUESTION("S11"), RECOVERY("S12"), SAVED("S08"), RESTRICTED(null),
}
enum class SessionDataState { UNKNOWN, DEVICE_ONLY, SYNCING, SERVER_ACKNOWLEDGED, DELETE_PENDING, FAILED }
data class SessionView(
    val screen: SessionScreen = SessionScreen.CONNECTING,
    val owner: OwnerContext? = null,
    val task: PracticeRef? = null,
    val lessonId: java.util.UUID? = null,
    val access: AccessState = AccessState(),
    val intent: PlaybackIntent = PlaybackIntent.STOPPED,
    val pendingAnswer: AcceptedAnswer? = null,
    val inputPhase: InputPhase = InputPhase.IDLE,
    val dataState: SessionDataState = SessionDataState.UNKNOWN,
    val dialogueProgress: DialogueProgress? = null,
    val expectedOutput: OutputExpectation? = null,
    val helpReceived: HelpSnapshot = HelpSnapshot(),
    val preparedSupports: List<PracticePresentationTicket> = emptyList(),
    val voiceInput: VoiceInputActivity = VoiceInputActivity.IDLE,
    val lastCapture: Capture? = null,
)

/** Process-scoped reducer. All callbacks use the same serialized lane. */
class SessionCoordinator {
    private val mutex = Mutex()
    private val input = InputLease()
    private val clock = DialogueClock()
    private val issuedOutputs = linkedMapOf<UUID, OutputExpectation>()
    private var gleoCapabilities = VoiceCapabilities()
    private var waitRoute: OutputRoute? = null
    private var view = SessionView()

    suspend fun snapshot(): SessionView = mutex.withLock { view }

    suspend fun updateAccess(access: AccessState): SessionView = mutex.withLock {
        input.recheck(access, view.owner, view.task)
        if (!access.ownerValid || access.power != PowerAvailability.ALLOWED || !access.audioAllowsReply ||
            waitRoute == OutputRoute.PARKED_TTS && !access.detailsOpen) {
            clock.cancel(); waitRoute = null
            view = view.copy(expectedOutput = null)
        }
        view = view.copy(access = access, inputPhase = if (view.pendingAnswer != null) InputPhase.ACCEPTED else input.phase, screen = when {
            !access.detailsOpen -> SessionScreen.RESTRICTED
            view.screen == SessionScreen.RESTRICTED -> SessionScreen.SAVED
            else -> view.screen
        })
        view
    }

    suspend fun activate(owner: OwnerContext, task: PracticeRef?, lessonId: java.util.UUID? = null,
                         dialogueProgress: DialogueProgress? = null): SessionView = mutex.withLock {
        input.invalidate()
        val previous = view.owner
        if (previous == null || previous.profileId != owner.profileId || previous.privacyEpoch != owner.privacyEpoch || previous.revision != owner.revision) issuedOutputs.clear()
        require(dialogueProgress == null || lessonId != null)
        clock.restore(dialogueProgress ?: DialogueProgress.newLesson())
        waitRoute = null
        // A transition never imports old real-time grants, capture, or playback intent.
        view = SessionView(owner = owner, task = task, lessonId = lessonId, dialogueProgress = dialogueProgress)
        view
    }

    suspend fun startAnswer(): CaptureLease? = mutex.withLock {
        if (view.pendingAnswer != null) return@withLock null
        val owner = view.owner ?: return@withLock null
        val task = view.task ?: return@withLock null
        val lessonId = view.lessonId ?: return@withLock null
        input.start(owner, task, lessonId, view.access).also {
            if (it != null) view = view.copy(inputPhase = InputPhase.CAPTURING, screen = SessionScreen.CAPTURE)
        }
    }

    suspend fun finishAnswer(captureId: java.util.UUID): Boolean = mutex.withLock {
        input.finish(captureId).also { if (it) view = view.copy(inputPhase = InputPhase.FINALIZING) }
    }

    suspend fun acceptFinal(captureId: java.util.UUID, text: String, capture: Capture,
                            persist: suspend (AcceptedAnswer) -> Unit): AcceptedAnswer? = mutex.withLock {
        val answer = input.finalText(captureId, text, view.access, view.owner, view.task, capture, view.helpReceived)
        if (answer == null) {
            view = view.copy(inputPhase = if (view.pendingAnswer != null) InputPhase.ACCEPTED else input.phase)
            if (view.inputPhase == InputPhase.IDLE && view.task != null) {
                view = view.copy(screen = if (view.access.detailsOpen) SessionScreen.QUESTION else SessionScreen.RESTRICTED)
            }
            return@withLock null
        }
        // Local transaction + receipt projection complete together even if the UI caller closes.
        // This callback must only persist locally; network work belongs after acceptance.
        withContext(NonCancellable) {
            try {
                persist(answer)
                view = view.copy(pendingAnswer = answer, inputPhase = InputPhase.ACCEPTED,
                                 dataState = SessionDataState.DEVICE_ONLY, screen = SessionScreen.FEEDBACK, lastCapture = capture)
            } catch (error: Exception) {
                view = view.copy(inputPhase = InputPhase.IDLE, dataState = SessionDataState.FAILED,
                                 screen = if (view.access.detailsOpen) SessionScreen.QUESTION else SessionScreen.RESTRICTED)
                throw error
            }
        }
        answer
    }

    suspend fun stop(): SessionView = mutex.withLock {
        input.invalidate()
        clock.cancel()
        waitRoute = null
        view = view.copy(intent = PlaybackIntent.STOPPED, screen = SessionScreen.SAVED,
                         inputPhase = if (view.pendingAnswer != null) InputPhase.ACCEPTED else InputPhase.IDLE, expectedOutput = null)
        view
    }

    suspend fun configureGleo(capabilities: VoiceCapabilities) = mutex.withLock {
        gleoCapabilities = capabilities
        if (waitRoute == OutputRoute.GLEO && capabilities.outputCompletion !is Capability.Verified) {
            clock.cancel(); waitRoute = null; view = view.copy(expectedOutput = null)
        }
    }

    suspend fun expectOutput(output: OutputExpectation): Boolean = mutex.withLock {
        if (output.context != view.owner || output.task != view.task || output.lessonId != view.lessonId ||
            !view.access.ownerValid || view.access.power != PowerAvailability.ALLOWED || !view.access.audioAllowsReply) return@withLock false
        if (output.kind != WaitKind.ARTICLE_OFFER && view.dialogueProgress == null) return@withLock false
        if (output.route == OutputRoute.GLEO && gleoCapabilities.outputCompletion !is Capability.Verified) return@withLock false
        if (output.route == OutputRoute.PARKED_TTS && (!view.access.detailsOpen || !view.access.directProviderVerified)) return@withLock false
        if (output.outputId in issuedOutputs) return@withLock false
        if (issuedOutputs.size >= 16) issuedOutputs.remove(issuedOutputs.keys.first())
        issuedOutputs[output.outputId] = output
        clock.expectOutput(output.kind, output.outputId, output.context)
        waitRoute = output.route
        view = view.copy(expectedOutput = output)
        true
    }

    /** Adapter must witness the exact output's real end. Local commit precedes the wait. */
    suspend fun outputCompleted(outputId: UUID, originalContext: OwnerContext, endedAtMs: Long,
        evidenceRef: String, persistHint: suspend (OwnerContext, DialogueProgressEvent) -> Unit): Boolean = mutex.withLock {
        require(endedAtMs >= 0); requireReference(evidenceRef)
        val issued = issuedOutputs[outputId] ?: return@withLock false
        val active = view.owner ?: return@withLock false
        if (issued.context != originalContext || active.profileId != originalContext.profileId ||
            active.privacyEpoch != originalContext.privacyEpoch || active.revision != originalContext.revision) return@withLock false
        if (issued.route == OutputRoute.GLEO && gleoCapabilities.outputCompletion !is Capability.Verified) return@withLock false
        if (issued.kind == WaitKind.HINT_METHOD) {
            val lesson = issued.lessonId ?: return@withLock false
            val fact = DialogueProgressEvent(UUID.randomUUID(), DialogueProgressObservation(lesson, true, outputId, evidenceRef))
            withContext(NonCancellable) { persistHint(originalContext, fact) }
            if (view.lessonId == lesson && view.task == issued.task) {
                view = view.copy(dialogueProgress = DialogueProgress(true))
                clock.recordBusinessProgress(DialogueProgress(true))
            }
        }
        issuedOutputs.remove(outputId)
        if (view.expectedOutput != issued || view.owner != originalContext || view.task != issued.task) return@withLock false
        if (!clock.outputCompleted(outputId, originalContext, endedAtMs)) return@withLock false
        view = view.copy(expectedOutput = null)
        true
    }

    suspend fun updateVoiceInput(activity: VoiceInputActivity, context: OwnerContext, nowMs: Long): Boolean = mutex.withLock {
        if (context != view.owner || gleoCapabilities.inputActivity !is Capability.Verified) return@withLock false
        clock.tick(nowMs, false, context)
        view = view.copy(voiceInput = activity)
        true
    }

    suspend fun tick(nowMs: Long): WaitAction? = mutex.withLock {
        val owner = view.owner ?: return@withLock null
        val canReply = view.access.ownerValid && view.access.audioAllowsReply && view.access.power == PowerAvailability.ALLOWED &&
            view.voiceInput == VoiceInputActivity.IDLE && input.phase == InputPhase.IDLE && view.pendingAnswer == null
        clock.tick(nowMs, canReply, owner)
    }

    suspend fun preparedSupport(ticket: PracticePresentationTicket): Boolean = mutex.withLock {
        if (ticket.clientContext != view.owner || ticket.presentation.contentRef != view.task || ticket.presentation.lessonId != view.lessonId) return@withLock false
        if (ticket.profileContext != view.owner?.let { ProfileContext(it.profileId, it.privacyEpoch) } ||
            ticket.exposure.questionRef != ticket.presentation.contentRef.questionRef) return@withLock false
        if (view.preparedSupports.any { it.presentation.presentationId == ticket.presentation.presentationId }) return@withLock false
        view = view.copy(preparedSupports = view.preparedSupports + ticket)
        true
    }

    /** Called only with a separately journaled, verified adapter observation. No attempt is made. */
    suspend fun observedSupport(presentationId: UUID, observation: PracticePresentationObservation): Boolean = mutex.withLock {
        val ticket = view.preparedSupports.singleOrNull { it.presentation.presentationId == presentationId } ?: return@withLock false
        if (ticket.clientContext != observation.clientContext || observation.clientContext != view.owner || ticket.presentation.contentRef != view.task ||
            ticket.presentation.lessonId != view.lessonId || observation.profileContext != ticket.profileContext ||
            observation.outcome != ObservationOutcome.COMPLETED) return@withLock false
        val allowed = when (observation.mode) {
            ObservationMode.PARKED_VISIBLE -> view.access.detailsOpen
            ObservationMode.VOICE_OUTPUT_COMPLETED, ObservationMode.EXPLICIT_EXAMPLE_CONFIRMATION ->
                gleoCapabilities.exactOutputContent is Capability.Verified && gleoCapabilities.outputCompletion is Capability.Verified
            else -> false
        }
        if (!allowed) return@withLock false
        val artifacts = ticket.presentation.artifacts
        view = view.copy(helpReceived = view.helpReceived.union(HelpSnapshot(artifacts.mapNotNull { it.kind }.toSet(), artifacts.map { it.supportRef }.toSet())),
            preparedSupports = view.preparedSupports.filterNot { it.presentation.presentationId == presentationId })
        true
    }

    suspend fun canPresent(context: OwnerContext, task: PracticeRef?): Boolean = mutex.withLock {
        context == view.owner && task == view.task && view.access.ownerValid &&
            view.access.power == PowerAvailability.ALLOWED && view.access.audioAllowsReply
    }
}
