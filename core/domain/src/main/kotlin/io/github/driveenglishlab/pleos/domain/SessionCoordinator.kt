package io.github.driveenglishlab.pleos.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    val ownerBinding: OwnerBindingStatus = OwnerBindingStatus.UNRESOLVED,
    val listening: ListeningWorkRef? = null,
    val mediaPositionMs: Long? = null,
    val mediaPlaying: Boolean = false,
    val mediaConnected: Boolean = false,
    val navigationGeneration: Long = 0,
    val intentGeneration: Long = 0,
    val activeOperations: Set<SessionOperationKind> = emptySet(),
)

/** Process-scoped reducer. All callbacks use the same serialized lane. */
class SessionCoordinator {
    private val mutex = Mutex()
    private val input = InputLease()
    private val clock = DialogueClock()
    private val issuedOutputs = linkedMapOf<UUID, OutputExpectation>()
    private var gleoCapabilities = VoiceCapabilities()
    private var waitRoute: OutputRoute? = null
    private val observable = MutableStateFlow(SessionView())
    val state = observable.asStateFlow()
    private val operations = mutableMapOf<SessionOperationKind, SessionOperationTicket>()
    private var restrictedReturn: SessionScreen? = null
    private var view = SessionView()
        set(value) { field = value; observable.value = value }

    suspend fun snapshot(): SessionView = mutex.withLock { view }

    suspend fun updateAccess(access: AccessState): SessionView = mutex.withLock {
        input.recheck(access, view.owner, view.task)
        if (!access.ownerValid || access.power != PowerAvailability.ALLOWED || !access.audioAllowsReply ||
            waitRoute == OutputRoute.PARKED_TTS && !access.detailsOpen) {
            clock.cancel(); waitRoute = null
            view = view.copy(expectedOutput = null)
        }
        if (!access.detailsOpen && view.screen !in setOf(SessionScreen.RESTRICTED, SessionScreen.RECOVERY, SessionScreen.CONNECTING))
            restrictedReturn = if (view.screen == SessionScreen.CAPTURE) SessionScreen.QUESTION else view.screen
        view = view.copy(access = access, inputPhase = if (view.pendingAnswer != null) InputPhase.ACCEPTED else input.phase, screen = when {
            !access.detailsOpen -> SessionScreen.RESTRICTED
            view.screen == SessionScreen.RESTRICTED -> restrictedReturn ?: SessionScreen.SAVED
            else -> view.screen
        })
        view
    }

    suspend fun activate(owner: OwnerContext, task: PracticeRef?, lessonId: java.util.UUID? = null,
                         dialogueProgress: DialogueProgress? = null): SessionView = mutex.withLock {
        input.invalidate()
        val previous = view.owner
        operations.clear()
        restrictedReturn = null
        if (previous == null || previous.profileId != owner.profileId || previous.privacyEpoch != owner.privacyEpoch || previous.revision != owner.revision) issuedOutputs.clear()
        require(dialogueProgress == null || lessonId != null)
        clock.restore(dialogueProgress ?: DialogueProgress.newLesson())
        waitRoute = null
        // A transition never imports old real-time grants, capture, or playback intent.
        view = SessionView(owner = owner, task = task, lessonId = lessonId, dialogueProgress = dialogueProgress,
            ownerBinding = OwnerBindingStatus.VERIFIED, mediaConnected = view.mediaConnected)
        view
    }

    suspend fun navigate(context: OwnerContext?, destination: SessionScreen): Boolean = mutex.withLock {
        if (context != view.owner || destination !in setOf(SessionScreen.ONBOARDING, SessionScreen.NEWS, SessionScreen.PLAYER,
                SessionScreen.ARTICLE_INFO, SessionScreen.HISTORY, SessionScreen.SETTINGS, SessionScreen.RECOVERY)) return@withLock false
        if (destination in setOf(SessionScreen.PLAYER, SessionScreen.ARTICLE_INFO) && view.listening == null) return@withLock false
        val target = if (destination != SessionScreen.RECOVERY && !view.access.detailsOpen) SessionScreen.RESTRICTED else destination
        if (target == SessionScreen.RESTRICTED) restrictedReturn = destination
        if (target != view.screen) {
            input.invalidate(); clock.cancel(); waitRoute = null
            view = view.copy(screen = target, navigationGeneration = view.navigationGeneration + 1,
                inputPhase = if (view.pendingAnswer != null) InputPhase.ACCEPTED else InputPhase.IDLE, expectedOutput = null)
        }
        true
    }

    suspend fun beginOperation(kind: SessionOperationKind, owner: OwnerContext?, id: UUID = UUID.randomUUID()): SessionOperationTicket? = mutex.withLock {
        if (owner != view.owner || operations[kind] != null) return@withLock null
        SessionOperationTicket(id, kind, owner, view.listening, view.task, view.lessonId, view.navigationGeneration,
            view.intentGeneration).also { operations[kind] = it; view = view.copy(activeOperations = operations.keys.toSet()) }
    }

    private fun matches(ticket: SessionOperationTicket, workSensitive: Boolean): Boolean = operations[ticket.kind] == ticket &&
        view.owner == ticket.owner && (!workSensitive || (view.listening == null && ticket.work == null ||
            view.listening?.sameWork(ticket.work) == true) && view.task == ticket.task && view.lessonId == ticket.lessonId)

    suspend fun finishOperation(ticket: SessionOperationTicket, workSensitive: Boolean = false): Boolean = mutex.withLock {
        val accepted = matches(ticket, workSensitive)
        if (operations[ticket.kind] == ticket) operations.remove(ticket.kind)
        view = view.copy(activeOperations = operations.keys.toSet())
        accepted
    }

    /** Only the verified profile repository calls this; navigation never re-activates an owner. */
    suspend fun bindVerifiedOwner(ticket: SessionOperationTicket, owner: OwnerContext, onboarding: Boolean): Boolean = mutex.withLock {
        if (ticket.kind != SessionOperationKind.PROFILE || !matches(ticket, false)) return@withLock false
        operations.remove(ticket.kind)
        if (view.owner != owner) {
            input.invalidate(); clock.cancel(); issuedOutputs.clear(); operations.clear(); waitRoute = null
            val access = view.access.copy(ownerValid = true)
            view = SessionView(owner = owner, ownerBinding = OwnerBindingStatus.VERIFIED, access = access,
                mediaConnected = view.mediaConnected, screen = if (access.detailsOpen) {
                    if (onboarding) SessionScreen.NEWS else SessionScreen.ONBOARDING
                } else SessionScreen.RESTRICTED)
            restrictedReturn = if (onboarding) SessionScreen.NEWS else SessionScreen.ONBOARDING
        } else view = view.copy(ownerBinding = OwnerBindingStatus.VERIFIED, dataState = SessionDataState.SERVER_ACKNOWLEDGED,
            activeOperations = operations.keys.toSet())
        true
    }

    suspend fun admitListening(completion: RepositoryCompletion<ListeningWorkRef>, positionMs: Long, showPlayer: Boolean): Boolean = mutex.withLock {
        val ticket = completion.ticket
        if (ticket.kind !in setOf(SessionOperationKind.SELECTION, SessionOperationKind.RESTORE) ||
            !matches(ticket, true) || ticket.owner == null) return@withLock false
        operations.remove(ticket.kind)
        val work = completion.value
        require(positionMs >= 0 && (work.durationMs == null && positionMs == 0L || work.durationMs != null && positionMs <= work.durationMs))
        input.invalidate(); clock.cancel(); waitRoute = null
        val target = if (work.stage == ListeningStage.ARTICLE_OFFER) SessionScreen.ARTICLE_OFFER else SessionScreen.PLAYER
        val sameNavigation = ticket.navigationGeneration == view.navigationGeneration
        view = view.copy(listening = work, mediaPositionMs = positionMs, mediaPlaying = false,
            task = null, lessonId = null, pendingAnswer = null, inputPhase = InputPhase.IDLE, expectedOutput = null,
            dialogueProgress = null, preparedSupports = emptyList(), helpReceived = HelpSnapshot(),
            activeOperations = operations.keys.toSet(),
            screen = if (showPlayer && sameNavigation) { if (view.access.detailsOpen) target else SessionScreen.RESTRICTED } else view.screen,
            dataState = SessionDataState.SERVER_ACKNOWLEDGED)
        restrictedReturn = target
        true
    }

    suspend fun setMediaConnected(connected: Boolean) = mutex.withLock {
        view = view.copy(mediaConnected = connected, mediaPlaying = if (connected) view.mediaPlaying else false)
    }

    suspend fun mediaPermission(key: NewsPlaybackKey): Boolean = mutex.withLock {
        key.owner == view.owner && view.listening?.accepts(key) == true && view.mediaConnected &&
            view.access.ownerValid && view.access.power == PowerAvailability.ALLOWED &&
            view.inputPhase == InputPhase.IDLE && view.pendingAnswer == null && view.voiceInput == VoiceInputActivity.IDLE
    }

    suspend fun bindMediaRun(key: NewsPlaybackKey, expectedPreviousRun: UUID?): Boolean = mutex.withLock {
        val work = view.listening ?: return@withLock false
        if (key.owner != view.owner || !work.accepts(key) || work.runId !in setOf(expectedPreviousRun, key.mainRunId)) return@withLock false
        view = view.copy(listening = work.copy(runId = key.mainRunId))
        true
    }

    suspend fun requestMediaIntent(owner: OwnerContext, intent: PlaybackIntent): Boolean = mutex.withLock {
        if (owner != view.owner) return@withLock false
        if (intent == PlaybackIntent.PLAY && (!view.mediaConnected || !view.access.ownerValid ||
                view.access.power != PowerAvailability.ALLOWED || view.listening == null || view.listening?.stage == ListeningStage.ARTICLE_OFFER)) return@withLock false
        view = view.copy(intent = intent, intentGeneration = view.intentGeneration + 1)
        true
    }

    suspend fun observeMedia(key: NewsPlaybackKey, positionMs: Long, playIntent: Boolean, playing: Boolean, pauseReason: String): Boolean = mutex.withLock {
        val work = view.listening ?: return@withLock false
        if (key.owner != view.owner) return@withLock false
        if (!work.accepts(key) || work.runId != key.mainRunId) {
            // A user STOP/pause queued at EOS still cancels continuation of the same article.
            if (!playIntent && pauseReason !in setOf("none", "transition") && work.checkpointId == key.checkpointId &&
                work.selectionId == key.selectionId && work.content == key.content)
                view = view.copy(intent = PlaybackIntent.USER_PAUSED, intentGeneration = view.intentGeneration + 1)
            return@withLock false
        }
        require(positionMs in 0..key.durationMs)
        view = view.copy(mediaPositionMs = positionMs, mediaPlaying = playing,
            intentGeneration = view.intentGeneration + if (!playIntent && pauseReason !in setOf("none", "transition")) 1 else 0,
            intent = if (pauseReason == "transition") view.intent else if (playIntent) PlaybackIntent.PLAY
                else if (view.intent == PlaybackIntent.STOPPED) PlaybackIntent.STOPPED else PlaybackIntent.USER_PAUSED)
        true
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
            // A durable write's completion can be lost before this reducer resumes.
            // Redelivery of the same witnessed output must reuse its immutable journal event.
            val eventId = UUID.nameUUIDFromBytes(("hint-method:" + outputId).toByteArray(Charsets.UTF_8))
            val fact = DialogueProgressEvent(eventId, DialogueProgressObservation(lesson, true, outputId, evidenceRef))
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
