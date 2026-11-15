package io.github.driveenglishlab.pleos.platform

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import io.github.driveenglishlab.pleos.domain.AccessState
import io.github.driveenglishlab.pleos.domain.VehiclePosition
import io.github.driveenglishlab.pleos.domain.ComponentPolicies
import io.github.driveenglishlab.pleos.domain.PowerAvailability
import android.car.hardware.power.PowerComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@SdkSuppress(minSdkVersion = 34)
@RunWith(AndroidJUnit4::class)
class CarAccessMonitorTest {
    @Test fun readActualCarOrKeepUnavailableWithoutGrantingInput() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var monitor: CarAccessMonitor
        instrumentation.runOnMainSync {
            monitor = CarAccessMonitor(instrumentation.targetContext)
            monitor.start()
        }
        try {
            val snapshot = withTimeout(10_000) { monitor.state.first { it.status !in setOf("not_started", "connecting") } }
            Log.i("CarAccessReadiness", "status=${snapshot.status}, position=${snapshot.safety.position}, uxKnown=${snapshot.safety.uxKnown}, details=${snapshot.safety.detailsAllowed}, components=${snapshot.components}, source=${snapshot.diagnostics}")
            if (snapshot.status != "connected") {
                assertEquals(VehiclePosition.UNKNOWN, snapshot.safety.position)
                assertFalse(snapshot.safety.detailsAllowed)
            }
            if (snapshot.safety.position != VehiclePosition.PARKED) assertFalse(AccessState(safety = snapshot.safety).detailsOpen)
            assertFalse(AccessState(safety = snapshot.safety).canCapture)
        } finally {
            instrumentation.runOnMainSync { monitor.close() }
        }
        assertEquals(VehiclePosition.UNKNOWN, monitor.state.value.safety.position)
        assertFalse(monitor.state.value.safety.detailsAllowed)
        assertFalse(monitor.state.value.components.audio.known)
        assertFalse(monitor.state.value.components.media.known)
        assertFalse(monitor.state.value.components.microphone.known)
    }

    @Test fun partialPolicyDoesNotTurnMissingMicrophoneIntoDeniedOrLoseAudio() {
        val values = CarAccessMonitor.projectComponents(intArrayOf(PowerComponent.AUDIO), intArrayOf(PowerComponent.MEDIA), 7)
        assertTrue(values.audio.known); assertTrue(values.audio.allowed); assertEquals(7L, values.audio.sourceGeneration)
        assertTrue(values.media.known); assertFalse(values.media.allowed); assertEquals(7L, values.media.sourceGeneration)
        assertFalse(values.microphone.known); assertEquals(-1L, values.microphone.sourceGeneration)
    }

    @Test fun conflictingMembershipIsUnknownOnlyForThatComponent() {
        val values = CarAccessMonitor.projectComponents(intArrayOf(PowerComponent.AUDIO, PowerComponent.MEDIA),
            intArrayOf(PowerComponent.AUDIO, PowerComponent.MICROPHONE), 8)
        assertFalse(values.audio.known)
        assertTrue(values.media.known); assertTrue(values.media.allowed)
        assertTrue(values.microphone.known); assertFalse(values.microphone.allowed)
    }

    @Test fun newPartialPolicyDoesNotCarryOldComponentAuthorization() {
        val old = CarAccessMonitor.projectComponents(intArrayOf(PowerComponent.AUDIO, PowerComponent.MEDIA, PowerComponent.MICROPHONE), intArrayOf(), 9)
        val current = CarAccessMonitor.projectComponents(intArrayOf(PowerComponent.MEDIA), intArrayOf(), 10)
        assertTrue(old.microphone.known); assertFalse(current.microphone.known); assertFalse(current.audio.known)
        assertEquals(10L, current.media.sourceGeneration)
        val reset = ComponentPolicies(generation = 11)
        assertFalse(reset.audio.known); assertFalse(reset.media.known); assertFalse(reset.microphone.known)
    }

    @Test fun componentAvailabilityNeverSynthesizesActualAwake() {
        val values = CarAccessMonitor.projectComponents(intArrayOf(PowerComponent.AUDIO, PowerComponent.MEDIA, PowerComponent.MICROPHONE), intArrayOf(), 12)
        val snapshot = VehicleSnapshot(components = values)
        assertEquals(VehiclePowerState.UNKNOWN, snapshot.actualPower)
        assertEquals(PowerAvailability.UNKNOWN, snapshot.effectivePower)
        assertEquals(PowerAvailability.UNKNOWN, snapshot.componentPolicy)
    }

    @Test fun microphoneOnlyChangeOrRepeatedReadPreservesAudioAndMediaPermissionGeneration() {
        val first = CarAccessMonitor.advanceComponents(ComponentPolicies(), CarAccessMonitor.projectComponents(
            intArrayOf(PowerComponent.AUDIO, PowerComponent.MEDIA, PowerComponent.MICROPHONE), intArrayOf(), 1))
        val changed = CarAccessMonitor.advanceComponents(first, CarAccessMonitor.projectComponents(
            intArrayOf(PowerComponent.AUDIO, PowerComponent.MEDIA), intArrayOf(PowerComponent.MICROPHONE), 2))
        assertEquals(first.audio.permissionGeneration, changed.audio.permissionGeneration)
        assertEquals(first.media.permissionGeneration, changed.media.permissionGeneration)
        assertEquals(first.microphone.permissionGeneration + 1, changed.microphone.permissionGeneration)
        assertEquals(2L, changed.audio.sourceGeneration)
        val repeated = CarAccessMonitor.advanceComponents(changed, CarAccessMonitor.projectComponents(
            intArrayOf(PowerComponent.AUDIO, PowerComponent.MEDIA), intArrayOf(PowerComponent.MICROPHONE), 3))
        assertEquals(changed.audio.permissionGeneration, repeated.audio.permissionGeneration)
        assertEquals(changed.media.permissionGeneration, repeated.media.permissionGeneration)
        assertEquals(changed.microphone.permissionGeneration, repeated.microphone.permissionGeneration)
        assertEquals(3L, repeated.audio.sourceGeneration)
    }

    @Test fun sourceResetAndUnknownRecoveryCannotReviveOldPermissionGeneration() {
        val first = CarAccessMonitor.advanceComponents(ComponentPolicies(), CarAccessMonitor.projectComponents(
            intArrayOf(PowerComponent.AUDIO), intArrayOf(), 1))
        val reset = CarAccessMonitor.advanceComponents(first, ComponentPolicies(generation = 2), sourceReset = true)
        assertEquals(first.audio.permissionGeneration + 1, reset.audio.permissionGeneration)
        assertEquals(first.microphone.permissionGeneration + 1, reset.microphone.permissionGeneration)
        assertFalse(reset.audio.known)
        val restored = CarAccessMonitor.advanceComponents(reset, CarAccessMonitor.projectComponents(
            intArrayOf(PowerComponent.AUDIO), intArrayOf(), 3))
        assertEquals(first.audio.permissionGeneration + 2, restored.audio.permissionGeneration)
        assertTrue(restored.audio.known); assertTrue(restored.audio.allowed)
        assertEquals(reset.media.permissionGeneration, restored.media.permissionGeneration)
    }
}
