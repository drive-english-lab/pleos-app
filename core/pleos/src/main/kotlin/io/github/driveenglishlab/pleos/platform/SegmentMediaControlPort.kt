package io.github.driveenglishlab.pleos.platform

import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

/** Immutable current-media fence, not a completion proof or an audio asset. */
data class SegmentMediaKey(val owner: OwnerContext, val selectionId: UUID, val checkpointId: UUID,
    val source: SegmentSourceKey, val phase: SegmentPhase, val scriptId: UUID, val manifestId: UUID, val generation: Long) {
    init { require(generation >= 1 && source.kind != "settings_demo") }
}
data class SegmentMediaProjection(val key: SegmentMediaKey, val title: String, val segmentIndex: Int?, val segmentCount: Int?,
    val playIntent: Boolean, val playing: Boolean, val preparing: Boolean, val available: Boolean,
    val previousAvailable: Boolean, val nextAvailable: Boolean) {
    init {
        require(title.isNotBlank() && title.codePointCount(0, title.length) <= 500)
        require((segmentIndex == null) == (segmentCount == null))
        segmentCount?.let { require(it >= 1 && requireNotNull(segmentIndex) in 0 until it) }
        // A real SDK may still be stopping after intent/permission is revoked. Preserve the
        // observed playing flag rather than inventing an instantaneous physical stop.
    }
}
enum class SegmentMediaCommand { PLAY, PAUSE, STOP, PREVIOUS_SEGMENT, NEXT_SEGMENT, FREEZE }
/** The host rechecks current owner/selection/key and actual permission on every command.
 * Flow values come from the real segment runtime; a command acceptance is never completion.
 */
interface SegmentMediaControlPort {
    val state: StateFlow<SegmentMediaProjection?>
    suspend fun command(expected: SegmentMediaKey, command: SegmentMediaCommand): Boolean
}
