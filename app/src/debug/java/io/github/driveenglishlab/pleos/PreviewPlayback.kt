package io.github.driveenglishlab.pleos

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.driveenglishlab.pleos.platform.media.MediaPlaybackSignals
import io.github.driveenglishlab.pleos.platform.media.NewsMediaService
import com.google.common.util.concurrent.ListenableFuture
import java.io.Closeable

/** UI connection only. The shared service owns audio, focus and foreground lifetime. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PreviewPlayback(private val context: Context, private val stories: List<PreviewStory>, private val initialId: String) : Closeable {
    private var controller: MediaController? = null
    private var pending: ListenableFuture<MediaController>? = null
    private var connecting = false
    private var pendingSelection: Pair<String, Int>? = null
    private var closed = false
    var ready by mutableStateOf(false); private set
    var playing by mutableStateOf(false); private set
    var positionMs by mutableIntStateOf(0); private set
    var durationMs by mutableIntStateOf(1); private set
    var mediaId by mutableStateOf(""); private set
    var error by mutableStateOf(""); private set
    var canPrevious by mutableStateOf(false); private set
    var canNext by mutableStateOf(false); private set
    var canPlay by mutableStateOf(false); private set
    val stopGeneration: Long get() = MediaPlaybackSignals.stopGeneration
    private val listener = object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) { refresh() } }
    init { connect() }
    private fun connect() {
        if (closed || connecting || controller != null) return
        connecting = true
        pending?.let(MediaController::releaseFuture)
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, NewsMediaService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) { if (!closed) { this@PreviewPlayback.controller = null; ready = false; error = "재생 연결이 끊겼어요. 다시 불러와 주세요." } }
            }).buildAsync()
        pending = future
        future.addListener({
            connecting = false
            if (closed) { MediaController.releaseFuture(future); return@addListener }
            runCatching {
                controller = future.get().also { current ->
                    current.addListener(listener)
                    val requested = pendingSelection
                    if (requested != null) {
                        current.setMediaItem(MediaItem.Builder().setMediaId(requested.first).build(), requested.second.toLong())
                        current.prepare(); pendingSelection = null
                    } else if (current.currentMediaItem == null) {
                        current.setMediaItem(MediaItem.Builder().setMediaId(initialId).build()); current.prepare()
                    }
                }
                refresh()
            }.onFailure { error = "오디오를 불러오지 못했어요. 다시 시도해 주세요."; ready = false }
        }, context.mainExecutor)
    }
    fun load(story: PreviewStory, resumeMs: Int = 0) {
        val c = controller ?: run { pendingSelection = story.id to resumeMs.coerceAtLeast(0); connect(); return }
        c.setMediaItem(MediaItem.Builder().setMediaId(story.id).build(), resumeMs.toLong().coerceAtLeast(0)); c.prepare(); error = ""
    }
    fun toggle() {
        val c = controller ?: return
        if (!canPlay) return
        if (c.playWhenReady) c.pause() else {
            if (c.currentPosition >= durationMs - 100 && c.playbackState != Player.STATE_IDLE) c.seekTo(0)
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }
    fun seek(position: Int) { controller?.seekTo(position.coerceIn(0, durationMs).toLong()) }
    fun previous() { controller?.takeIf { canPrevious }?.seekToPreviousMediaItem() }
    fun next() { controller?.takeIf { canNext }?.seekToNextMediaItem() }
    fun pause() { controller?.pause() }
    fun resume() { controller?.takeIf { canPlay }?.let { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } }
    fun stop() { controller?.stop() }
    fun refresh() {
        val c = controller ?: return
        mediaId = c.currentMediaItem?.mediaId.orEmpty()
        val measured = c.duration
        durationMs = (if (measured != C.TIME_UNSET && measured > 0) measured else ((stories.firstOrNull { it.id == mediaId }?.seconds ?: 0.001) * 1000).toLong()).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        positionMs = c.currentPosition.coerceIn(0, durationMs.toLong()).toInt()
        playing = c.isPlaying
        ready = c.currentMediaItem != null && c.playerError == null
        error = if (c.playerError != null) "오디오를 불러오지 못했어요. 다시 불러와 주세요." else ""
        canPrevious = c.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        canNext = c.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        canPlay = c.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)
    }
    override fun close() {
        closed = true; controller?.removeListener(listener)
        pending?.let(MediaController::releaseFuture); controller = null; ready = false
    }
}
