package io.github.driveenglishlab.pleos.domain

import java.net.URI
import java.util.UUID

/** One pinned asset, never a playlist or an instruction to advance the news stream. */
data class NewsPlaybackKey(val owner: OwnerContext, val selectionId: UUID, val checkpointId: UUID,
    val content: ContentRef, val scriptId: UUID, val assetId: UUID, val durationMs: Long,
    val mainRunId: UUID?, val phase: String) {
    init {
        require(durationMs > 0 && phase in setOf("main", "followup"))
        require(phase != "main" || mainRunId != null)
    }
}

class NewsPlaybackResource(val key: NewsPlaybackKey, val title: String, val uri: URI, val positionMs: Long) {
    init { require(title.isNotBlank() && title.length <= 500 && positionMs in 0..key.durationMs) }
    override fun toString() = "NewsPlaybackResource(uri=redacted)"
}

data class NewsPlaybackPosition(val key: NewsPlaybackKey, val positionMs: Long,
    val playIntent: Boolean, val pauseReason: String, val playing: Boolean = false) {
    init { require(positionMs in 0..key.durationMs) }
}

/** Player timeline observation. This is not Gleo output-end or physical audibility evidence. */
data class PlayedInterval(val startMs: Long, val endMs: Long) {
    init { require(startMs >= 0 && endMs > startMs) }
}

/** Bound to a current owner/selection. Implementations must recheck after network completion.
 * persist returns only after the original event/position is durably stored, not merely queued in RAM.
 */
interface NewsPlaybackPort {
    suspend fun current(): NewsPlaybackResource?
    suspend fun renew(key: NewsPlaybackKey, positionMs: Long): NewsPlaybackResource?
    /** Credential access on a media loading thread; does not seek or replace a playback run. */
    suspend fun streamAccess(key: NewsPlaybackKey): URI? = null
    suspend fun mayPlay(key: NewsPlaybackKey): Boolean
    suspend fun persist(position: NewsPlaybackPosition, interval: PlayedInterval?)
    suspend fun ended(key: NewsPlaybackKey)
    suspend fun followupReady(endedKey: NewsPlaybackKey): Boolean = false
}

/** Conservative 1x timeline sampler. A seek/discontinuity, long scheduler gap, buffering,
 * suppression or time reversal resets the anchor; no skipped range becomes listening coverage.
 */
class PlaybackIntervalTracker(private val durationMs: Long, private val maxSampleGapMs: Long = 2_000) {
    init { require(durationMs > 0 && maxSampleGapMs in 1..10_000) }
    private data class Anchor(val position: Long, val elapsed: Long)
    private var anchor: Anchor? = null
    fun reset() { anchor = null }
    /** Anchor the already pinned stationary cursor when the player actually enters isPlaying.
     * This emits nothing. The next advancing sample must still pass the elapsed-time checks.
     */
    fun beganAt(positionMs: Long, elapsedMs: Long) {
        require(positionMs in 0..durationMs && elapsedMs >= 0)
        anchor = Anchor(positionMs, elapsedMs)
    }
    fun sample(positionMs: Long, elapsedMs: Long, playing: Boolean): PlayedInterval? {
        require(positionMs in 0..durationMs && elapsedMs >= 0)
        val previous = anchor
        anchor = if (playing) Anchor(positionMs, elapsedMs) else null
        if (previous == null) return null
        val wall = elapsedMs - previous.elapsed
        val distance = positionMs - previous.position
        return if (wall in 1..maxSampleGapMs && distance in 1..wall + 100)
            PlayedInterval(previous.position, positionMs) else null
    }
}
