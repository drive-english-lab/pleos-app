package io.github.driveenglishlab.pleos.platform

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** MediaSession command/state facade only. No physical audio engine, fake URI/duration/EOS. */
@UnstableApi
internal class SegmentSessionPlayer(private val reported: SegmentReportedPlayer, private val scope: CoroutineScope,
    private val port: () -> SegmentMediaControlPort?) : ForwardingPlayer(reported) {
    constructor(scope: CoroutineScope, port: () -> SegmentMediaControlPort?) : this(SegmentReportedPlayer(), scope, port)
    fun publish(value: SegmentMediaProjection?) = reported.publish(value)
    private fun dispatch(command: SegmentMediaCommand) {
        val expected = reported.projection?.key ?: return
        val current = port() ?: return
        scope.launch { try { if (current.state.value?.key == expected) current.command(expected, command) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* State remains actual; never optimistic completion. */ } }
    }
    override fun play() = dispatch(SegmentMediaCommand.PLAY)
    override fun pause() = dispatch(SegmentMediaCommand.PAUSE)
    override fun stop() = dispatch(SegmentMediaCommand.STOP)
    override fun setPlayWhenReady(value: Boolean) { if (value) play() else pause() }
    override fun prepare() { /* Readiness is reported by the existing source/runtime; no implicit play. */ }
    override fun seekToPrevious() = dispatch(SegmentMediaCommand.PREVIOUS_SEGMENT)
    override fun seekToPreviousMediaItem() = dispatch(SegmentMediaCommand.PREVIOUS_SEGMENT)
    override fun seekToNext() = dispatch(SegmentMediaCommand.NEXT_SEGMENT)
    override fun seekToNextMediaItem() = dispatch(SegmentMediaCommand.NEXT_SEGMENT)
    override fun seekBack() = dispatch(SegmentMediaCommand.PREVIOUS_SEGMENT)
    override fun seekForward() = dispatch(SegmentMediaCommand.NEXT_SEGMENT)
    override fun seekTo(positionMs: Long) { /* No millisecond mapping exists for segment narration. */ }
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) { }
    override fun seekToDefaultPosition() { }
    override fun seekToDefaultPosition(mediaItemIndex: Int) { }
    override fun setMediaItem(item: MediaItem) { }
    override fun setMediaItem(item: MediaItem, resetPosition: Boolean) { }
    override fun setMediaItem(item: MediaItem, startPositionMs: Long) { }
    override fun setMediaItems(items: List<MediaItem>) { }
    override fun setMediaItems(items: List<MediaItem>, resetPosition: Boolean) { }
    override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) { }
}

@UnstableApi
internal class SegmentReportedPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    var projection: SegmentMediaProjection? = null
        private set
    fun publish(value: SegmentMediaProjection?) { projection = value; invalidateState() }
    override fun getState(): State {
        val value = projection
        val commands = Player.Commands.Builder().addAll(Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA, Player.COMMAND_GET_TIMELINE)
        if (value != null) {
            commands.add(Player.COMMAND_STOP)
            if (value.available) commands.addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE)
            if (value.previousAvailable) commands.addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            if (value.nextAvailable) commands.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
        val builder = State.Builder().setAvailableCommands(commands.build())
            .setPlayWhenReady(value?.playIntent == true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(when {
                value == null || !value.available -> Player.STATE_IDLE
                value.preparing -> Player.STATE_BUFFERING
                value.playing || !value.playIntent -> Player.STATE_READY
                else -> Player.STATE_IDLE
            }).setContentPositionMs(PositionSupplier.ZERO).setContentBufferedPositionMs(PositionSupplier.ZERO)
            .setTotalBufferedDurationMs(PositionSupplier.ZERO)
        if (value != null) {
            val extra = Bundle().apply {
                putString("progress_unit", "segment"); putString("phase", value.key.phase.name.lowercase())
                value.segmentIndex?.let { putInt("segment_index", it) }; value.segmentCount?.let { putInt("segment_count", it) }
            }
            val metadata = MediaMetadata.Builder().setTitle(value.title).setIsPlayable(value.available).setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_NEWS).setExtras(extra).build()
            val item = MediaItem.Builder().setMediaId(NewsMediaService.CURRENT).setMediaMetadata(metadata).build()
            builder.setPlaylist(listOf(MediaItemData.Builder(value.key).setMediaItem(item).setMediaMetadata(metadata)
                .setDurationUs(C.TIME_UNSET).setIsSeekable(false).build())).setCurrentMediaItemIndex(0)
        }
        return builder.build()
    }
}
