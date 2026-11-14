package io.github.driveenglishlab.pleos.platform

import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import android.os.Bundle
import io.github.driveenglishlab.pleos.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import java.io.IOException

/** The product composition supplies a real owner/permission-aware port. Absent wiring stays silent. */
interface NewsMediaRuntimeProvider {
    val newsPlayback: NewsPlaybackPort?
    val segmentMedia: SegmentMediaControlPort? get() = null
}

/** Single-asset player. EOS is an observed event; only the product coordinator chooses a next step.
 * No Activity lifecycle, browser connection, focus gain or stale URL reply starts playback.
 */
@UnstableApi
open class NewsMediaService : MediaLibraryService() {
    protected open val runtime: NewsPlaybackPort? get() = (application as? NewsMediaRuntimeProvider)?.newsPlayback
    protected open val segmentRuntime: SegmentMediaControlPort? get() = (application as? NewsMediaRuntimeProvider)?.segmentMedia
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var writer: Job? = null
    private var writerFailures = 0L
    private lateinit var player: ExoPlayer
    private var library: MediaLibrarySession? = null
    private lateinit var legacyControlled: Player
    private lateinit var segmentControlled: SegmentSessionPlayer
    private var observedSegmentPort: SegmentMediaControlPort? = null
    private var segmentObserver: Job? = null
    private var segmentProjection: SegmentMediaProjection? = null
    private var usingSegments = false
    @Volatile private var resource: NewsPlaybackResource? = null
    private var tracker: PlaybackIntervalTracker? = null
    private var generation = 0L
    private var userWantsPlay = false
    private var ended = false
    private var focused = false
    @Volatile private var disposed = false
    private var requestedPosition: Long? = null
    private var stationaryPosition: Long? = null
    private val events = Channel<suspend () -> Unit>(64)
    private lateinit var audio: AudioManager
    private lateinit var focusRequest: AudioFocusRequest

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (!disposed) {
                    focused = change == AudioManager.AUDIOFOCUS_GAIN
                    if (!focused) pausePlayback("audio_focus")
                }
                // GAIN never restores the previous play intent.
            }, Handler(Looper.getMainLooper())).build()
        val http = ResolvingDataSource.Factory(DefaultHttpDataSource.Factory()) { request ->
            val pinned = resource ?: throw IOException("No pinned media access.")
            // Resolve every new HTTP/range open on ExoPlayer's loader thread. Cached access is
            // renewed after its monotonic hint, without changing cursor, item, asset or run.
            val uri = runBlocking {
                withTimeoutOrNull(8_000) { runtime?.streamAccess(pinned.key) }
            } ?: throw IOException("Media access is unavailable.")
            if (disposed || resource?.key != pinned.key || uri.scheme != "https")
                throw IOException("Media access was superseded.")
            request.withUri(Uri.parse(uri.toString()))
        }
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
        player = ExoPlayer.Builder(this).setMediaSourceFactory(sources).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), false)
            setHandleAudioBecomingNoisy(true)
            repeatMode = Player.REPEAT_MODE_OFF
            shuffleModeEnabled = false
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying && focused) {
                        val duration = resource?.key?.durationMs ?: return
                        val start = stationaryPosition ?: player.currentPosition
                        if (start in 0..duration) tracker?.beganAt(start, SystemClock.elapsedRealtime()) else tracker?.reset()
                        stationaryPosition = null
                    } else {
                        sample("none")
                        stationaryPosition = player.currentPosition
                    }
                }
                override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                    tracker?.reset()
                    stationaryPosition = new.positionMs.takeIf { it >= 0 }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (segmentProjection != null) return
                    if (state == Player.STATE_ENDED && !ended) {
                        ended = true
                        val pinned = resource ?: return
                        val naturalIntent = userWantsPlay
                        userWantsPlay = false
                        sample("none", naturalIntent)
                        val port = runtime
                        val endingGeneration = generation
                        if (port != null) enqueue {
                            port.ended(pinned.key)
                            if (!disposed && generation == endingGeneration && port.followupReady(pinned.key))
                                load(true, null, pinned.key)
                        }
                        abandonFocus()
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    // Do not retry/emit automatically after an error, including an expired signed URL.
                    pausePlayback("media_error")
                }
                override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) {
                    if (!ready && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
                        pausePlayback("audio_focus")
                }
            })
        }
        val controlled = object : ForwardingPlayer(player) {
            override fun play() { load(true, null) }
            override fun prepare() { load(false, null) }
            override fun pause() { pausePlayback("user") }
            override fun setPlayWhenReady(value: Boolean) { if (value) play() else pause() }
            override fun stop() { pausePlayback("user"); player.stop() }
            override fun seekTo(positionMs: Long) { load(userWantsPlay, positionMs) }
            override fun seekTo(mediaItemIndex: Int, positionMs: Long) { if (mediaItemIndex == 0) seekTo(positionMs) }
            override fun seekBack() { seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }
            override fun seekForward() { seekTo(player.currentPosition + 10_000) }
            override fun seekToDefaultPosition() { seekTo(0) }
            override fun seekToDefaultPosition(mediaItemIndex: Int) { if (mediaItemIndex == 0) seekTo(0) }
            override fun setMediaItem(item: MediaItem) { selectCurrent(listOf(item)) }
            override fun setMediaItem(item: MediaItem, resetPosition: Boolean) { selectCurrent(listOf(item)) }
            override fun setMediaItem(item: MediaItem, startPositionMs: Long) { selectCurrent(listOf(item)) }
            override fun setMediaItems(items: List<MediaItem>) { selectCurrent(items) }
            override fun setMediaItems(items: List<MediaItem>, resetPosition: Boolean) { selectCurrent(items) }
            override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) { selectCurrent(items) }
        }
        legacyControlled = controlled
        segmentControlled = SegmentSessionPlayer(scope) { segmentRuntime }
        library = MediaLibrarySession.Builder(this, controlled, LibraryCallback()).build()
        refreshSegmentPort()
        writer = writerScope.launch {
            for (event in events) {
                try { event() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { writerFailures++; if (!disposed) pausePlayback("storage_unavailable", persist = false) }
            }
        }
        scope.launch {
            while (isActive) {
                delay(500)
                refreshSegmentPort()
                val key = resource?.key ?: continue
                val port = runtime
                val allowed = try { port != null && port.mayPlay(key) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                if (resource?.key != key) continue
                if (!allowed && (player.isPlaying || userWantsPlay)) pausePlayback("output_unavailable")
                else if (player.isPlaying) sample("none")
            }
        }
    }

    private fun selectCurrent(items: List<MediaItem>) {
        if (segmentProjection != null) return
        if (items.size == 1 && items[0].mediaId == CURRENT && items[0].localConfiguration == null) {
            pausePlayback("user")
            resource = null
            tracker = null
            requestedPosition = null
            stationaryPosition = null
            player.clearMediaItems()
            library?.notifyChildrenChanged(ROOT, 0, null)
            load(false, null)
        }
    }

    private fun load(play: Boolean, seek: Long?, followupFrom: NewsPlaybackKey? = null) {
        if (disposed) return
        if (segmentProjection != null) {
            // A stale legacy controller command can neither open a file nor guess a time seek.
            if (seek == null && play) segmentControlled.play()
            return
        }
        val ticket = ++generation
        if (seek != null) requestedPosition = seek
        val requested = requestedPosition
        sample(if (followupFrom == null) "user" else "none", if (followupFrom == null) null else true)
        userWantsPlay = play
        player.pause()
        tracker?.reset()
        scope.launch {
            val port = runtime ?: run { pausePlayback("output_unavailable"); return@launch }
            try {
                val old = resource
                val position = requested ?: player.currentPosition.coerceAtLeast(0)
                val loaded = if (old == null || followupFrom != null) port.current()
                    else port.renew(old.key, position.coerceIn(0, old.key.durationMs))
                val next = loaded?.let { if (old == null && requested != null)
                    NewsPlaybackResource(it.key, it.title, it.uri, requested.coerceIn(0, it.key.durationMs)) else it }
                if (ticket != generation || disposed) return@launch
                val sameArticleFollowup = old?.key == followupFrom && followupFrom?.phase == "main" && next?.key?.let {
                    it.owner == followupFrom.owner && it.selectionId == followupFrom.selectionId &&
                        it.checkpointId == followupFrom.checkpointId && it.content == followupFrom.content && it.phase == "followup"
                } == true
                if (next == null || (old != null && next.key != old.key && !sameArticleFollowup) || !port.mayPlay(next.key)) {
                    pausePlayback("output_unavailable"); return@launch
                }
                if (ticket != generation || disposed) return@launch
                resource = next
                library?.notifyChildrenChanged(ROOT, 1, null)
                requestedPosition = null
                tracker = PlaybackIntervalTracker(next.key.durationMs)
                ended = false
                val item = MediaItem.Builder().setMediaId(CURRENT).setUri(next.uri.toString())
                    .setMediaMetadata(metadata(next.title, playable = true)).build()
                player.setMediaItem(item, next.positionMs)
                stationaryPosition = next.positionMs
                player.prepare()
                if (play && userWantsPlay && ticket == generation) {
                    focused = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                    if (focused) player.play() else pausePlayback("audio_focus")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { if (ticket == generation) pausePlayback("media_unavailable") }
        }
    }

    private fun sample(reason: String, playIntentOverride: Boolean? = null) {
        val pinned = resource ?: return
        if (player.currentPosition < 0) { tracker?.reset(); return }
        val position = player.currentPosition.coerceIn(0, pinned.key.durationMs)
        val interval = tracker?.sample(position, SystemClock.elapsedRealtime(), player.isPlaying && focused)
        val observed = NewsPlaybackPosition(pinned.key, position, playIntentOverride ?: userWantsPlay, reason, player.isPlaying && focused)
        val port = runtime ?: return
        enqueue { port.persist(observed, interval) }
    }

    private fun enqueue(event: suspend () -> Unit): Boolean {
        val sent = events.trySend(event).isSuccess
        if (!sent) pausePlayback("storage_unavailable", persist = false)
        return sent
    }

    private fun pausePlayback(reason: String, persist: Boolean = true) {
        generation++
        userWantsPlay = false
        if (persist) sample(reason)
        tracker?.reset()
        player.pause()
        abandonFocus()
    }

    private fun abandonFocus() { focused = false; audio.abandonAudioFocusRequest(focusRequest) }

    /** Dynamic provider binding lets the application install the process runtime after connect. */
    private fun refreshSegmentPort() {
        val port = segmentRuntime
        if (port === observedSegmentPort) return
        segmentObserver?.cancel(); observedSegmentPort = port
        if (port == null) publishSegments(null)
        else segmentObserver = scope.launch { port.state.collect { projection ->
            if (observedSegmentPort === port) publishSegments(projection)
        } }
    }
    private fun publishSegments(value: SegmentMediaProjection?) {
        if (disposed) return
        segmentProjection = value
        segmentControlled.publish(value)
        if (value != null && !usingSegments) {
            // Existing file output is stopped first. The facade itself never synthesizes audio.
            pausePlayback("transition")
            usingSegments = true
            library?.setPlayer(segmentControlled)
        } else if (value == null && usingSegments) {
            usingSegments = false
            library?.setPlayer(legacyControlled)
        }
        library?.notifyChildrenChanged(ROOT, if (value != null || resource != null) 1 else 0, null)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = library

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.uid != applicationInfo.uid && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
            val allowed = Player.Commands.Builder().addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE,
                Player.COMMAND_STOP, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA, Player.COMMAND_GET_TIMELINE).build()
            return MediaSession.ConnectionResult.accept(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(FREEZE, Bundle.EMPTY)).build(), allowed)
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): com.google.common.util.concurrent.ListenableFuture<SessionResult> {
            if (command.customAction != FREEZE || controller.uid != applicationInfo.uid)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            val result = SettableFuture.create<SessionResult>()
            val segment = segmentProjection
            if (segment != null) {
                val port = segmentRuntime
                scope.launch {
                    val saved = try { port != null && port.state.value?.key == segment.key && port.command(segment.key, SegmentMediaCommand.FREEZE) }
                        catch (cancelled: CancellationException) { result.cancel(false); throw cancelled }
                        catch (_: Exception) { false }
                    result.set(SessionResult(if (saved) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_IO))
                }
                return result
            }
            val before = writerFailures
            pausePlayback("transition")
            if (!enqueue { result.set(SessionResult(if (writerFailures == before) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_IO)) })
                result.set(SessionResult(SessionResult.RESULT_ERROR_IO))
            return result
        }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?) =
            Futures.immediateFuture(LibraryResult.ofItem(MediaItem.Builder().setMediaId(ROOT)
                .setMediaMetadata(metadata("Drive English", false, true)).build(), params))

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            parentId: String, page: Int, pageSize: Int, params: LibraryParams?) =
            Futures.immediateFuture(if (parentId != ROOT || page < 0 || pageSize !in 1..100)
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            else LibraryResult.ofItemList(if (page == 0 && (resource != null || segmentProjection != null)) listOf(currentItem()) else emptyList(), params))

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String) =
            Futures.immediateFuture(if (mediaId == CURRENT && (resource != null || segmentProjection != null)) LibraryResult.ofItem(currentItem(), null)
                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))

        override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, items: List<MediaItem>) =
            Futures.immediateFuture(if (items.size == 1 && items.single().mediaId == CURRENT)
                listOf(currentItem()) else emptyList())
    }

    private fun currentItem() = MediaItem.Builder().setMediaId(CURRENT)
        .setMediaMetadata(metadata(segmentProjection?.title ?: resource?.title ?: "현재 기사", segmentProjection?.available ?: true)).build()

    private fun metadata(title: String, playable: Boolean, browsable: Boolean = false) = MediaMetadata.Builder()
        .setTitle(title).setIsPlayable(playable).setIsBrowsable(browsable)
        .setMediaType(if (browsable) MediaMetadata.MEDIA_TYPE_FOLDER_MIXED else MediaMetadata.MEDIA_TYPE_NEWS).build()

    override fun onDestroy() {
        val segment = segmentProjection
        val port = segmentRuntime
        if (segment != null && port != null) writerScope.launch {
            withContext(NonCancellable) { try { port.command(segment.key, SegmentMediaCommand.STOP) } catch (_: Exception) { } }
        }
        segmentObserver?.cancel()
        disposed = true
        pausePlayback("service_stopped")
        player.release()
        segmentControlled.release()
        library?.release()
        library = null
        events.close()
        scope.cancel()
        // Drain captured events while this process survives. Process death can lose only uncommitted
        // samples; the next process restores the last durable checkpoint without starting output.
        writer?.invokeOnCompletion { writerScope.cancel() }
        super.onDestroy()
    }

    companion object {
        const val ROOT = "news"
        const val CURRENT = "current"
        const val FREEZE = "io.github.driveenglishlab.pleos.FREEZE_PLAYBACK"
    }
}
