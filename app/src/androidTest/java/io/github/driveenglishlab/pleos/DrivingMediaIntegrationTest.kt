package io.github.driveenglishlab.pleos

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.browse.MediaBrowser as PlatformBrowser
import android.media.session.MediaController as PlatformController
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import java.util.concurrent.CountDownLatch
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.platform.media.NewsMediaService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class DrivingMediaIntegrationTest {
    private fun <T> main(block: () -> T): T {
        val box = java.util.concurrent.atomic.AtomicReference<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { box.set(block()) }
        return box.get()
    }
    @Test fun modernBrowseSearchAndIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val browser = main { MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, NewsMediaService::class.java))).buildAsync() }.get(10, TimeUnit.SECONDS)
        try {
            assertFalse(main { browser.playWhenReady })
            assertFalse(main { browser.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE) })
            assertFalse(main { browser.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE) })
            assertFalse(main { browser.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) })
            val root = main { browser.getLibraryRoot(null) }.get(5, TimeUnit.SECONDS).value!!
            assertEquals("drive-english", root.mediaId)
            val folder = main { browser.getChildren(root.mediaId, 0, 10, null) }.get(5, TimeUnit.SECONDS).value!!
            assertEquals("news", folder.single().mediaId)
            assertEquals("news", main { browser.getItem("news") }.get(5, TimeUnit.SECONDS).value?.mediaId)
            assertEquals(0, main { browser.subscribe("news", null) }.get(5, TimeUnit.SECONDS).resultCode)
            val items = main { browser.getChildren("news", 0, 10, null) }.get(5, TimeUnit.SECONDS).value!!
            assertEquals(3, items.size)
            main { browser.search("library", null) }.get(5, TimeUnit.SECONDS)
            val results = main { browser.getSearchResult("library", 0, 10, null) }.get(5, TimeUnit.SECONDS).value!!
            assertEquals("city-library", results.single().mediaId)
            assertNotEquals(0, main { browser.getItem("https://untrusted.example/audio.mp3") }.get(5, TimeUnit.SECONDS).resultCode)
            main { browser.setMediaItem(MediaItem.Builder().setMediaId(items.first().mediaId).build()); browser.prepare() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (System.nanoTime() < deadline && main { browser.currentMediaItem?.mediaId } != items.first().mediaId) Thread.sleep(80)
            assertEquals(items.first().mediaId, main { browser.currentMediaItem?.mediaId })
            assertFalse(main { browser.playWhenReady })
            main { browser.stop() }
        } finally { main { browser.release() } }
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun browser(): MediaBrowser = main { MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, NewsMediaService::class.java))).buildAsync() }.get(10, TimeUnit.SECONDS)
    private fun await(message: String, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < end) { if (condition()) return; Thread.sleep(50) }
        assertTrue(message, condition())
    }
    private fun select(browser: MediaBrowser, id: String, playing: Boolean = false, position: Long = 0) {
        main { browser.pause(); browser.setMediaItem(MediaItem.Builder().setMediaId(id).build(), position); browser.prepare() }
        await("selected/ready " + id) { main { browser.currentMediaItem?.mediaId == id && browser.playbackState == Player.STATE_READY } }
        if (playing) { main { browser.play() }; await("actual playback") { main { browser.isPlaying } } }
    }
    @Test fun twoControllersSharePlaybackSeekAndStopIntent() {
        val first = browser(); val second = browser()
        try {
            select(first, "city-library", true)
            main { first.seekForward() }
            await("seek shared") { main { second.currentPosition >= 9_000 } }
            main { second.pause() }
            await("paused in both") { main { !first.playWhenReady && !second.playWhenReady } }
            assertEquals(main { first.currentMediaItem?.mediaId }, main { second.currentMediaItem?.mediaId })
            assertTrue(kotlin.math.abs(main { first.currentPosition - second.currentPosition }) < 1000)
            main { second.stop() }
            await("STOP clears intent") { main { !first.playWhenReady && first.playbackState == Player.STATE_IDLE } }
            @Suppress("DEPRECATION")
            fun serviceForeground() = context.getSystemService(android.app.ActivityManager::class.java)
                .getRunningServices(Int.MAX_VALUE).any { it.service.className == NewsMediaService::class.java.name && it.foreground }
            await("STOP removes foreground notification") { !serviceForeground() }
            Thread.sleep(500)
            assertFalse(main { first.isPlaying })
        } finally { main { first.stop(); first.release(); second.release() } }
    }
    @Test fun endAndArticleBoundariesDoNotAutoplayOrWrap() {
        val b = browser()
        try {
            select(b, "city-library", true, 37_700)
            await("pause at article end") { main { !b.playWhenReady && b.currentPosition >= 38_000 } }
            assertEquals("city-library", main { b.currentMediaItem?.mediaId })
            main { b.seekToNextMediaItem() }
            await("explicit next") { main { b.currentMediaItem?.mediaId == "cooler-streets" } }
            assertFalse(main { b.playWhenReady })
            select(b, "second-life")
            assertFalse(main { b.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) })
            main { b.seekToNextMediaItem() }
            assertEquals("second-life", main { b.currentMediaItem?.mediaId })
            select(b, "city-library")
            assertFalse(main { b.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) })
        } finally { main { b.stop(); b.release() } }
    }
    @Test fun platformLegacyBrowseIdSearchPlayAndStop() {
        lateinit var legacy: PlatformBrowser
        val connected = CountDownLatch(1)
        main { legacy = PlatformBrowser(context, ComponentName(context, NewsMediaService::class.java), object : PlatformBrowser.ConnectionCallback() { override fun onConnected() { connected.countDown() } }, null); legacy.connect() }
        assertTrue("platform browser connected", connected.await(8, TimeUnit.SECONDS))
        try {
            val children = CountDownLatch(1)
            var count = 0
            main { legacy.subscribe("news", object : PlatformBrowser.SubscriptionCallback() { override fun onChildrenLoaded(parentId: String, items: MutableList<PlatformBrowser.MediaItem>) { count = items.size; children.countDown() } }) }
            assertTrue(children.await(8, TimeUnit.SECONDS)); assertEquals(3, count)
            val controller = PlatformController(context, legacy.sessionToken)
            main { controller.transportControls.playFromMediaId("city-library", null) }
            await("legacy ID actual play") { controller.metadata?.description?.mediaId == "city-library" && controller.playbackState?.state == 3 }
            assertEquals(0L, controller.playbackState!!.actions and android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            main { controller.transportControls.pause() }
            await("legacy pause") { controller.playbackState?.state == 2 }
            main { controller.transportControls.playFromSearch("cooler", null) }
            await("legacy search actual play") { controller.metadata?.description?.mediaId == "cooler-streets" && controller.playbackState?.state == 3 }
            main { controller.transportControls.seekTo(7_000) }
            await("legacy seek before previous") { controller.playbackState!!.position >= 6_000 }
            main { controller.transportControls.skipToPrevious() }
            await("legacy previous selects article rather than restarting current") { controller.metadata?.description?.mediaId == "city-library" && controller.playbackState?.state == 3 }
            main { controller.transportControls.skipToNext() }
            await("legacy next retains playing") { controller.metadata?.description?.mediaId == "cooler-streets" && controller.playbackState?.state == 3 }
            main { controller.transportControls.skipToNext() }
            await("legacy final article") { controller.metadata?.description?.mediaId == "second-life" }
            await("last article disables next") { controller.playbackState!!.actions and android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT == 0L }
            main { controller.transportControls.pause(); controller.transportControls.skipToPrevious() }
            await("legacy previous retains paused") { controller.metadata?.description?.mediaId == "cooler-streets" && controller.playbackState?.state == 2 }
            main { controller.transportControls.stop() }
            await("legacy STOP") { controller.playbackState?.state == 0 && controller.playbackState?.position?.let { it >= 0 } == true }
        } finally { main { legacy.disconnect() } }
    }
    @Test fun compatLibrarySearchIsCatalogScoped() {
        lateinit var compat: MediaBrowserCompat
        val connected = CountDownLatch(1)
        main { compat = MediaBrowserCompat(context, ComponentName(context, NewsMediaService::class.java), object : MediaBrowserCompat.ConnectionCallback() { override fun onConnected() { connected.countDown() } }, null); compat.connect() }
        assertTrue(connected.await(8, TimeUnit.SECONDS))
        try {
            val found = CountDownLatch(1); var ids = emptyList<String>()
            main { compat.search("library", null, object : MediaBrowserCompat.SearchCallback() { override fun onSearchResult(query: String, extras: Bundle?, items: MutableList<MediaBrowserCompat.MediaItem>) { ids = items.mapNotNull { it.mediaId }; found.countDown() } }) }
            assertTrue("compat library search", found.await(8, TimeUnit.SECONDS)); assertEquals(listOf("city-library"), ids)
        } finally { main { compat.disconnect() } }
    }
    @Test fun transientFocusAndExplicitStopCannotReviveAudio() {
        val b = browser()
        val manager = context.getSystemService(AudioManager::class.java)
        val transient = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ }, android.os.Handler(android.os.Looper.getMainLooper())).build()
        val permanent = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener({ }, android.os.Handler(android.os.Looper.getMainLooper())).build()
        try {
            select(b, "city-library", true)
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, main { manager.requestAudioFocus(transient) })
            await("speech suppressed for navigation") { main { !b.isPlaying } }
            assertTrue(main { b.playWhenReady })
            main { manager.abandonAudioFocusRequest(transient) }
            await("transient gain resumes previous intent") { main { b.isPlaying } }
            main { manager.requestAudioFocus(transient) }
            await("second transient loss") { main { !b.isPlaying } }
            main { b.seekToNextMediaItem() }
            await("article move retains transient play intent") { main { b.currentMediaItem?.mediaId == "cooler-streets" && b.playWhenReady } }
            main { b.stop(); manager.abandonAudioFocusRequest(transient) }
            await("user STOP wins gain") { main { !b.playWhenReady && b.playbackState == Player.STATE_IDLE } }
            Thread.sleep(300); assertFalse(main { b.isPlaying })
            select(b, "city-library", true)
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, main { manager.requestAudioFocus(permanent) })
            await("permanent loss stops") { main { !b.playWhenReady && b.playbackState == Player.STATE_IDLE } }
            main { manager.abandonAudioFocusRequest(permanent) }
            Thread.sleep(300); assertFalse(main { b.isPlaying })
        } finally { main { manager.abandonAudioFocusRequest(transient); manager.abandonAudioFocusRequest(permanent); b.stop(); b.release() } }
    }
    @Test fun actualActivityRecreationKeepsTheServicePlayer() {
        val b = browser()
        val scenario = ActivityScenario.launch<DesignPreviewActivity>(Intent(context, DesignPreviewActivity::class.java))
        try {
            select(b, "city-library", true)
            val before = main { b.currentPosition }
            scenario.recreate()
            await("same playing article after recreation") { main { b.isPlaying && b.currentMediaItem?.mediaId == "city-library" && b.currentPosition >= before } }
        } finally { main { b.stop(); b.release() }; scenario.close() }
    }

    @Test fun actualCarClientDisconnectClosesTheDetailPredicate() {
        if (android.os.Build.VERSION.SDK_INT < 34) return
        val source = main { io.github.driveenglishlab.pleos.platform.vehicle.DrivingAccessSource(context).also { it.start() } }
        try {
            await("actual car service connected") { source.state.value.connected }
            main { source.close() }
            assertFalse(source.state.value.connected)
            assertFalse(source.state.value.detailsAllowed)
            assertNull(source.state.value.gear)
            assertNull(source.state.value.speed)
        } finally { main { source.close() } }
    }
    @Test fun actualTaskRemovalStopsPlaybackAndRebindDoesNotAutoplay() {
        val b = browser()
        val scenario = ActivityScenario.launch<DesignPreviewActivity>(Intent(context, DesignPreviewActivity::class.java))
        try {
            select(b, "city-library", true)
            val generation = io.github.driveenglishlab.pleos.platform.media.MediaPlaybackSignals.stopGeneration
            main { context.getSystemService(android.app.ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() } }
            await("task removal invokes STOP before stopSelf") {
                main { !b.playWhenReady && b.playbackState == Player.STATE_IDLE && io.github.driveenglishlab.pleos.platform.media.MediaPlaybackSignals.stopGeneration > generation }
            }
            val fresh = browser()
            try { assertFalse(main { fresh.playWhenReady }); assertFalse(main { fresh.isPlaying }) }
            finally { main { fresh.release() } }
        } finally { main { b.stop(); b.release() }; scenario.close() }
    }

}
