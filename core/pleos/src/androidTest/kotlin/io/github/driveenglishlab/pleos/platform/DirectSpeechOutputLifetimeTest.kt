package io.github.driveenglishlab.pleos.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic callbacks at the actual Gateway handler/state boundary. Fixtures use private app
 * state only: approval stays null, no initialize/acquireFocus/speak/STT/TTS/provider is called,
 * and no product identity, vehicle permission, DI, clock or successful learning is created. */
@RunWith(AndroidJUnit4::class)
class DirectSpeechOutputLifetimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun owner() = OwnerContext(UUID.randomUUID(), 0, 1, UUID.randomUUID())
    private fun get(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun set(target: Any, name: String, value: Any?) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    private fun error(gateway: DirectSpeechGateway, id: String?) = gateway.javaClass
        .getDeclaredMethod("postOutputError", String::class.java).apply { isAccessible = true }.invoke(gateway, id)
    private fun done(gateway: DirectSpeechGateway, id: UUID) = gateway.javaClass
        .getDeclaredMethod("postOutputDone", String::class.java, String::class.java).apply { isAccessible = true }
        .invoke(gateway, id.toString(), "synthetic-output-observation")
    private fun access(permission: Long = 7) = AccessState(
        safety = SafetyState(VehiclePosition.PARKED, uxKnown = true, detailsAllowed = true, directInputAllowed = true),
        window = WindowState(visible = true, focused = true), ownerValid = true, power = PowerAvailability.UNKNOWN,
        components = ComponentPolicies(generation = 3, audio = ComponentAccess(true, true, 3, permission)))

    private class Fixture(val gateway: DirectSpeechGateway, val events: MutableList<DirectSpeechEvent>,
        val owner: OwnerContext, val generation: Long)
    private fun fixture(): Fixture {
        val events = mutableListOf<DirectSpeechEvent>()
        val gateway = DirectSpeechGateway(instrumentation.targetContext, approval = null) { events.add(it) }
        val owner = owner(); val generation = 41L
        instrumentation.runOnMainSync {
            // Model a previously accepted output/focus without constructing any engine or changing OS grants.
            set(gateway, "focusOwner", owner); set(gateway, "focusGeneration", generation); set(gateway, "ready", true)
            @Suppress("UNCHECKED_CAST") val readiness = get(gateway, "mutable") as MutableStateFlow<DirectSpeechReadiness>
            readiness.value = DirectSpeechReadiness(approved = true, outputReady = true, focusGranted = true)
        }
        assertNull(get(gateway, "approval")); assertNull(get(gateway, "tts")); assertNull(get(gateway, "recognizer")); assertNull(get(gateway, "focus"))
        return Fixture(gateway, events, owner, generation)
    }
    private fun output(f: Fixture, id: UUID = UUID.randomUUID(), owner: OwnerContext = f.owner,
        generation: Long = f.generation, permission: Long = 7): DirectSpeechOutputLifetime =
        DirectSpeechOutputLifetime(owner, id, generation, permission).also { set(f.gateway, "output", it) }
    private fun finish(f: Fixture) { instrumentation.runOnMainSync { f.gateway.close() }; instrumentation.waitForIdleSync() }
    private fun assertLive(f: Fixture, expected: DirectSpeechOutputLifetime) {
        assertSame(expected, get(f.gateway, "output")); assertTrue(f.gateway.speaking)
        assertTrue(f.gateway.outputAllowed(access()))
        assertFalse(f.gateway.outputAllowed(access(expected.audioPermissionGeneration + 1)))
        assertTrue(f.events.isEmpty())
    }

    @Test fun queuedOldErrorAfterReplacementPreservesBAndNormalBDoneExactlyOnce() {
        val f = fixture()
        try {
            lateinit var b: DirectSpeechOutputLifetime
            val originalReadiness = f.gateway.state.value
            instrumentation.runOnMainSync {
                val a = output(f)
                error(f.gateway, a.outputId.toString()) // Runnable waits while the same main action installs B.
                b = output(f) // Same synthetic text scenario; identity uses a new ID, never text equality.
            }
            instrumentation.waitForIdleSync()
            assertLive(f, b); assertEquals(originalReadiness, f.gateway.state.value)
            instrumentation.runOnMainSync { done(f.gateway, b.outputId); done(f.gateway, b.outputId); error(f.gateway, b.outputId.toString()) }
            instrumentation.waitForIdleSync()
            assertEquals(1, f.events.size)
            val ended = f.events.single() as DirectSpeechEvent.Ended
            assertEquals(b.owner, ended.owner); assertEquals(b.outputId, ended.outputId)
            assertFalse(f.gateway.speaking); assertNull(get(f.gateway, "output"))
        } finally { finish(f) }
    }

    @Test fun currentBErrorFailsOnlyItsOriginalIdentityAndFocusLifetimeOnce() {
        val f = fixture()
        try {
            lateinit var b: DirectSpeechOutputLifetime
            instrumentation.runOnMainSync { b = output(f); error(f.gateway, b.outputId.toString()); error(f.gateway, b.outputId.toString()); done(f.gateway, b.outputId) }
            instrumentation.waitForIdleSync()
            val failed = f.events.single() as DirectSpeechEvent.Failed
            assertEquals(b.owner, failed.owner); assertEquals(b.outputId, failed.outputId)
            assertEquals(b.focusGeneration, failed.focusGeneration); assertNull(failed.captureId)
            assertEquals("speech_output_unavailable", failed.reason)
            assertFalse(f.gateway.speaking); assertNull(get(f.gateway, "output"))
            assertTrue("Owner-null admission remains available for other legitimate failures", f.gateway.acceptsEvent(null, f.generation))
        } finally { finish(f) }
    }

    @Test fun unknownAndNullErrorsLeaveLiveOutputAndPermissionChecksUnchanged() {
        val f = fixture()
        try {
            lateinit var b: DirectSpeechOutputLifetime
            instrumentation.runOnMainSync { b = output(f); error(f.gateway, null); error(f.gateway, UUID.randomUUID().toString()); error(f.gateway, "") }
            instrumentation.waitForIdleSync(); assertLive(f, b)
        } finally { finish(f) }
    }

    @Test fun lateErrorsAfterCancelAndQueueFlushStyleReplacementNeverClearB() {
        for (cancelFirst in listOf(false, true)) {
            val f = fixture()
            try {
                lateinit var b: DirectSpeechOutputLifetime
                instrumentation.runOnMainSync {
                    val a = output(f)
                    if (cancelFirst) f.gateway.cancel() // No recognizer/TTS instance exists in this fixture.
                    b = output(f)
                    error(f.gateway, a.outputId.toString())
                }
                instrumentation.waitForIdleSync(); assertLive(f, b)
            } finally { finish(f) }
        }
    }

    @Test fun oldErrorAcrossOwnerAndFocusReplacementIsNotRetaggedToNewGeneration() {
        val f = fixture()
        try {
            lateinit var b: DirectSpeechOutputLifetime
            instrumentation.runOnMainSync {
                val a = output(f)
                error(f.gateway, a.outputId.toString())
                f.gateway.releaseFocus() // Actual Gateway cancellation boundary, no OS focus request was created.
                val nextOwner = owner(); val nextGeneration = get(f.gateway, "focusGeneration") as Long
                set(f.gateway, "focusOwner", nextOwner)
                @Suppress("UNCHECKED_CAST") val readiness = get(f.gateway, "mutable") as MutableStateFlow<DirectSpeechReadiness>
                readiness.value = readiness.value.copy(focusGranted = true)
                b = output(f, owner = nextOwner, generation = nextGeneration)
            }
            instrumentation.waitForIdleSync(); assertLive(f, b)
            instrumentation.runOnMainSync { done(f.gateway, b.outputId) }; instrumentation.waitForIdleSync()
            assertEquals(b.outputId, (f.events.single() as DirectSpeechEvent.Ended).outputId)
        } finally { finish(f) }
    }

    @Test fun sameIdWithStaleFocusOrOwnerCannotConsumeOrEmitCurrentGenerationFailure() {
        for (replaceOwner in listOf(false, true)) {
            val f = fixture()
            try {
                lateinit var original: DirectSpeechOutputLifetime
                instrumentation.runOnMainSync {
                    original = output(f)
                    if (replaceOwner) set(f.gateway, "focusOwner", owner()) else set(f.gateway, "focusGeneration", f.generation + 1)
                    error(f.gateway, original.outputId.toString()); done(f.gateway, original.outputId)
                }
                instrumentation.waitForIdleSync()
                assertSame(original, get(f.gateway, "output")); assertTrue(f.gateway.speaking)
                assertFalse(f.gateway.outputAllowed(access())); assertTrue(f.events.isEmpty())
            } finally { finish(f) }
        }
    }

    @Test fun closedGatewayRejectsOldErrorsAndDoneWithoutChangingReadiness() {
        val f = fixture()
        try {
            instrumentation.runOnMainSync {
                val a = output(f); f.gateway.close(); error(f.gateway, a.outputId.toString()); done(f.gateway, a.outputId); error(f.gateway, null)
            }
            instrumentation.waitForIdleSync()
            assertTrue(f.events.isEmpty()); assertFalse(f.gateway.speaking); assertFalse(f.gateway.outputAllowed(access()))
            assertEquals(DirectSpeechReadiness(), f.gateway.state.value); assertFalse(f.gateway.acceptsEvent(null, f.generation))
        } finally { finish(f) }
    }
}
