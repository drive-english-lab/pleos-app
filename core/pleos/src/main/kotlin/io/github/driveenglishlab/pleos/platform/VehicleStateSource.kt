package io.github.driveenglishlab.pleos.platform

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Looper
import android.view.View
import io.github.driveenglishlab.pleos.domain.PowerAvailability
import io.github.driveenglishlab.pleos.domain.ComponentPolicies
import io.github.driveenglishlab.pleos.domain.ComponentAccess
import io.github.driveenglishlab.pleos.domain.SafetyState
import io.github.driveenglishlab.pleos.domain.WindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class VehiclePowerState { UNKNOWN, AWAKE, SUSPENDING, SUSPENDED }
data class VehicleSnapshot(
    val safety: SafetyState = SafetyState(),
    val componentPolicy: PowerAvailability = PowerAvailability.UNKNOWN,
    val actualPower: VehiclePowerState = VehiclePowerState.UNKNOWN,
    val status: String = "not_started",
    val components: ComponentPolicies = ComponentPolicies(),
    val diagnostics: VehicleSignalDiagnostics = VehicleSignalDiagnostics(),
) {
    val effectivePower: PowerAvailability get() = when (actualPower) {
        VehiclePowerState.UNKNOWN -> PowerAvailability.UNKNOWN
        VehiclePowerState.SUSPENDING -> PowerAvailability.SUSPENDING
        VehiclePowerState.SUSPENDED -> PowerAvailability.SUSPENDED
        // This is raw observed power only, never the aggregate component policy.
        VehiclePowerState.AWAKE -> PowerAvailability.ALLOWED
    }
}

interface VehicleStateSource : AutoCloseable {
    val state: StateFlow<VehicleSnapshot>
    fun start()
}

/** API34 car reads are isolated; no interactive/window signal masquerades as Suspend. */
class PublicVehicleStateSource(private val context: Context, private val scope: CoroutineScope) : VehicleStateSource {
    private val mutable = MutableStateFlow(VehicleSnapshot())
    override val state = mutable.asStateFlow()
    private var monitor: CarAccessMonitor? = null
    private var collection: Job? = null
    private var started = false

    override fun start() {
        check(Looper.myLooper() == Looper.getMainLooper()); check(!started); started = true
        if (Build.VERSION.SDK_INT < 34) { mutable.value = VehicleSnapshot(status = "unsupported_api"); return }
        val current = CarAccessMonitor(context)
        monitor = current
        collection = scope.launch {
            current.state.collect { value ->
                mutable.value = VehicleSnapshot(safety = value.safety, actualPower = VehiclePowerState.UNKNOWN,
                    status = value.status, components = value.components, diagnostics = value.diagnostics)
            }
        }
        current.start()
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        collection?.cancel(); collection = null
        if (Build.VERSION.SDK_INT >= 34) monitor?.close()
        monitor = null
        val previous = mutable.value
        fun reset(value: ComponentAccess) = ComponentAccess(permissionGeneration = value.permissionGeneration + 1)
        mutable.value = VehicleSnapshot(safety = SafetyState(generation = previous.safety.generation + 1), status = "closed",
            components = ComponentPolicies(generation = previous.components.generation + 1,
                audio = reset(previous.components.audio), media = reset(previous.components.media), microphone = reset(previous.components.microphone)),
            diagnostics = VehicleSignalDiagnostics(sourceGeneration = previous.diagnostics.sourceGeneration + 1,
                gear = "closed", speed = "closed", ux = "closed", components = "closed"))
    }
}

/** Refresh from actual lifecycle/window callbacks; a true observation never starts input. */
class WindowAccessSource(private val activity: Activity) {
    private val mutable = MutableStateFlow(WindowState())
    val state = mutable.asStateFlow()
    fun refresh() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val decor = activity.window?.decorView
        val visible = !activity.isFinishing && !activity.isDestroyed && decor?.isShown == true && decor.windowVisibility == View.VISIBLE
        val old = mutable.value
        val next = WindowState(visible, visible && activity.hasWindowFocus(), old.generation)
        if (next != old) mutable.value = next.copy(generation = old.generation + 1)
    }
    fun closed() { mutable.value = WindowState(generation = mutable.value.generation + 1) }
}
