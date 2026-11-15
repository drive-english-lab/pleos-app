package io.github.driveenglishlab.pleos

import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.domain.ComponentAccess
import io.github.driveenglishlab.pleos.domain.PowerAvailability
import io.github.driveenglishlab.pleos.domain.VehiclePosition
import io.github.driveenglishlab.pleos.platform.PublicVehicleStateSource
import io.github.driveenglishlab.pleos.platform.CarAccessMonitor
import io.github.driveenglishlab.pleos.platform.VehiclePowerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in, actual target-app UID/SDK35 public reads. No vehicle or permission writes. */
@SdkSuppress(minSdkVersion = 34)
@RunWith(AndroidJUnit4::class)
class VehicleSignalDiagnosticTest {
    @Test fun observeActualTarget35VehicleChannelsWithoutManufacturingPowerOrPark() = runBlocking {
        assumeTrue("Read-only device probe requires explicit vehicle_signal_probe=true",
            InstrumentationRegistry.getArguments().getString("vehicle_signal_probe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Probe must use the product target35 app, not a legacy target28 library", context.applicationInfo.targetSdkVersion >= 35)
        val permissionNames = listOf("android.car.permission.CAR_POWERTRAIN", "android.car.permission.CAR_SPEED",
            "android.car.permission.READ_CAR_POWER_POLICY", "android.permission.RECORD_AUDIO")
        val permissionState = permissionNames.joinToString(",") { name ->
            "$name=${context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED}"
        }
        Log.i("VehicleSignalDiagnostic", "package=${context.packageName}, target=${context.applicationInfo.targetSdkVersion}, sdk=${Build.VERSION.SDK_INT}, automotive=${context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)}, permissions=$permissionState")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var source: PublicVehicleStateSource
        lateinit var originalMonitor: CarAccessMonitor
        instrumentation.runOnMainSync {
            source = PublicVehicleStateSource(context, scope); source.start()
            // Own app state only, never SDK private methods or a new native monitor.
            originalMonitor = PublicVehicleStateSource::class.java.getDeclaredField("monitor").apply { isAccessible = true }.get(source) as CarAccessMonitor
        }
        Log.i("VehicleSignalDiagnostic", "main_start_returned=true, calls=public_worker_lanes, activity_window=not_observed")
        try {
            val settled = withTimeoutOrNull(10_000) {
                source.state.first {
                    val d = it.diagnostics
                    val pending = setOf("not_started", "connecting", "initializing", "connected")
                    it.status !in setOf("not_started", "connecting", "connected") || it.status == "connected" &&
                        listOf(d.gear, d.speed, d.ux, d.components).none { value -> value in pending }
                }
            }
            val observed = settled ?: source.state.value
            fun component(value: ComponentAccess): String = "known=${value.known}/allowed=${value.allowed}/sourceGeneration=${value.sourceGeneration}/permissionGeneration=${value.permissionGeneration}"
            Log.i("VehicleSignalDiagnostic", "settled=${settled != null}, status=${observed.status}, source=${observed.diagnostics.source}, sourceGeneration=${observed.diagnostics.sourceGeneration}, position=${observed.safety.position}, uxKnown=${observed.safety.uxKnown}, uxDetails=${observed.safety.detailsAllowed}, uxInput=${observed.safety.directInputAllowed}, safetyGeneration=${observed.safety.generation}, channels=${observed.diagnostics}, policyGeneration=${observed.components.generation}, audio=${component(observed.components.audio)}, media=${component(observed.components.media)}, microphone=${component(observed.components.microphone)}, actualPower=${observed.actualPower}")
            // This probe has no public raw-Suspend source; components/lifecycle cannot replace it.
            assertEquals(VehiclePowerState.UNKNOWN, observed.actualPower)
            assertEquals(PowerAvailability.UNKNOWN, observed.effectivePower)
            for (value in listOf(observed.components.audio, observed.components.media, observed.components.microphone)) {
                if (value.known) assertEquals(observed.components.generation, value.sourceGeneration)
            }
            if (observed.status != "connected") {
                assertEquals(VehiclePosition.UNKNOWN, observed.safety.position)
                assertFalse(observed.safety.uxKnown)
            }
            // A passing fail-closed observation is not successful P/UXR/media/vehicle execution.
        } finally {
            instrumentation.runOnMainSync {
                source.close()
                assertEquals("closed", source.state.value.status)
                assertFalse(source.state.value.components.audio.known)
                assertEquals("closed", originalMonitor.state.value.status)
                Log.i("VehicleSignalDiagnostic", "logical_close_returned=true, state=${source.state.value.status}, cleanup=${originalMonitor.state.value.diagnostics.cleanup}")
            }
            val cleaned = withTimeoutOrNull(10_000) {
                originalMonitor.state.first { it.diagnostics.cleanup.contains("disconnect=SUCCEEDED") ||
                    it.diagnostics.cleanup.contains("disconnect=FAILED") || it.diagnostics.cleanup.contains("UNCONFIRMED") }
            }
            Log.i("VehicleSignalDiagnostic", "cleanup_observed=${cleaned != null}, original_cleanup=${originalMonitor.state.value.diagnostics.cleanup}, raw_power=${source.state.value.actualPower}")
            scope.cancel()
        }
    }
}
