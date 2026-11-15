package io.github.driveenglishlab.pleos.platform

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.power.CarPowerManager
import android.car.hardware.power.CarPowerPolicyFilter
import android.car.hardware.power.PowerComponent
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import io.github.driveenglishlab.pleos.domain.ComponentAccess
import io.github.driveenglishlab.pleos.domain.ComponentPolicies
import io.github.driveenglishlab.pleos.domain.PowerAvailability
import io.github.driveenglishlab.pleos.domain.SafetyState
import io.github.driveenglishlab.pleos.domain.VehiclePosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Channel status is an observation, never a synthesized permission or raw power state. */
data class VehicleSignalDiagnostics(
    val source: String = "android.car/public-api34",
    val sourceGeneration: Long = 0,
    val gear: String = "not_started",
    val speed: String = "not_started",
    val ux: String = "not_started",
    val components: String = "not_started",
    val policyId: String? = null,
    val connectionFailure: String? = null,
)

/** Public car reads only. Component policy is not an actual AWAKE/Suspend-state signal. */
@RequiresApi(34)
@Suppress("DEPRECATION")
class CarAccessMonitor(private val context: Context) : AutoCloseable {
    data class Snapshot(
        val safety: SafetyState = SafetyState(),
        val components: ComponentPolicies = ComponentPolicies(),
        val status: String = "not_started",
        val diagnostics: VehicleSignalDiagnostics = VehicleSignalDiagnostics(),
    ) {
        /** Compatibility observation only. Features consume individual current components. */
        val componentPolicy: PowerAvailability get() = PowerAvailability.UNKNOWN
    }
    private val mutable = MutableStateFlow(Snapshot())
    val state = mutable.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var restrictions: CarUxRestrictionsManager? = null
    private var power: CarPowerManager? = null
    private var propertyCallback: CarPropertyManager.CarPropertyEventCallback? = null
    private var uxListener: CarUxRestrictionsManager.OnUxRestrictionsChangedListener? = null
    private var powerListener: CarPowerManager.CarPowerPolicyListener? = null
    private var gear: Int? = null
    private var speed: Float? = null
    private var ux: CarUxRestrictions? = null
    private var gearTimestamp = -1L
    private var speedTimestamp = -1L
    private var attempt = 0L
    private var sourceGeneration = 0L
    private var policyGeneration = 0L
    private var started = false
    private var closed = false

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!started && !closed); started = true
        if (Build.VERSION.SDK_INT < 34 || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            invalidate("unsupported"); return
        }
        // Missing gear/speed permission must not prevent unrelated UXR or component reads.
        invalidate("connecting")
        val thisAttempt = ++attempt
        main.postDelayed({
            if (!closed && attempt == thisAttempt && mutable.value.status == "connecting") invalidate("connection_timeout")
        }, 8_000)
        try {
            car = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (closed || attempt != thisAttempt) return@createCar
                if (!ready) {
                    releaseManagers(); invalidate("disconnected")
                    return@createCar
                }
                releaseManagers(); invalidate("connected")
                val generation = sourceGeneration
                attachProperties(connected, generation)
                attachRestrictions(connected, generation)
                attachPower(connected, generation)
                project()
            }
            if (car == null && mutable.value.status == "connecting") invalidate("create_car_unavailable")
        } catch (failure: SecurityException) {
            releaseManagers(); invalidate("car_permission_required", failure.javaClass.simpleName)
        } catch (failure: LinkageError) {
            releaseManagers(); invalidate("car_api_unavailable", failure.javaClass.simpleName)
        } catch (failure: Exception) {
            releaseManagers(); invalidate("car_connection_unavailable", failure.javaClass.simpleName)
        }
    }
    private fun current(generation: Long): Boolean = !closed && sourceGeneration == generation

    private fun attachProperties(connected: Car, generation: Long) {
        val gearPermission = context.checkSelfPermission("android.car.permission.CAR_POWERTRAIN") == PackageManager.PERMISSION_GRANTED
        val speedPermission = context.checkSelfPermission("android.car.permission.CAR_SPEED") == PackageManager.PERMISSION_GRANTED
        diagnostic(gear = if (gearPermission) "initializing" else "permission_required",
            speed = if (speedPermission) "initializing" else "permission_required")
        if (!gearPermission && !speedPermission) return
        val manager = try { connected.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager } catch (_: Exception) { null }
        if (manager == null) {
            diagnostic(gear = if (gearPermission) "manager_unavailable" else null,
                speed = if (speedPermission) "manager_unavailable" else null)
            return
        }
        properties = manager
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                if (!current(generation) || properties !== manager) return
                if (value.status != CarPropertyValue.STATUS_AVAILABLE) {
                    when (value.propertyId) {
                        VehiclePropertyIds.GEAR_SELECTION -> {
                            if (value.timestamp < gearTimestamp) return
                            gearTimestamp = maxOf(gearTimestamp, value.timestamp)
                        }
                        VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                            if (value.timestamp < speedTimestamp) return
                            speedTimestamp = maxOf(speedTimestamp, value.timestamp)
                        }
                    }
                    onErrorEvent(value.propertyId, value.areaId); return
                }
                when (value.propertyId) {
                    VehiclePropertyIds.GEAR_SELECTION -> {
                        if (!gearPermission || value.timestamp <= gearTimestamp) return
                        gearTimestamp = value.timestamp; gear = value.value as? Int
                        diagnostic(gear = if (gear == null) "invalid_value" else "available")
                    }
                    VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                        if (!speedPermission || value.timestamp <= speedTimestamp) return
                        speedTimestamp = value.timestamp; speed = (value.value as? Float)?.takeIf { it.isFinite() }
                        diagnostic(speed = if (speed == null) "invalid_value" else "available")
                    }
                }
                project()
            }
            override fun onErrorEvent(propertyId: Int, areaId: Int) {
                if (!current(generation) || properties !== manager) return
                when (propertyId) {
                    VehiclePropertyIds.GEAR_SELECTION -> { gear = null; diagnostic(gear = "read_unavailable") }
                    VehiclePropertyIds.PERF_VEHICLE_SPEED -> { speed = null; diagnostic(speed = "read_unavailable") }
                }
                project()
            }
        }
        propertyCallback = callback
        if (gearPermission) try {
            check(manager.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE))
            callback.onChangeEvent(manager.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, 0))
        } catch (_: Exception) { gear = null; diagnostic(gear = "read_unavailable") }
        if (speedPermission) try {
            check(manager.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_ONCHANGE))
            callback.onChangeEvent(manager.getProperty<Float>(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0))
        } catch (_: Exception) { speed = null; diagnostic(speed = "read_unavailable") }
    }
    private fun attachRestrictions(connected: Car, generation: Long) {
        try {
            val manager = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager ?: error("UXR unavailable")
            restrictions = manager
            val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener {
                if (current(generation) && restrictions === manager) { ux = it; diagnostic(ux = "available"); project() }
            }
            uxListener = listener
            manager.registerListener(listener)
            ux = manager.currentCarUxRestrictions
            diagnostic(ux = if (ux == null) "current_value_unavailable" else "available")
        } catch (_: SecurityException) { ux = null; diagnostic(ux = "permission_required") }
        catch (_: Exception) { ux = null; diagnostic(ux = "read_unavailable") }
    }
    private fun attachPower(connected: Car, generation: Long) {
        if (context.checkSelfPermission("android.car.permission.READ_CAR_POWER_POLICY") != PackageManager.PERMISSION_GRANTED) {
            unknownComponents("permission_required"); return
        }
        try {
            val manager = connected.getCarManager(Car.POWER_SERVICE) as? CarPowerManager ?: error("Power policy unavailable")
            power = manager
            val listener = CarPowerManager.CarPowerPolicyListener {
                if (current(generation) && power === manager) readCurrentPolicy(manager, generation)
            }
            powerListener = listener
            manager.addPowerPolicyListener(context.mainExecutor, CarPowerPolicyFilter.Builder()
                .setComponents(PowerComponent.AUDIO, PowerComponent.MEDIA, PowerComponent.MICROPHONE).build(), listener)
            readCurrentPolicy(manager, generation)
        } catch (_: SecurityException) { unknownComponents("permission_required") }
        catch (_: Exception) { unknownComponents("read_unavailable") }
    }
    private fun readCurrentPolicy(manager: CarPowerManager, generation: Long) {
        if (!current(generation) || power !== manager) return
        try {
            // Listener policies may be partial. Re-read current membership rather than
            // accumulating old bits or interpreting isComponentEnabled(false) as denial.
            val policy = manager.currentPowerPolicy ?: run { unknownComponents("current_value_unavailable"); return }
            val values = advanceComponents(mutable.value.components,
                projectComponents(policy.enabledComponents, policy.disabledComponents, ++policyGeneration))
            mutable.value = mutable.value.copy(components = values,
                diagnostics = mutable.value.diagnostics.copy(components = "current_policy", policyId = policy.policyId))
        } catch (_: SecurityException) { unknownComponents("permission_required") }
        catch (_: Exception) { unknownComponents("read_unavailable") }
    }
    private fun unknownComponents(status: String) {
        mutable.value = mutable.value.copy(components = advanceComponents(mutable.value.components,
            ComponentPolicies(generation = ++policyGeneration)),
            diagnostics = mutable.value.diagnostics.copy(components = status, policyId = null))
    }
    private fun diagnostic(gear: String? = null, speed: String? = null, ux: String? = null) {
        val old = mutable.value.diagnostics
        mutable.value = mutable.value.copy(diagnostics = old.copy(gear = gear ?: old.gear, speed = speed ?: old.speed, ux = ux ?: old.ux))
    }
    private fun project() {
        val position = when {
            gear == VehicleGear.GEAR_PARK && speed == 0f -> VehiclePosition.PARKED
            gear == VehicleGear.GEAR_PARK -> VehiclePosition.UNKNOWN
            gear == VehicleGear.GEAR_DRIVE && speed == 0f -> VehiclePosition.DRIVE_STOPPED
            gear in setOf(VehicleGear.GEAR_REVERSE, VehicleGear.GEAR_NEUTRAL) -> VehiclePosition.MOVING_OR_REVERSE_NEUTRAL
            gear == VehicleGear.GEAR_DRIVE && speed != null -> VehiclePosition.MOVING_OR_REVERSE_NEUTRAL
            else -> VehiclePosition.UNKNOWN
        }
        val permitted = ux?.let { !it.isRequiresDistractionOptimization && it.activeRestrictions == 0 } == true
        val previous = mutable.value.safety
        val next = SafetyState(position, ux != null, permitted, permitted, generation = previous.generation)
        if (next != previous) mutable.value = mutable.value.copy(safety = next.copy(generation = previous.generation + 1))
    }
    private fun invalidate(status: String, failure: String? = null) {
        gear = null; speed = null; ux = null; gearTimestamp = -1; speedTimestamp = -1
        sourceGeneration++
        mutable.value = Snapshot(SafetyState(generation = mutable.value.safety.generation + 1),
            advanceComponents(mutable.value.components, ComponentPolicies(generation = ++policyGeneration), sourceReset = true), status,
            VehicleSignalDiagnostics(sourceGeneration = sourceGeneration, gear = status, speed = status, ux = status,
                components = status, connectionFailure = failure))
    }
    private fun releaseManagers() {
        propertyCallback?.let { callback -> runCatching { properties?.unregisterCallback(callback) } }
        runCatching { restrictions?.unregisterListener() }
        powerListener?.let { listener -> runCatching { power?.removePowerPolicyListener(listener) } }
        properties = null; restrictions = null; power = null
        propertyCallback = null; uxListener = null; powerListener = null
    }
    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        closed = true; attempt++
        releaseManagers()
        runCatching { car?.disconnect() }; car = null
        invalidate("closed")
    }
    companion object {
        /** Policy reads refresh source identity. Only a relevant disposition/reset expires
         * that component's grants; an unrelated MICROPHONE change must not expire AUDIO.
         */
        internal fun advanceComponents(previous: ComponentPolicies, observed: ComponentPolicies,
            sourceReset: Boolean = false): ComponentPolicies {
            fun advance(old: ComponentAccess, next: ComponentAccess): ComponentAccess {
                val oldKnown = old.known && old.sourceGeneration == previous.generation
                val nextKnown = next.known && next.sourceGeneration == observed.generation
                val changed = sourceReset || oldKnown != nextKnown || nextKnown && old.allowed != next.allowed
                return next.copy(known = nextKnown, allowed = nextKnown && next.allowed,
                    sourceGeneration = if (nextKnown) observed.generation else -1,
                    permissionGeneration = old.permissionGeneration + if (changed) 1 else 0)
            }
            return observed.copy(audio = advance(previous.audio, observed.audio),
                media = advance(previous.media, observed.media), microphone = advance(previous.microphone, observed.microphone))
        }

        /** Exact current membership only. Conflicting or absent entries have no known value. */
        internal fun projectComponents(enabled: IntArray, disabled: IntArray, generation: Long): ComponentPolicies {
            require(generation >= 0)
            val on = enabled.toSet(); val off = disabled.toSet()
            fun value(component: Int): ComponentAccess = when {
                component in on && component !in off -> ComponentAccess(true, true, generation)
                component in off && component !in on -> ComponentAccess(true, false, generation)
                else -> ComponentAccess()
            }
            return ComponentPolicies(generation, value(PowerComponent.AUDIO), value(PowerComponent.MEDIA), value(PowerComponent.MICROPHONE))
        }
    }
}
