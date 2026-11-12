package io.github.driveenglishlab.pleos.platform.media

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Process
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import io.github.driveenglishlab.pleos.platform.vehicle.DrivingAccessSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class NewsMediaService : MediaLibraryService() {
    @Inject lateinit var catalog: NewsMediaCatalog
    private var session: MediaLibrarySession? = null
    private lateinit var exo: ExoPlayer
    private lateinit var transport: OutputPolicyPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var vehicle: DrivingAccessSource? = null
    private val articles get() = catalog.articles

    override fun onCreate() {
        super.onCreate()
        // Explicit STOP must remove the foreground notification, not keep Media3's idle timeout.
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)
        exo = ExoPlayer.Builder(this).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
            .setPauseAtEndOfMediaItems(true).build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
                repeatMode = Player.REPEAT_MODE_OFF
                addListener(object : Player.Listener {
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS && !exo.playWhenReady) stopMedia()
                    }
                    override fun onEvents(player: Player, events: Player.Events) {
                        if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) || events.contains(Player.EVENT_IS_PLAYING_CHANGED) || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                            Log.i(TAG, "state=${player.playbackState} playing=${player.isPlaying} intent=${player.playWhenReady} id=${player.currentMediaItem?.mediaId} pos=${player.currentPosition}")
                        }
                    }
                })
            }
        transport = OutputPolicyPlayer(exo, ::stopMedia)
        val callback = CatalogCallback()
        session = MediaLibrarySession.Builder(this, transport, callback).apply {
            packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
                setSessionActivity(PendingIntent.getActivity(this@NewsMediaService, 0, intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        }.build()
        if (Build.VERSION.SDK_INT >= 34) {
            vehicle = DrivingAccessSource(this).also { source ->
                scope.launch { source.state.collect { it.componentAllowed?.let(transport::updateOutputAllowed) } }
                source.start()
            }
        }
        // Media3 owns foreground notification/startForegroundService while playing.
        // Also retain started-service task-removal semantics for a foreground bind.
        try { startService(Intent(this, NewsMediaService::class.java)) }
        catch (_: IllegalStateException) { Log.i(TAG, "Background browse; Media3 will start foreground on explicit playback") }
        Log.i(TAG, "created instance=${System.identityHashCode(this)} player=${System.identityHashCode(exo)} catalog=${articles.size}")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session
    private fun stopMedia() {
        MediaPlaybackSignals.stopped()
        exo.pause() // ExoPlayer.stop alone retains playWhenReady.
        exo.stop()
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task_removed")
        stopMedia(); stopSelf(); super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        MediaPlaybackSignals.stopped()
        if (Build.VERSION.SDK_INT >= 34) vehicle?.close()
        scope.cancel()
        session?.release(); session = null
        if (::exo.isInitialized) exo.release()
        super.onDestroy()
        Log.i(TAG, "destroyed")
    }

    private fun NewsArticle.item(): MediaItem = MediaItem.Builder().setMediaId(id).setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist("Drive English · 창작 시연 기사")
            .setDescription(summary).setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
            .setExtras(Bundle().apply { putString("created_at", createdAt); putLong("duration_ms", durationMs) }).build()).build()
    private fun folder(id: String, title: String) = MediaItem.Builder().setMediaId(id).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).build()).build()
    private fun page(items: List<MediaItem>, page: Int, size: Int): List<MediaItem> {
        if (page < 0 || size < 1) return emptyList()
        val start = page.toLong() * size
        return if (start >= items.size) emptyList() else items.drop(start.toInt()).take(size)
    }
    private fun resolve(request: MediaItem): NewsArticle? {
        val query = request.requestMetadata.searchQuery
        return if (!query.isNullOrBlank()) catalog.search(query).firstOrNull() else catalog.find(request.mediaId)
    }
    private fun allowed(controller: MediaSession.ControllerInfo): Boolean {
        if (controller.uid == Process.myUid() || controller.isTrusted) return true
        // This installed Pleos system media host is a verified package, not a general URI client.
        return controller.packageName == "ai.umos.mediaapp" && runCatching {
            packageManager.getApplicationInfo(controller.packageName, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
        }.getOrDefault(false)
    }

    private inner class CatalogCallback : MediaLibrarySession.Callback {
        override fun onConnect(s: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            Log.i(TAG, "controller=${controller.packageName} trusted=${controller.isTrusted} allowed=${allowed(controller)}")
            if (!allowed(controller)) return MediaSession.ConnectionResult.reject()
            // External clients select catalog IDs; they cannot reorder/extend this finite queue.
            return MediaSession.ConnectionResult.AcceptedResultBuilder(s, controller)
                .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS)
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon().removeAll(
                    Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE).build())
                .build()
        }
        override fun onPlayerCommandRequest(s: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int =
            if (!transport.outputAllowed && (playerCommand == Player.COMMAND_PLAY_PAUSE || playerCommand == Player.COMMAND_PREPARE)) SessionError.ERROR_PERMISSION_DENIED
            else super.onPlayerCommandRequest(s, controller, playerCommand)
        override fun onGetLibraryRoot(s: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder("drive-english", "Drive English"), params))
        override fun onGetChildren(s: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val items = when (parentId) { "drive-english" -> listOf(folder("news", "뉴스 · 창작 기사")); "news" -> articles.map { it.item() }; else -> return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)) }
            if (page < 0 || pageSize < 1) return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItemList(page(items, page, pageSize), params))
        }
        override fun onGetItem(s: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            val item = when (mediaId) {
                "drive-english" -> folder("drive-english", "Drive English")
                "news" -> folder("news", "뉴스 · 창작 기사")
                else -> catalog.find(mediaId)?.item()
            }
            return Futures.immediateFuture(item?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
        }
        override fun onSearch(s: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
            s.notifySearchResultChanged(browser, query, catalog.search(query).size, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }
        override fun onGetSearchResult(s: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(page(catalog.search(query).map { it.item() }, page, pageSize), params))
        override fun onAddMediaItems(s: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
            val resolved = mediaItems.map { resolve(it)?.item() ?: return Futures.immediateFailedFuture(IllegalArgumentException("Unknown catalog item")) }
            return Futures.immediateFuture(resolved)
        }
        override fun onSetMediaItems(s: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (mediaItems.isEmpty()) return Futures.immediateFailedFuture(IllegalArgumentException("No catalog item"))
            val request = mediaItems.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex)
            val article = request?.let(::resolve) ?: return Futures.immediateFailedFuture(IllegalArgumentException("Unknown catalog item"))
            val index = articles.indexOfFirst { it.id == article.id }
            val position = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs.coerceIn(0, article.durationMs)
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(articles.map { it.item() }, index, position))
        }
    }
    companion object { private const val TAG = "DriveEnglishMedia" }
}

/** Keep output blocked for the entire disallowed policy, including later external PLAY. */
@OptIn(UnstableApi::class)
internal class OutputPolicyPlayer(player: ExoPlayer, private val onExplicitStop: () -> Unit) : ForwardingSimpleBasePlayer(player) {
    var outputAllowed = true
        private set
    fun updateOutputAllowed(allowed: Boolean) {
        if (outputAllowed == allowed) return
        outputAllowed = allowed
        if (!allowed) onExplicitStop()
        invalidateState() // Reallow only restores commands, never playing intent.
    }
    override fun getState(): SimpleBasePlayer.State {
        val state = super.getState()
        return if (outputAllowed) state else state.buildUpon().setAvailableCommands(
            state.availableCommands.buildUpon().removeAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE).build()).build()
    }
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
        if (outputAllowed || !playWhenReady) super.handleSetPlayWhenReady(playWhenReady)
        else Futures.immediateVoidFuture()
    override fun handlePrepare(): ListenableFuture<*> =
        if (outputAllowed) super.handlePrepare() else Futures.immediateVoidFuture()
    override fun handleStop(): ListenableFuture<*> { onExplicitStop(); return Futures.immediateVoidFuture() }
}
