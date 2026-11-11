package io.github.driveenglishlab.pleos.platform

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import io.github.driveenglishlab.pleos.domain.AccessState
import io.github.driveenglishlab.pleos.domain.VehiclePosition
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
            val snapshot = withTimeout(8_000) { monitor.state.first { it.status !in setOf("not_started", "connecting") } }
            Log.i("CarAccessReadiness", "status=${snapshot.status}, position=${snapshot.safety.position}, uxKnown=${snapshot.safety.uxKnown}, details=${snapshot.safety.detailsAllowed}, componentPolicy=${snapshot.componentPolicy}")
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
    }
}
