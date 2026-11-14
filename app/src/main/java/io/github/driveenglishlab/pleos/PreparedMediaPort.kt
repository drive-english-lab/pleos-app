package io.github.driveenglishlab.pleos

import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.platform.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The same process runtime used by the product, not a background synthesis/player clone. */
internal class PreparedMediaPort(private val coordinator: SessionCoordinator, scope: CoroutineScope,
    private val preparedRuntime: PreparedProductRuntime, private val title: () -> String) : SegmentMediaControlPort {
    override val state = combine(coordinator.state, preparedRuntime.state) { view, runtime -> projection(view, runtime) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private fun projection(view: SessionView, runtime: SegmentRuntimeState): SegmentMediaProjection? {
        val owner = view.owner ?: return null
        val work = view.preparedListening ?: return null
        val snapshot = runtime.snapshot?.takeIf { it.program.owner == owner && it.program.selectionId == work.selectionId && it.program.source == work.content.segmentSourceKey() }
        val matches = snapshot != null && work.accepts(snapshot.program)
        val offer = work.stage == ListeningStage.ARTICLE_OFFER
        val index = if (!offer && matches) snapshot?.cursor?.segmentIndex else null
        val count = if (index != null) snapshot?.program?.manifest?.segments?.size else null
        val generation = snapshot?.generation ?: work.checkpointVersion
        val available = !offer && matches && view.mediaConnected && view.access.ownerValid && view.access.power == PowerAvailability.ALLOWED &&
            view.task == null && view.pendingAnswer == null && view.inputPhase == InputPhase.IDLE && view.voiceInput == VoiceInputActivity.IDLE &&
            view.practiceModal?.visible != true && view.reviewPolicy.auxiliary == null
        return SegmentMediaProjection(SegmentMediaKey(owner, work.selectionId, work.checkpointId, work.content.segmentSourceKey(), work.cursor.phase,
            work.cursor.scriptId, work.cursor.manifestId, generation), title().ifBlank { "현재 기사" }, index, count,
            view.intent == PlaybackIntent.PLAY && snapshot?.cursor?.playIntent == true,
            matches && runtime.status == SegmentRuntimeStatus.SPEAKING && snapshot?.bindingAcknowledged == true,
            matches && runtime.status == SegmentRuntimeStatus.PREPARING, available,
            available && index != null && requireNotNull(snapshot).program.segmentIndices.first() < index,
            available && index != null && requireNotNull(snapshot).program.segmentIndices.last() > index)
    }

    override suspend fun command(expected: SegmentMediaKey, command: SegmentMediaCommand): Boolean {
        val view = coordinator.snapshot()
        val runtime = preparedRuntime.state.value
        val current = projection(view, runtime) ?: return false
        if (current.key != expected || view.owner != expected.owner) return false
        val work = view.preparedListening ?: return false
        if (work.stage == ListeningStage.ARTICLE_OFFER) return false
        val snapshot = runtime.snapshot ?: return false
        if (!work.accepts(snapshot.program) || snapshot.program.owner != expected.owner) return false
        return when (command) {
            SegmentMediaCommand.PLAY -> {
                if (!current.available || !coordinator.segmentPermission(snapshot.program) ||
                    !coordinator.requestMediaIntent(expected.owner, PlaybackIntent.PLAY,
                        expectedIntentGeneration = view.intentGeneration, expectedSelectionId = work.selectionId)) return false
                // The runtime rechecks owner, source/rights, exclusive lease and actual Pleos gate.
                preparedRuntime.play(expected.owner, work.selectionId, view.intentGeneration + 1)
            }
            SegmentMediaCommand.PAUSE, SegmentMediaCommand.STOP, SegmentMediaCommand.FREEZE -> {
                // Revoked playback permission must never prevent stopping the same original run.
                val intent = if (command == SegmentMediaCommand.STOP) PlaybackIntent.STOPPED else PlaybackIntent.USER_PAUSED
                if (!coordinator.requestMediaIntent(expected.owner, intent)) return false
                preparedRuntime.hold(PauseReason.USER)
            }
            SegmentMediaCommand.PREVIOUS_SEGMENT, SegmentMediaCommand.NEXT_SEGMENT -> {
                val previous = command == SegmentMediaCommand.PREVIOUS_SEGMENT
                if (!current.available || !(if (previous) current.previousAvailable else current.nextAvailable) ||
                    !coordinator.segmentPermission(snapshot.program)) return false
                preparedRuntime.runtime.move(if (previous) -1 else 1, playAfterMove = current.playIntent)
            }
        }
    }
}
