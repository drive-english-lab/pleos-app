package io.github.driveenglishlab.pleos.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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
)

/** Process-scoped reducer. All callbacks use the same serialized lane. */
class SessionCoordinator {
    private val mutex = Mutex()
    private val input = InputLease()
    private var view = SessionView()

    suspend fun snapshot(): SessionView = mutex.withLock { view }

    suspend fun updateAccess(access: AccessState): SessionView = mutex.withLock {
        input.recheck(access, view.owner, view.task)
        view = view.copy(access = access, inputPhase = if (view.pendingAnswer != null) InputPhase.ACCEPTED else input.phase, screen = when {
            !access.detailsOpen -> SessionScreen.RESTRICTED
            view.screen == SessionScreen.RESTRICTED -> SessionScreen.SAVED
            else -> view.screen
        })
        view
    }

    suspend fun activate(owner: OwnerContext, task: PracticeRef?, lessonId: java.util.UUID? = null): SessionView = mutex.withLock {
        input.invalidate()
        // A transition never imports old real-time grants, capture, or playback intent.
        view = SessionView(owner = owner, task = task, lessonId = lessonId)
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

    suspend fun acceptFinal(captureId: java.util.UUID, text: String,
                            persist: suspend (AcceptedAnswer) -> Unit): AcceptedAnswer? = mutex.withLock {
        val answer = input.finalText(captureId, text, view.access, view.owner, view.task)
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
                                 dataState = SessionDataState.DEVICE_ONLY, screen = SessionScreen.FEEDBACK)
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
        view = view.copy(intent = PlaybackIntent.STOPPED, screen = SessionScreen.SAVED,
                         inputPhase = if (view.pendingAnswer != null) InputPhase.ACCEPTED else InputPhase.IDLE)
        view
    }

    suspend fun canPresent(context: OwnerContext, task: PracticeRef?): Boolean = mutex.withLock {
        context == view.owner && task == view.task && view.access.ownerValid &&
            view.access.power == PowerAvailability.ALLOWED && view.access.audioAllowsReply
    }
}
