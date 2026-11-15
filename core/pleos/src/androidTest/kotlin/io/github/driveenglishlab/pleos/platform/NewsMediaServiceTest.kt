package io.github.driveenglishlab.pleos.platform

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.domain.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

class MediaTestActivity : Activity()

@UnstableApi
class MediaTestService : NewsMediaService() {
    override val runtime: NewsPlaybackPort? get() = port
    companion object { @Volatile var port: NewsPlaybackPort? = null }
}

@UnstableApi
@RunWith(AndroidJUnit4::class)
class NewsMediaServiceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < until) {
            var ok = false
            main { ok = predicate() }
            if (ok) return
            SystemClock.sleep(25)
        }
        fail("Timed out waiting for the real media service state.")
    }

    private class Port(val value: NewsPlaybackResource) : NewsPlaybackPort {
        @Volatile var allowed = true
        @Volatile var endedCount = 0
        @Volatile var loads = 0
        var delay: CompletableDeferred<Unit>? = null
        var outputDelay: CompletableDeferred<Unit>? = null
        @Volatile var outputChecks = 0
        @Volatile var actualFocusSeen = false
        @Volatile var grantEnabled = true
        // Optional real Core consumer for the held-writer race; other service cases keep their owned fixture port.
        var core: SessionCoordinator? = null
        var holdNextObservation: CompletableDeferred<Unit>? = null
        var holdNextObservationReason: String? = null
        var observationEntered: CompletableDeferred<NewsPlaybackPosition>? = null
        var observationCommitted: CompletableDeferred<Unit>? = null
        val positions = CopyOnWriteArrayList<NewsPlaybackPosition>()
        val intervals = CopyOnWriteArrayList<PlayedInterval>()
        override suspend fun current(): NewsPlaybackResource { loads++; delay?.await(); return value }
        override suspend fun renew(key: NewsPlaybackKey, positionMs: Long): NewsPlaybackResource {
            loads++; delay?.await()
            return NewsPlaybackResource(value.key, value.title, value.uri, positionMs)
        }
        override suspend fun mayPlay(key: NewsPlaybackKey) = allowed && key == value.key
        override suspend fun beginExplicitPlay(): NewsMediaPlayGrant? {
            if (!allowed || !grantEnabled) return null
            val key = value.key
            val coordinator = core ?: return NewsMediaPlayGrant(key.owner, key.selectionId, key.checkpointId, key.content, 1, 1)
            val before = coordinator.snapshot()
            if (!coordinator.requestMediaIntent(key.owner, PlaybackIntent.PLAY, before.navigationGeneration,
                    before.intentGeneration, key.selectionId)) return null
            val after = coordinator.snapshot()
            return NewsMediaPlayGrant(key.owner, key.selectionId, key.checkpointId, key.content,
                after.navigationGeneration, after.intentGeneration)
        }
        override suspend fun mayOutput(key: NewsPlaybackKey, grant: NewsMediaPlayGrant, focusLease: () -> Boolean): Boolean {
            outputChecks++
            actualFocusSeen = actualFocusSeen || focusLease()
            outputDelay?.await()
            return allowed && grant.matches(key) && key == value.key && focusLease() &&
                (core?.mediaFocusedPermission(key, grant.navigationGeneration, grant.intentGeneration, focusLease) ?: true)
        }
        override suspend fun persist(position: NewsPlaybackPosition, interval: PlayedInterval?) {
            val barrier = holdNextObservation?.takeIf { holdNextObservationReason == null || position.pauseReason == holdNextObservationReason }
            if (barrier != null) {
                holdNextObservation = null
                observationEntered?.complete(position)
                barrier.await()
            }
            positions.add(position); interval?.let(intervals::add)
            core?.observeMedia(position.key, position.positionMs, position.playIntent, position.playing, position.pauseReason,
                expectedIntentGeneration = position.intentGeneration)
            if (barrier != null) observationCommitted?.complete(Unit)
        }
        override suspend fun ended(key: NewsPlaybackKey) { check(key == value.key); endedCount++ }
    }

    private fun <T> withBrowser(test: (MediaBrowser, Port, Activity) -> T): T {
        // Own silent PCM fixture: no news content, network, signed credential or actual Gleo output.
        val pcm = ByteArray(8_000 * 2 * 4)
        val wav = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(pcm.size); put(pcm)
        }.array()
        val file = java.io.File(context.cacheDir, "own-silent-media-test.wav").apply { writeBytes(wav) }
        val owner = OwnerContext(UUID.randomUUID(), 0, 1, UUID.randomUUID())
        val content = ContentRef(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ListeningMode.ORIGINAL_FULL)
        val key = NewsPlaybackKey(owner, UUID.randomUUID(), UUID.randomUUID(), content, content.listeningScriptId,
            UUID.randomUUID(), 4_000, UUID.randomUUID(), "main")
        val port = Port(NewsPlaybackResource(key, "Owned silent test fixture", file.toURI(), 0))
        MediaTestService.port = port
        val activity = instrumentation.startActivitySync(Intent(context, MediaTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val component = ComponentName(context, MediaTestService::class.java)
        var future: com.google.common.util.concurrent.ListenableFuture<MediaBrowser>? = null
        var browser: MediaBrowser? = null
        try {
            main { future = MediaBrowser.Builder(context, SessionToken(context, component)).buildAsync() }
            browser = requireNotNull(future).get(8, TimeUnit.SECONDS)
            return test(browser, port, activity)
        } finally {
            main { browser?.release(); activity.finish(); context.stopService(Intent().setComponent(component)) }
            MediaTestService.port = null
            file.delete()
        }
    }

    @Test fun browserPauseSeekAndEosNeverAdvanceTheArticle() = withBrowser { browser, port, _ ->
        var root: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.LibraryResult<androidx.media3.common.MediaItem>>? = null
        main { root = browser.getLibraryRoot(null) }
        assertEquals(NewsMediaService.ROOT, root!!.get(4, TimeUnit.SECONDS).value!!.mediaId)
        main {
            assertFalse(browser.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
            browser.play()
        }
        await { browser.isPlaying }
        await { port.intervals.isNotEmpty() }
        // A delayed first sample can legitimately leave an unknown prefix; never manufacture
        // that prefix just to label the article complete. Domain tests cover timely 0-start.
        main { browser.pause() }
        await { !browser.isPlaying && port.positions.any { it.pauseReason == "user" && !it.playIntent } }
        val count = port.intervals.size
        SystemClock.sleep(600)
        assertEquals(count, port.intervals.size)
        main { browser.seekTo(3_200); browser.play() }
        await { browser.playbackState == Player.STATE_ENDED }
        await { port.endedCount == 1 }
        assertTrue(port.intervals.none { it.startMs < 2_000 && it.endMs > 3_000 })
        SystemClock.sleep(600)
        assertEquals(1, port.endedCount)
        main { assertFalse(browser.isPlaying); assertEquals(1, browser.mediaItemCount) }
    }

    @Test fun pauseSupersedesALateCredentialReply() = withBrowser { browser, port, _ ->
        val delayed = CompletableDeferred<Unit>(); port.delay = delayed
        main { browser.play() }
        await { port.loads >= 1 }
        main { browser.pause() }
        delayed.complete(Unit)
        SystemClock.sleep(800)
        main { assertFalse(browser.isPlaying); assertFalse(browser.playWhenReady) }
        assertTrue(port.intervals.isEmpty())
        assertEquals(0, port.endedCount)
    }

    @Test fun heldPausedPositionDuringLoadCannotCancelTheNewExplicitCorePlayGrant() = withBrowser { browser, port, _ ->
        // Owned silent media + synthetic current vehicle/route metadata. Core reducer and actual
        // service/focus are real; this is not a news/vehicle/Gleo integration-success claim.
        // A real explicit PLAY first establishes the asset; prepare alone cannot start a run.
        main { browser.play() }
        await { browser.isPlaying }
        main { browser.pause() }
        await { !browser.isPlaying && !browser.playWhenReady }
        val key = port.value.key
        val coordinator = SessionCoordinator()
        runBlocking {
            coordinator.activate(key.owner, null)
            coordinator.updateAccess(AccessState(ownerValid = true, power = PowerAvailability.UNKNOWN,
                safety = SafetyState(VehiclePosition.PARKED, true, true, true), window = WindowState(true, true),
                components = ComponentPolicies(7, ComponentAccess(true, true, 7), ComponentAccess(true, true, 7)),
                mediaRouteAvailable = true))
            coordinator.setMediaConnected(true)
            val ticket = requireNotNull(coordinator.beginOperation(SessionOperationKind.SELECTION, key.owner))
            assertTrue(coordinator.admitListening(RepositoryCompletion(ticket, ListeningWorkRef(UUID.randomUUID(), key.selectionId,
                key.checkpointId, key.content, ListeningStage.MAIN, key.assetId, key.durationMs, key.mainRunId, 1)), 0, true))
            assertTrue(coordinator.requestMediaIntent(key.owner, PlaybackIntent.USER_PAUSED))
        }
        port.core = coordinator
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<NewsPlaybackPosition>()
        val committed = CompletableDeferred<Unit>()
        port.holdNextObservation = release; port.observationEntered = entered; port.observationCommitted = committed
        try {
            main { browser.play() }
            val priorPausedPosition = runBlocking { withTimeout(8_000) { entered.await() } }
            assertFalse(priorPausedPosition.playIntent)
            assertEquals("transition", priorPausedPosition.pauseReason)
            await { browser.isPlaying }
            val newGrant = runBlocking { coordinator.snapshot() }
            assertEquals(PlaybackIntent.PLAY, newGrant.intent)
            release.complete(Unit)
            runBlocking { withTimeout(8_000) { committed.await() } }
            val afterCommit = runBlocking { coordinator.snapshot() }
            assertEquals(PlaybackIntent.PLAY, afterCommit.intent)
            assertEquals(newGrant.intentGeneration, afterCommit.intentGeneration)
            assertFalse(afterCommit.requiresExplicitMediaResume)
            assertTrue(port.positions.contains(priorPausedPosition))
            assertTrue(requireNotNull(afterCommit.mediaPositionMs) >= requireNotNull(newGrant.mediaPositionMs))

            // Hold a real user pause too: its original grant must not stop a later explicit PLAY.
            val releasePause = CompletableDeferred<Unit>()
            val enteredPause = CompletableDeferred<NewsPlaybackPosition>()
            val committedPause = CompletableDeferred<Unit>()
            port.holdNextObservation = releasePause; port.holdNextObservationReason = "user"
            port.observationEntered = enteredPause; port.observationCommitted = committedPause
            try {
                main { browser.pause() }
                val oldPause = runBlocking { withTimeout(8_000) { enteredPause.await() } }
                assertFalse(oldPause.playIntent)
                assertEquals("user", oldPause.pauseReason)
                assertEquals(newGrant.intentGeneration, oldPause.intentGeneration)
                main { browser.play() }
                await { browser.isPlaying }
                val resumed = runBlocking { coordinator.snapshot() }
                assertTrue(resumed.intentGeneration > newGrant.intentGeneration)
                releasePause.complete(Unit)
                runBlocking { withTimeout(8_000) { committedPause.await() } }
                val afterOldPause = runBlocking { coordinator.snapshot() }
                assertEquals(PlaybackIntent.PLAY, afterOldPause.intent)
                assertEquals(resumed.intentGeneration, afterOldPause.intentGeneration)
                assertFalse(afterOldPause.requiresExplicitMediaResume)
                assertTrue(port.positions.contains(oldPause))
            } finally { releasePause.complete(Unit) }

            main { browser.pause() }
            await { coordinator.state.value.intent == PlaybackIntent.USER_PAUSED && !browser.isPlaying }
            assertTrue(coordinator.state.value.requiresExplicitMediaResume)
        } finally { release.complete(Unit) }
    }

    @Test fun pauseDuringActualPostFocusCheckRejectsTheLateOutputPermission() = withBrowser { browser, port, _ ->
        // Synthetic product authority; the service still must obtain its real Android focus.
        val delayed = CompletableDeferred<Unit>(); port.outputDelay = delayed
        main { browser.play() }
        await { port.outputChecks > 0 }
        assertTrue("Output verification must follow actual focus acquisition", port.actualFocusSeen)
        main { browser.pause() }
        delayed.complete(Unit)
        SystemClock.sleep(650)
        main { assertFalse(browser.isPlaying); assertFalse(browser.playWhenReady) }
        assertTrue(port.intervals.isEmpty())
        assertEquals(0, port.endedCount)
    }

    @Test fun missingProductGrantFailsBeforeLoadingAnAssetOrRequestingOutput() = withBrowser { browser, port, _ ->
        port.grantEnabled = false
        main { browser.play() }
        SystemClock.sleep(650)
        main { assertFalse(browser.isPlaying); assertFalse(browser.playWhenReady) }
        assertEquals(0, port.loads)
        assertEquals(0, port.outputChecks)
        assertFalse(port.actualFocusSeen)
    }

    @Test fun revokedOutputGateStopsWithoutAutomaticRestart() = withBrowser { browser, port, _ ->
        main { browser.play() }
        await { browser.isPlaying }
        port.allowed = false
        await { !browser.isPlaying }
        port.allowed = true
        SystemClock.sleep(600)
        main { assertFalse(browser.isPlaying); assertFalse(browser.playWhenReady) }
        assertEquals(0, port.endedCount)
    }

    @Test fun actualFocusLossPausesAndFocusGainDoesNotResume() = withBrowser { browser, _, _ ->
        main { browser.play() }
        await { browser.isPlaying }
        val manager = context.getSystemService(AudioManager::class.java)
        val competing = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({}, Handler(Looper.getMainLooper())).build()
        try {
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(competing))
            await { !browser.isPlaying }
        } finally { manager.abandonAudioFocusRequest(competing) }
        SystemClock.sleep(600)
        main { assertFalse(browser.isPlaying); assertFalse(browser.playWhenReady) }
    }
    @Test fun finishingTheActivityKeepsAllowedMediaInItsService() = withBrowser { browser, port, activity ->
        main { browser.play() }
        await { browser.isPlaying }
        await { port.intervals.isNotEmpty() }
        val before = port.intervals.size
        main { activity.finish() }
        await { port.intervals.size > before }
        main { assertTrue(browser.isPlaying) }
    }

}
