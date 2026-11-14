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
    val task: PracticeRef?, val lessonId: UUID?, val route: OutputRoute, val work: ListeningWorkRef? = null,
    val preparedWork: PreparedListeningWorkRef? = null, val announcement: ReviewAnnouncement? = null, val modalToken: PracticeModalToken? = null)

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
    val captureLease: CaptureLease? = null,
    val preparedListening: PreparedListeningWorkRef? = null,
    val reviewPolicy: ReviewPolicyState = ReviewPolicyState(null),
    val offerRuntime: ReviewOfferRuntime? = null,
    val practiceModal: PracticeModalSession? = null,
)

/** Process-scoped reducer. All callbacks use the same serialized lane. */
class SessionCoordinator {
    private val mutex = Mutex()
    private val input = InputLease()
    private var gleoLease: CaptureLease? = null
    private fun invalidateInput() { input.invalidate(); gleoLease = null }
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
        val closingSettings = !access.detailsOpen && view.screen == SessionScreen.SETTINGS
        input.recheck(access, view.owner, view.task)
        if (closingSettings) invalidateOfferLocked(ReviewOfferEvent.SettingsForcedClosed)
        if (!access.detailsOpen) closeModalLocked(PracticeModalCloseReason.RESTRICTED)
        if (!access.ownerValid || access.power != PowerAvailability.ALLOWED || !access.audioAllowsReply)
            invalidateOfferLocked(ReviewOfferEvent.InputOrRestriction)
        if (!access.ownerValid || access.power != PowerAvailability.ALLOWED || !access.audioAllowsReply) gleoLease = null
        if (!access.ownerValid || access.power != PowerAvailability.ALLOWED || !access.audioAllowsReply ||
            waitRoute == OutputRoute.PARKED_TTS && !access.detailsOpen) {
            clock.cancel(); waitRoute = null
            view = view.copy(expectedOutput = null)
        }
        if (!access.detailsOpen && view.screen !in setOf(SessionScreen.RESTRICTED, SessionScreen.RECOVERY, SessionScreen.CONNECTING))
            restrictedReturn = if (view.screen == SessionScreen.CAPTURE) SessionScreen.QUESTION else view.screen
        if (closingSettings) restrictedReturn = if (view.listening != null || view.preparedListening != null) SessionScreen.PLAYER else SessionScreen.NEWS
        view = view.copy(access = access, inputPhase = if (view.pendingAnswer != null) InputPhase.ACCEPTED else if (gleoLease != null) InputPhase.CAPTURING else input.phase,
            captureLease = view.captureLease?.takeIf { input.phase != InputPhase.IDLE || gleoLease != null }, screen = when {
            !access.detailsOpen -> SessionScreen.RESTRICTED
            view.screen == SessionScreen.RESTRICTED -> restrictedReturn ?: SessionScreen.SAVED
            else -> view.screen
        })
        view
    }

    /** Durable repositories perform the identity mutation; close current effects immediately. */
    suspend fun fenceOwner(owner: OwnerContext): Boolean = mutex.withLock {
        if (view.owner != owner) return@withLock false
        invalidateInput(); clock.cancel(); issuedOutputs.clear(); operations.clear(); waitRoute = null
        view = SessionView(owner = owner, ownerBinding = OwnerBindingStatus.TRANSITION,
            access = view.access.copy(ownerValid = false, audioAllowsReply = false), mediaConnected = view.mediaConnected,
            screen = SessionScreen.RECOVERY, navigationGeneration = view.navigationGeneration + 1,
            intentGeneration = view.intentGeneration + 1, dataState = SessionDataState.SYNCING)
        true
    }

    suspend fun activate(owner: OwnerContext, task: PracticeRef?, lessonId: java.util.UUID? = null,
                         dialogueProgress: DialogueProgress? = null): SessionView = mutex.withLock {
        invalidateInput()
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
        if (destination in setOf(SessionScreen.PLAYER, SessionScreen.ARTICLE_INFO) && view.listening == null && view.preparedListening == null) return@withLock false
        val target = if (destination != SessionScreen.RECOVERY && !view.access.detailsOpen) SessionScreen.RESTRICTED else destination
        if (target == SessionScreen.RESTRICTED) restrictedReturn = destination
        if (target != view.screen) {
            if (destination == SessionScreen.SETTINGS) {
                closeModalLocked(PracticeModalCloseReason.OPEN_SETTINGS)
                view.offerRuntime?.let { offer -> view = view.copy(offerRuntime = ReviewOfferReducer.reduce(offer,
                    ReviewOfferEvent.SettingsOpened(if (view.practiceModal != null) ReviewEntryOrigin.PRACTICE else ReviewEntryOrigin.NEWS, view.intent == PlaybackIntent.PLAY), offerGateLocked()).state) }
            } else if (view.practiceModal?.visible == true) closeModalLocked(PracticeModalCloseReason.CLOSE)
            invalidateInput(); clock.cancel(); waitRoute = null
            view = view.copy(screen = target, navigationGeneration = view.navigationGeneration + 1,
                inputPhase = if (view.pendingAnswer != null) InputPhase.ACCEPTED else InputPhase.IDLE, expectedOutput = null)
        }
        true
    }

    suspend fun beginOperation(kind: SessionOperationKind, owner: OwnerContext?, id: UUID = UUID.randomUUID()): SessionOperationTicket? = mutex.withLock {
        if (owner != view.owner || operations[kind] != null) return@withLock null
        SessionOperationTicket(id, kind, owner, view.listening, view.task, view.lessonId, view.navigationGeneration,
            view.intentGeneration, view.preparedListening).also { operations[kind] = it; view = view.copy(activeOperations = operations.keys.toSet()) }
    }

    private fun matches(ticket: SessionOperationTicket, workSensitive: Boolean): Boolean = operations[ticket.kind] == ticket &&
        view.owner == ticket.owner && (!workSensitive || ((view.listening == null && ticket.work == null ||
            view.listening?.sameWork(ticket.work) == true) && (view.preparedListening == null && ticket.preparedWork == null ||
            view.preparedListening?.sameWork(ticket.preparedWork) == true) && view.task == ticket.task && view.lessonId == ticket.lessonId))

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
            invalidateInput(); clock.cancel(); issuedOutputs.clear(); operations.clear(); waitRoute = null
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
        invalidateInput(); clock.cancel(); waitRoute = null
        val target = if (work.stage == ListeningStage.ARTICLE_OFFER) SessionScreen.ARTICLE_OFFER else SessionScreen.PLAYER
        val sameNavigation = ticket.navigationGeneration == view.navigationGeneration
        view = view.copy(listening = work, preparedListening = null, reviewPolicy = ReviewPolicyState(null), offerRuntime = null, practiceModal = null, mediaPositionMs = positionMs, mediaPlaying = false,
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
            view.task == null && view.inputPhase == InputPhase.IDLE && view.pendingAnswer == null && view.voiceInput == VoiceInputActivity.IDLE
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
                view.access.power != PowerAvailability.ALLOWED || view.listening == null && view.preparedListening == null ||
                (view.preparedListening?.stage ?: view.listening?.stage) == ListeningStage.ARTICLE_OFFER)) return@withLock false
        if (intent != PlaybackIntent.PLAY) invalidateOfferLocked(ReviewOfferEvent.ExplicitStop)
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
        if (view.practiceModal?.visible == true && view.practiceModal?.phase !in setOf(PracticeModalPhase.QUESTION, PracticeModalPhase.RETRY)) return@withLock null
        if (view.pendingAnswer != null || view.inputPhase != InputPhase.IDLE || gleoLease != null) return@withLock null
        val owner = view.owner ?: return@withLock null
        val task = view.task ?: return@withLock null
        val lessonId = view.lessonId ?: return@withLock null
        input.start(owner, task, lessonId, view.access).also {
            if (it != null) {
                val modal = view.practiceModal?.takeIf { modal -> modal.visible }
                val nextModal = modal?.let { current -> PracticeModalReducer.reduce(current, PracticeModalEvent.BeginCapture(it.captureId), view.access, view.owner).session }
                view = view.copy(inputPhase = InputPhase.CAPTURING, captureLease = it, practiceModal = nextModal ?: view.practiceModal,
                    screen = if (nextModal != null) SessionScreen.PLAYER else SessionScreen.CAPTURE)
            }
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
                val modal = view.practiceModal
                val returned = modal?.captureToken?.takeIf { it == captureId }?.let { PracticeModalReducer.reduce(modal,
                    PracticeModalEvent.CaptureCancelled(it), view.access, view.owner).session }
                view = view.copy(practiceModal = returned ?: modal,
                    screen = if (view.access.detailsOpen) { if (returned?.visible == true) SessionScreen.PLAYER else SessionScreen.QUESTION } else SessionScreen.RESTRICTED)
            }
            return@withLock null
        }
        // Local transaction + receipt projection complete together even if the UI caller closes.
        // This callback must only persist locally; network work belongs after acceptance.
        withContext(NonCancellable) {
            try {
                persist(answer)
                view = view.copy(pendingAnswer = answer, inputPhase = InputPhase.ACCEPTED,
                                 dataState = SessionDataState.DEVICE_ONLY, screen = if (view.practiceModal?.visible == true) SessionScreen.PLAYER else SessionScreen.FEEDBACK,
                                 practiceModal = view.practiceModal?.takeIf { it.visible }?.let { modal -> PracticeModalReducer.reduce(modal,
                                     PracticeModalEvent.AnswerAccepted(modal.token, captureId, PracticeModalLesson(PracticeModalPhase.PROCESSING, modal.lesson.revision,
                                         answer.lease.attemptId, modal.lesson.retryUsed, modal.lesson.complete)), view.access, view.owner).session } ?: view.practiceModal,
                                 lastCapture = capture, captureLease = null)
            } catch (error: Exception) {
                view = view.copy(inputPhase = InputPhase.IDLE, dataState = SessionDataState.FAILED,
                                 screen = if (view.access.detailsOpen) SessionScreen.QUESTION else SessionScreen.RESTRICTED)
                throw error
            }
        }
        answer
    }

    suspend fun stop(): SessionView = mutex.withLock {
        closeModalLocked(PracticeModalCloseReason.SAVE_EXIT)
        invalidateOfferLocked(ReviewOfferEvent.ExplicitStop)
        invalidateInput()
        clock.cancel()
        waitRoute = null
        view = view.copy(intent = PlaybackIntent.STOPPED, screen = SessionScreen.SAVED, captureLease = null,
                         navigationGeneration = view.navigationGeneration + 1, intentGeneration = view.intentGeneration + 1,
                         inputPhase = if (view.pendingAnswer != null) InputPhase.ACCEPTED else InputPhase.IDLE, expectedOutput = null)
        view
    }

    /** New explicit lesson only; preserve the factual listening/source position and access. */
    suspend fun bindPractice(ticket: SessionOperationTicket, task: PracticeRef, lessonId: UUID,
        progress: DialogueProgress, help: HelpSnapshot = HelpSnapshot()): Boolean = mutex.withLock {
        if (ticket.kind != SessionOperationKind.PRACTICE || !matches(ticket, true)) return@withLock false
        operations.remove(ticket.kind); invalidateInput(); clock.restore(progress); waitRoute = null
        issuedOutputs.clear()
        view = view.copy(task = task, lessonId = lessonId, practiceModal = null, pendingAnswer = null, inputPhase = InputPhase.IDLE,
            captureLease = null, expectedOutput = null, preparedSupports = emptyList(), helpReceived = help,
            dialogueProgress = progress, mediaPlaying = false, dataState = SessionDataState.DEVICE_ONLY,
            activeOperations = operations.keys.toSet(), screen = if (view.access.detailsOpen) SessionScreen.QUESTION else SessionScreen.RESTRICTED)
        restrictedReturn = SessionScreen.QUESTION
        true
    }

    suspend fun showPractice(owner: OwnerContext, lessonId: UUID, screen: SessionScreen): Boolean = mutex.withLock {
        if (owner != view.owner || lessonId != view.lessonId || screen !in setOf(SessionScreen.QUESTION, SessionScreen.FEEDBACK)) return@withLock false
        invalidateInput(); clock.cancel(); waitRoute = null
        view = view.copy(captureLease = null, expectedOutput = null,
            inputPhase = if (view.pendingAnswer != null) InputPhase.ACCEPTED else InputPhase.IDLE,
            screen = if (view.access.detailsOpen) { if (view.practiceModal?.visible == true) SessionScreen.PLAYER else screen } else SessionScreen.RESTRICTED)
        restrictedReturn = if (view.practiceModal?.visible == true) SessionScreen.PLAYER else screen
        true
    }

    suspend fun practiceResult(owner: OwnerContext, lessonId: UUID, attemptId: UUID?, hasFeedback: Boolean,
        navigationGeneration: Long): Boolean = mutex.withLock {
        if (owner != view.owner || lessonId != view.lessonId) return@withLock false
        val pending = view.pendingAnswer
        if (view.captureLease != null || view.inputPhase in setOf(InputPhase.CAPTURING, InputPhase.FINALIZING)) return@withLock false
        if (pending != null && pending.lease.attemptId != attemptId) return@withLock false
        val modal = view.practiceModal
        if (modal != null) {
            if (!modal.visible || navigationGeneration != view.navigationGeneration) return@withLock false
            val projection = PracticeModalLesson(if (hasFeedback) PracticeModalPhase.FEEDBACK else PracticeModalPhase.PROCESSING,
                modal.lesson.revision + 1, attemptId, modal.lesson.retryUsed, modal.lesson.complete)
            val change = PracticeModalReducer.reduce(modal, PracticeModalEvent.LessonUpdated(modal.token, projection), view.access, view.owner)
            view = view.copy(practiceModal = change.session, pendingAnswer = if (attemptId != null) null else pending,
                inputPhase = if (attemptId != null) InputPhase.IDLE else view.inputPhase, captureLease = null,
                dataState = SessionDataState.SERVER_ACKNOWLEDGED, screen = if (view.access.detailsOpen) SessionScreen.PLAYER else SessionScreen.RESTRICTED)
            return@withLock true
        }
        view = view.copy(pendingAnswer = if (attemptId != null) null else pending,
            inputPhase = if (attemptId != null) InputPhase.IDLE else view.inputPhase, captureLease = null,
            dataState = SessionDataState.SERVER_ACKNOWLEDGED,
            screen = if (hasFeedback && navigationGeneration == view.navigationGeneration && view.access.detailsOpen) SessionScreen.FEEDBACK else view.screen)
        true
    }

    suspend fun openArticleQuestion(ticket: SessionOperationTicket): Boolean = mutex.withLock {
        if (ticket.kind != SessionOperationKind.QUESTION || !matches(ticket, true)) return@withLock false
        invalidateInput(); clock.cancel(); waitRoute = null
        view = view.copy(captureLease = null, expectedOutput = null, mediaPlaying = false,
            inputPhase = if (view.pendingAnswer != null) InputPhase.ACCEPTED else InputPhase.IDLE,
            screen = if (view.access.detailsOpen) SessionScreen.ARTICLE_QUESTION else SessionScreen.RESTRICTED)
        restrictedReturn = SessionScreen.ARTICLE_QUESTION
        true
    }

    suspend fun cancelCapture(owner: OwnerContext): Boolean = mutex.withLock {
        if (view.owner != owner || view.pendingAnswer != null) return@withLock false
        val modal = view.practiceModal
        val restored = modal?.captureToken?.let { capture -> PracticeModalReducer.reduce(modal, PracticeModalEvent.CaptureCancelled(capture), view.access, view.owner).session }
        invalidateInput(); clock.cancel(); waitRoute = null
        view = view.copy(inputPhase = InputPhase.IDLE, captureLease = null, expectedOutput = null, practiceModal = restored ?: modal,
            screen = if (view.access.detailsOpen) { if (modal?.visible == true) SessionScreen.PLAYER else SessionScreen.QUESTION } else SessionScreen.RESTRICTED)
        true
    }

    suspend fun leavePractice(owner: OwnerContext): Boolean = mutex.withLock {
        if (view.owner != owner) return@withLock false
        closeModalLocked(PracticeModalCloseReason.FINISH)
        invalidateInput(); clock.cancel(); issuedOutputs.clear(); waitRoute = null
        view = view.copy(task = null, lessonId = null, pendingAnswer = null, inputPhase = InputPhase.IDLE, captureLease = null,
            helpReceived = HelpSnapshot(), preparedSupports = emptyList(), dialogueProgress = null, expectedOutput = null)
        true
    }

    /** Requires verified system input-start/arbitration, independently of direct parked mic. */
    suspend fun startGleoAnswer(owner: OwnerContext, lessonId: UUID): CaptureLease? = mutex.withLock {
        if (view.practiceModal?.visible == true && view.practiceModal?.phase !in setOf(PracticeModalPhase.QUESTION, PracticeModalPhase.RETRY)) return@withLock null
        val task = view.task ?: return@withLock null
        if (view.owner != owner || view.lessonId != lessonId || view.pendingAnswer != null || view.inputPhase != InputPhase.IDLE ||
            !view.access.ownerValid || view.access.power != PowerAvailability.ALLOWED || !view.access.audioAllowsReply ||
            gleoCapabilities.inputActivity !is Capability.Verified || gleoCapabilities.microphoneArbitration !is Capability.Verified) return@withLock null
        clock.cancel(); waitRoute = null
        CaptureLease(UUID.randomUUID(), UUID.randomUUID(), owner, task, lessonId, view.access.safety.generation, view.access.window.generation).also {
            gleoLease = it
            val modal = view.practiceModal?.takeIf { it.visible }
            view = view.copy(practiceModal = modal?.copy(phase = PracticeModalPhase.CAPTURING, captureToken = it.captureId, captureReturnState = modal.phase) ?: view.practiceModal,
                captureLease = it, inputPhase = InputPhase.CAPTURING, expectedOutput = null, voiceInput = VoiceInputActivity.CAPTURING)
        }
    }

    suspend fun acceptGleoFinal(lease: CaptureLease, text: String, capture: Capture,
        persist: suspend (AcceptedAnswer) -> Unit): AcceptedAnswer? = mutex.withLock {
        if (gleoLease != lease || view.owner != lease.owner || view.task != lease.task || view.lessonId != lease.lessonId ||
            view.pendingAnswer != null || capture.path != CapturePath.GLEO || !view.access.ownerValid ||
            view.access.power != PowerAvailability.ALLOWED || !view.access.audioAllowsReply ||
            gleoCapabilities.inputActivity !is Capability.Verified || gleoCapabilities.microphoneArbitration !is Capability.Verified) return@withLock null
        val value = text.trim()
        if (value.isBlank() || value.codePointCount(0, value.length) > 1200) return@withLock null
        val answer = AcceptedAnswer(lease, value, capture, view.helpReceived)
        withContext(NonCancellable) { persist(answer) }
        gleoLease = null
        view = view.copy(pendingAnswer = answer, inputPhase = InputPhase.ACCEPTED, captureLease = null,
            practiceModal = view.practiceModal?.takeIf { it.visible }?.let { modal -> PracticeModalReducer.reduce(modal,
                PracticeModalEvent.AnswerAccepted(modal.token, lease.captureId, PracticeModalLesson(PracticeModalPhase.PROCESSING,
                    modal.lesson.revision, lease.attemptId, modal.lesson.retryUsed, modal.lesson.complete)), view.access, view.owner).session } ?: view.practiceModal,
            voiceInput = VoiceInputActivity.PROCESSING, dataState = SessionDataState.DEVICE_ONLY)
        answer
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
        if (view.practiceModal != null && (view.practiceModal?.visible != true || output.modalToken != view.practiceModal?.token)) return@withLock false
        if (output.kind == WaitKind.ARTICLE_OFFER && !outputMatchesWork(output)) return@withLock false
        if (output.kind == WaitKind.ARTICLE_OFFER && view.reviewPolicy.context != null && !view.reviewPolicy.canAutoOffer) return@withLock false
        if (output.kind == WaitKind.ARTICLE_OFFER && view.preparedListening != null && view.reviewPolicy.context == null) return@withLock false
        if (output.kind == WaitKind.ARTICLE_OFFER && view.offerRuntime != null && (output.announcement == null ||
            view.offerRuntime?.expected != output.announcement || output.announcement.requestId != output.outputId)) return@withLock false
        if (output.kind != WaitKind.ARTICLE_OFFER && view.dialogueProgress == null) return@withLock false
        if (output.route == OutputRoute.GLEO && gleoCapabilities.outputCompletion !is Capability.Verified) return@withLock false
        if (output.route == OutputRoute.PARKED_TTS && (!view.access.detailsOpen || !view.access.directProviderVerified)) return@withLock false
        if (output.outputId in issuedOutputs) return@withLock false
        if (issuedOutputs.size >= 16) issuedOutputs.remove(issuedOutputs.keys.first())
        issuedOutputs[output.outputId] = output
        if (output.announcement == null) clock.expectOutput(output.kind, output.outputId, output.context)
        else clock.cancel()
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
        if (view.expectedOutput != issued || view.owner != originalContext || view.task != issued.task || issued.kind == WaitKind.ARTICLE_OFFER && !outputMatchesWork(issued)) return@withLock false
        if (issued.announcement != null) {
            val offer = view.offerRuntime ?: return@withLock false
            val change = ReviewOfferReducer.reduce(offer, ReviewOfferEvent.OutputCompleted(issued.announcement, endedAtMs), offerGateLocked())
            if (change.state.wait == null) return@withLock false
            view = view.copy(offerRuntime = change.state, expectedOutput = null)
            return@withLock true
        }
        if (view.practiceModal != null && (view.practiceModal?.visible != true || issued.modalToken != view.practiceModal?.token)) return@withLock false
        if (!clock.outputCompleted(outputId, originalContext, endedAtMs)) return@withLock false
        view = view.copy(expectedOutput = null)
        true
    }

    suspend fun updateVoiceInput(activity: VoiceInputActivity, context: OwnerContext, nowMs: Long): Boolean = mutex.withLock {
        if (context != view.owner || gleoCapabilities.inputActivity !is Capability.Verified) return@withLock false
        clock.tick(nowMs, false, context)
        if (activity != VoiceInputActivity.IDLE) invalidateOfferLocked(ReviewOfferEvent.InputOrRestriction)
        view = view.copy(voiceInput = activity)
        true
    }

    suspend fun tick(nowMs: Long): WaitAction? = mutex.withLock {
        val owner = view.owner ?: return@withLock null
        val canReply = view.access.ownerValid && view.access.audioAllowsReply && view.access.power == PowerAvailability.ALLOWED &&
            view.voiceInput == VoiceInputActivity.IDLE && input.phase == InputPhase.IDLE && view.pendingAnswer == null
        val offer = view.offerRuntime
        if (offer != null) {
            val change = ReviewOfferReducer.reduce(offer, ReviewOfferEvent.Tick(nowMs), offerGateLocked())
            view = view.copy(offerRuntime = change.state)
            if (change.effects.any { it is ReviewOfferEffect.RequestNextAuto }) WaitAction.NEXT_ARTICLE else null
        } else if (view.reviewPolicy.context != null && !view.reviewPolicy.canAutoOffer && (view.preparedListening?.stage ?: view.listening?.stage) == ListeningStage.ARTICLE_OFFER) null
        else clock.tick(nowMs, canReply, owner)
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
        if (view.practiceModal != null && view.practiceModal?.visible != true) return@withLock false
        val ticket = view.preparedSupports.singleOrNull { it.presentation.presentationId == presentationId } ?: return@withLock false
        if (ticket.clientContext != observation.clientContext || observation.clientContext != view.owner || ticket.presentation.contentRef != view.task ||
            ticket.presentation.lessonId != view.lessonId || observation.profileContext != ticket.profileContext ||
            observation.outcome != ObservationOutcome.COMPLETED) return@withLock false
        val allowed = when (observation.mode) {
            ObservationMode.PARKED_VISIBLE -> view.access.detailsOpen
            ObservationMode.VOICE_OUTPUT_COMPLETED -> if (ticket.presentation.interactionChannel == InteractionChannel.PARKED)
                view.access.detailsOpen && view.access.directProviderVerified && view.access.audioAllowsReply
                else gleoCapabilities.exactOutputContent is Capability.Verified && gleoCapabilities.outputCompletion is Capability.Verified
            ObservationMode.EXPLICIT_EXAMPLE_CONFIRMATION -> view.access.detailsOpen && view.access.window.focused ||
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
        context == view.owner && task == view.task && (view.practiceModal == null || view.practiceModal?.visible == true) && view.access.ownerValid &&
            view.access.power == PowerAvailability.ALLOWED && view.access.audioAllowsReply
    }
    /** source6 admission keeps its own reference/cursor and leaves all legacy fields absent. */
    suspend fun admitPreparedListening(completion: RepositoryCompletion<PreparedListeningWorkRef>, showPlayer: Boolean,
        review: ReviewContext? = null, restored: Boolean = false): Boolean = mutex.withLock {
        val ticket = completion.ticket
        val owner = ticket.owner ?: return@withLock false
        if (ticket.kind !in setOf(SessionOperationKind.SELECTION, SessionOperationKind.RESTORE) || !matches(ticket, true)) return@withLock false
        val work = completion.value
        review?.let { require(it.profile == ProfileContext(owner.profileId, owner.privacyEpoch) && it.selectionId == work.selectionId) }
        operations.remove(ticket.kind); invalidateInput(); clock.cancel(); issuedOutputs.clear(); waitRoute = null
        val policy = if (restored) ReviewContextPolicy.restored(review) else ReviewPolicyState(review)
        val offer = review?.offerId?.takeIf { work.stage == ListeningStage.ARTICLE_OFFER }?.let {
            ReviewOfferRuntime(owner, work.selectionId, it, explicitResumeRequired = restored || !policy.canAutoOffer)
        }
        val target = if (work.stage == ListeningStage.ARTICLE_OFFER) SessionScreen.ARTICLE_OFFER else SessionScreen.PLAYER
        view = view.copy(listening = null, preparedListening = work, mediaPositionMs = null, mediaPlaying = false,
            task = null, lessonId = null, pendingAnswer = null, inputPhase = InputPhase.IDLE, captureLease = null,
            expectedOutput = null, dialogueProgress = null, preparedSupports = emptyList(), helpReceived = HelpSnapshot(),
            reviewPolicy = policy, offerRuntime = offer, practiceModal = null, activeOperations = operations.keys.toSet(),
            screen = if (showPlayer && ticket.navigationGeneration == view.navigationGeneration) { if (view.access.detailsOpen) target else SessionScreen.RESTRICTED } else view.screen,
            dataState = SessionDataState.SERVER_ACKNOWLEDGED)
        restrictedReturn = target
        true
    }

    suspend fun segmentPermission(program: SegmentProgram): Boolean = mutex.withLock {
        program.owner == view.owner && view.preparedListening?.accepts(program) == true && view.mediaConnected &&
            view.access.ownerValid && view.access.power == PowerAvailability.ALLOWED && view.task == null &&
            view.inputPhase == InputPhase.IDLE && view.pendingAnswer == null && view.voiceInput == VoiceInputActivity.IDLE &&
            view.practiceModal?.visible != true && view.reviewPolicy.auxiliary == null
    }

    /** Caller carries the previous binding across the asynchronous runtime observation. */
    suspend fun observeSegment(snapshot: SegmentPlaybackSnapshot, playing: Boolean, expectedPreviousRun: UUID? = null): Boolean = mutex.withLock {
        val work = view.preparedListening ?: return@withLock false
        if (snapshot.program.owner != view.owner || !work.accepts(snapshot.program) || work.runId !in setOf(expectedPreviousRun, snapshot.runId)) return@withLock false
        view = view.copy(preparedListening = work.copy(cursor = snapshot.cursor, runId = if (snapshot.bindingAcknowledged) snapshot.runId else work.runId),
            mediaPositionMs = null, mediaPlaying = playing && snapshot.bindingAcknowledged,
            intent = if (snapshot.cursor.playIntent) PlaybackIntent.PLAY else if (view.intent == PlaybackIntent.STOPPED) PlaybackIntent.STOPPED else PlaybackIntent.USER_PAUSED)
        true
    }

    suspend fun bindReviewContext(owner: OwnerContext, context: ReviewContext, restored: Boolean = false): Boolean = mutex.withLock {
        val selection = view.preparedListening?.selectionId ?: view.listening?.selectionId ?: return@withLock false
        if (owner != view.owner || context.profile != ProfileContext(owner.profileId, owner.privacyEpoch) || context.selectionId != selection) return@withLock false
        val policy = if (restored) ReviewContextPolicy.restored(context, view.reviewPolicy.localCancelledOfferId)
            else ReviewContextPolicy.acknowledge(view.reviewPolicy, context)
        val stage = view.preparedListening?.stage ?: view.listening?.stage
        val oldOffer = view.offerRuntime
        val offer = if (stage == ListeningStage.ARTICLE_OFFER && context.offerId != null)
            oldOffer?.takeIf { it.owner == owner && it.selectionId == selection && it.offerId == context.offerId }
                ?: ReviewOfferRuntime(owner, selection, context.offerId, explicitResumeRequired = restored || !policy.canAutoOffer)
            else null
        view = view.copy(reviewPolicy = policy, offerRuntime = offer)
        if (restored || !policy.canAutoOffer) { clock.cancel(); waitRoute = null; view = view.copy(expectedOutput = null) }
        true
    }

    suspend fun prepareOfferAnnouncement(owner: OwnerContext, requestId: UUID): ReviewAnnouncement? = mutex.withLock {
        if (owner != view.owner) return@withLock null
        val offer = view.offerRuntime ?: return@withLock null
        val change = ReviewOfferReducer.reduce(offer, ReviewOfferEvent.Announce(requestId), offerGateLocked())
        view = view.copy(offerRuntime = change.state)
        change.effects.filterIsInstance<ReviewOfferEffect.AnnounceFullOffer>().singleOrNull()?.announcement
    }

    /** Settings entry cancels the existing countdown; it does not pause ordinary news playback. */
    suspend fun settingsOpened(owner: OwnerContext, source: ReviewEntryOrigin): ReviewOfferChange? = mutex.withLock {
        if (owner != view.owner) return@withLock null
        closeModalLocked(PracticeModalCloseReason.OPEN_SETTINGS)
        val offer = view.offerRuntime ?: return@withLock null
        val change = ReviewOfferReducer.reduce(offer, ReviewOfferEvent.SettingsOpened(source, view.intent == PlaybackIntent.PLAY), offerGateLocked())
        clock.cancel(); waitRoute = null
        view = view.copy(offerRuntime = change.state, expectedOutput = null)
        change
    }

    suspend fun settingsUserLeft(owner: OwnerContext, requestId: UUID): ReviewOfferChange? = mutex.withLock {
        if (owner != view.owner) return@withLock null
        val offer = view.offerRuntime ?: return@withLock null
        val change = ReviewOfferReducer.reduce(offer, ReviewOfferEvent.SettingsUserLeft(requestId), offerGateLocked())
        clock.cancel(); waitRoute = null
        view = view.copy(offerRuntime = change.state, expectedOutput = null)
        change
    }

    suspend fun settingsForcedClosed(owner: OwnerContext): Boolean = mutex.withLock {
        if (owner != view.owner) return@withLock false
        invalidateOfferLocked(ReviewOfferEvent.SettingsForcedClosed)
        true
    }

    suspend fun openPracticeModal(token: PracticeModalToken, original: ReviewReturnSnapshot, origin: ReviewEntryOrigin,
        lesson: PracticeModalLesson, admission: PracticeModalAdmission): PracticeModalSession? = mutex.withLock {
        val selection = view.preparedListening?.selectionId ?: view.listening?.selectionId ?: return@withLock null
        if (token.owner != view.owner || token.selectionId != selection || token.lessonId != view.lessonId || view.task == null || view.practiceModal?.visible == true) return@withLock null
        if (original.phase == SegmentPhase.ARTICLE_OFFER && view.reviewPolicy.context == null) return@withLock null
        val modal = PracticeModalReducer.open(token, original, origin, lesson, view.access, view.owner, admission) ?: return@withLock null
        val policy = if (view.reviewPolicy.context != null) ReviewContextPolicy.enterAuxiliary(view.reviewPolicy, ReviewAuxiliaryKind.PRACTICE, original).state else view.reviewPolicy.copy(explicitResumeRequired = true)
        invalidateInput(); clock.cancel(); waitRoute = null
        invalidateOfferLocked(ReviewOfferEvent.InputOrRestriction)
        view = view.copy(practiceModal = modal, reviewPolicy = policy, intent = PlaybackIntent.USER_PAUSED, mediaPlaying = false,
            inputPhase = if (view.pendingAnswer != null) InputPhase.ACCEPTED else InputPhase.IDLE, captureLease = null, expectedOutput = null,
            screen = SessionScreen.PLAYER, navigationGeneration = view.navigationGeneration + 1)
        restrictedReturn = SessionScreen.PLAYER
        modal
    }

    suspend fun practiceModalEvent(owner: OwnerContext, event: PracticeModalEvent): PracticeModalChange? = mutex.withLock {
        if (owner != view.owner) return@withLock null
        val modal = view.practiceModal ?: return@withLock null
        val change = PracticeModalReducer.reduce(modal, event, view.access, view.owner)
        applyModalLocked(change)
        change
    }

    private fun applyModalLocked(change: PracticeModalChange) {
        val modal = change.session
        if (!modal.visible) {
            invalidateInput(); clock.cancel(); waitRoute = null
            view = view.copy(practiceModal = modal, reviewPolicy = ReviewContextPolicy.closeAuxiliary(view.reviewPolicy).state,
                task = null, lessonId = null, pendingAnswer = null, inputPhase = InputPhase.IDLE, captureLease = null, expectedOutput = null,
                intent = PlaybackIntent.USER_PAUSED, mediaPlaying = false, preparedSupports = emptyList(), voiceInput = VoiceInputActivity.IDLE,
                screen = if (view.access.detailsOpen) SessionScreen.PLAYER else SessionScreen.RESTRICTED,
                navigationGeneration = view.navigationGeneration + 1, intentGeneration = view.intentGeneration + 1)
            restrictedReturn = SessionScreen.PLAYER
        } else view = view.copy(practiceModal = modal)
    }
    private fun closeModalLocked(reason: PracticeModalCloseReason): PracticeModalChange? {
        val modal = view.practiceModal?.takeIf { it.visible } ?: return null
        val change = PracticeModalReducer.reduce(modal, PracticeModalEvent.Close(reason), view.access, view.owner)
        applyModalLocked(change)
        return change
    }
    private fun offerGateLocked(): ReviewOfferGate = ReviewOfferGate(view.owner, view.reviewPolicy,
        SegmentPhase.valueOf((view.preparedListening?.stage ?: view.listening?.stage ?: ListeningStage.MAIN).name),
        view.access.ownerValid && view.access.power == PowerAvailability.ALLOWED && view.access.audioAllowsReply,
        view.voiceInput == VoiceInputActivity.IDLE && view.inputPhase == InputPhase.IDLE && view.pendingAnswer == null && view.practiceModal?.visible != true,
        !view.access.ownerValid || view.access.power != PowerAvailability.ALLOWED || !view.access.audioAllowsReply)
    private fun invalidateOfferLocked(event: ReviewOfferEvent) {
        val offer = view.offerRuntime ?: return
        val change = ReviewOfferReducer.reduce(offer, event, offerGateLocked())
        view = view.copy(offerRuntime = change.state, expectedOutput = null)
        clock.cancel(); waitRoute = null
    }
    private fun outputMatchesWork(output: OutputExpectation): Boolean =
        if (output.preparedWork != null) view.preparedListening?.sameWork(output.preparedWork) == true && view.listening == null
        else output.work != null && view.listening?.sameWork(output.work) == true && view.preparedListening == null

    /** Immediate local fence before any review network/material work. No success is implied. */
    suspend fun suspendForReviewTools(owner: OwnerContext): Boolean = mutex.withLock {
        if (owner != view.owner) return@withLock false
        invalidateInput(); clock.cancel(); waitRoute = null
        invalidateOfferLocked(ReviewOfferEvent.InputOrRestriction)
        view = view.copy(expectedOutput = null, captureLease = null, voiceInput = VoiceInputActivity.IDLE,
            inputPhase = if (view.pendingAnswer != null) InputPhase.ACCEPTED else InputPhase.IDLE,
            mediaPlaying = false, intent = PlaybackIntent.USER_PAUSED)
        true
    }
    suspend fun enterReviewAuxiliary(owner: OwnerContext, kind: ReviewAuxiliaryKind, original: ReviewReturnSnapshot,
        localCancellationDurable: Boolean): ReviewContextChange? = mutex.withLock {
        if (owner != view.owner || !view.access.detailsOpen || view.reviewPolicy.context == null ||
            original.phase == SegmentPhase.ARTICLE_OFFER && !localCancellationDurable) return@withLock null
        val change = ReviewContextPolicy.enterAuxiliary(view.reviewPolicy, kind, original)
        invalidateInput(); clock.cancel(); waitRoute = null
        invalidateOfferLocked(ReviewOfferEvent.InputOrRestriction)
        view = view.copy(reviewPolicy = change.state, expectedOutput = null, captureLease = null, mediaPlaying = false,
            inputPhase = if (view.pendingAnswer != null) InputPhase.ACCEPTED else InputPhase.IDLE, intent = PlaybackIntent.USER_PAUSED)
        change
    }
    suspend fun closeReviewAuxiliary(owner: OwnerContext): ReviewContextChange? = mutex.withLock {
        if (owner != view.owner) return@withLock null
        val change = ReviewContextPolicy.closeAuxiliary(view.reviewPolicy)
        view = view.copy(reviewPolicy = change.state, mediaPlaying = false, intent = PlaybackIntent.USER_PAUSED, expectedOutput = null)
        clock.cancel(); waitRoute = null
        change
    }

}
