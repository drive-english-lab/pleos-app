package io.github.driveenglishlab.pleos.platform.media

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real ExoPlayer command adapter checks; this does not simulate vehicle power/Suspend. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class OutputPolicyPlayerTest {
    @Test fun disallowBlocksLaterPlayAndPrepareAndReallowDoesNotResume() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val exo = ExoPlayer.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build()
            var stops = 0
            val adapter = OutputPolicyPlayer(exo) { stops++; exo.pause(); exo.stop() }
            try {
                exo.play()
                adapter.updateOutputAllowed(false)
                assertEquals(1, stops)
                assertFalse(exo.playWhenReady)
                assertFalse(adapter.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
                assertFalse(adapter.isCommandAvailable(Player.COMMAND_PREPARE))
                adapter.play(); adapter.prepare()
                assertFalse(exo.playWhenReady)
                assertEquals(Player.STATE_IDLE, exo.playbackState)
                adapter.updateOutputAllowed(true)
                assertTrue(adapter.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
                assertFalse(exo.playWhenReady)
                assertEquals(Player.STATE_IDLE, exo.playbackState)
            } finally { adapter.release() }
        }
    }
    @Test fun stopInvokesNormalizationExactlyOnce() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val exo = ExoPlayer.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build()
            var stops = 0
            val adapter = OutputPolicyPlayer(exo) { stops++; exo.pause(); exo.stop() }
            try {
                exo.play(); adapter.stop()
                assertEquals(1, stops)
                assertFalse(exo.playWhenReady)
                assertEquals(Player.STATE_IDLE, exo.playbackState)
            } finally { adapter.release() }
        }
    }
}
