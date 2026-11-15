package io.github.driveenglishlab.pleos.domain

import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic reducer boundaries; not evidence of vehicle power or Gleo approval. */
class ProductSessionBoundaryTest {
    private val owner = OwnerContext(UUID.randomUUID(), 0, 1, UUID.randomUUID())
    private val content = ContentRef(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ListeningMode.ORIGINAL_FULL)
    private val work = ListeningWorkRef(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), content,
        ListeningStage.MAIN, UUID.randomUUID(), 60_000, UUID.randomUUID(), 1)
    private val key get() = NewsPlaybackKey(owner, work.selectionId, work.checkpointId, content,
        content.listeningScriptId, requireNotNull(work.assetId), 60_000, work.runId, "main")
    private fun access() = AccessState(SafetyState(VehiclePosition.PARKED, true, true, true), WindowState(true, true),
        PowerAvailability.ALLOWED, true, true, true, true, true).withSyntheticFeatureRoutes()
    private suspend fun ready() = SessionCoordinator().also { c ->
        c.activate(owner, null); c.updateAccess(access()); c.setMediaConnected(true)
        val ticket = requireNotNull(c.beginOperation(SessionOperationKind.SELECTION, owner))
        assertTrue(c.admitListening(RepositoryCompletion(ticket, work), 0, true))
    }

    @Test fun navigationKeepsWorkAndIntentButLateSelectionDoesNotStealTheScreen() = runBlocking {
        val c = ready(); c.requestMediaIntent(owner, PlaybackIntent.PLAY)
        val ticket = requireNotNull(c.beginOperation(SessionOperationKind.SELECTION, owner))
        assertTrue(c.navigate(owner, SessionScreen.SETTINGS))
        val next = work.copy(selectionId = UUID.randomUUID(), checkpointId = UUID.randomUUID(), runId = null)
        assertTrue(c.admitListening(RepositoryCompletion(ticket, next), 0, true))
        assertEquals(SessionScreen.SETTINGS, c.snapshot().screen)
        assertEquals(next, c.snapshot().listening)
        assertEquals(PlaybackIntent.PLAY, c.snapshot().intent)
        assertNull(c.beginOperation(SessionOperationKind.READER, owner.copy(revision = 2)))
    }

    @Test fun ownerReplacementRejectsOldCompletionAndMediaWithoutRevivingAnIntent() = runBlocking {
        val c = ready(); val ticket = requireNotNull(c.beginOperation(SessionOperationKind.SELECTION, owner))
        c.activate(owner.copy(profileId = UUID.randomUUID(), revision = 2), null)
        assertFalse(c.admitListening(RepositoryCompletion(ticket, work), 0, true))
        assertFalse(c.mediaPermission(key)); assertFalse(c.observeMedia(key, 100, true, true, "none"))
        assertNull(c.snapshot().listening); assertEquals(PlaybackIntent.STOPPED, c.snapshot().intent)
    }

    @Test fun closingTheWindowDoesNotStopAllowedBackgroundMediaButActualSuspendClosesPlaybackPermission() = runBlocking {
        val c = ready(); c.requestMediaIntent(owner, PlaybackIntent.PLAY)
        c.updateAccess(access().copy(window = WindowState(false, false)))
        assertFalse(c.snapshot().access.detailsOpen); assertTrue(c.mediaPermission(key))
        c.updateAccess(access().copy(power = PowerAvailability.SUSPENDED))
        assertFalse(c.mediaPermission(key)); assertFalse(c.requestMediaIntent(owner, PlaybackIntent.PLAY))
        assertEquals(SessionScreen.RESTRICTED, c.snapshot().screen)
    }

    @Test fun transitionFreezePreservesIntentButExplicitPauseWinsOverArticleFollowup() = runBlocking {
        val c = ready(); c.requestMediaIntent(owner, PlaybackIntent.PLAY)
        val generation = c.snapshot().intentGeneration
        assertTrue(c.observeMedia(key, 400, false, false, "transition",generation))
        assertEquals(PlaybackIntent.PLAY, c.snapshot().intent); assertEquals(generation, c.snapshot().intentGeneration)
        val ticket = requireNotNull(c.beginOperation(SessionOperationKind.SELECTION, owner))
        val followup = work.copy(stage = ListeningStage.FOLLOWUP, assetId = UUID.randomUUID())
        assertTrue(c.admitListening(RepositoryCompletion(ticket, followup), 0, true))
        assertFalse(c.observeMedia(key, 60_000, false, false, "user",generation))
        assertEquals(PlaybackIntent.USER_PAUSED, c.snapshot().intent)
        assertTrue(c.snapshot().intentGeneration > generation)
    }
}
