package io.github.driveenglishlab.pleos.platform

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.power.CarPowerManager
import android.car.hardware.power.CarPowerPolicy
import android.car.hardware.power.CarPowerPolicyFilter
import android.car.hardware.power.PowerComponent
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import androidx.annotation.RequiresApi
import android.os.Looper
import io.github.driveenglishlab.pleos.domain.PowerAvailability
import io.github.driveenglishlab.pleos.domain.SafetyState
import io.github.driveenglishlab.pleos.domain.VehiclePosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Public car API candidate. Power component policy is NOT a Suspend-state signal. */
@RequiresApi(34)
@Suppress("DEPRECATION") // Public API34 callbacks are the target AVD compatibility path.
class CarAccessMonitor(private val context: Context) : AutoCloseable {
    data class Snapshot(val safety: SafetyState = SafetyState(),
                        val componentPolicy: PowerAvailability = PowerAvailability.UNKNOWN,
                        val status: String = "not_started")
    private val mutable = MutableStateFlow(Snapshot())
    val state = mutable.asStateFlow()
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var restrictions: CarUxRestrictionsManager? = null
    private var power: CarPowerManager? = null
    private var gear: Int? = null
    private var speed: Float? = null
    private var ux: CarUxRestrictions? = null
    private var closed = false

    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (closed) return
            if (value.status != CarPropertyValue.STATUS_AVAILABLE) {
                onErrorEvent(value.propertyId, value.areaId); return
            }
            when (value.propertyId) {
                VehiclePropertyIds.GEAR_SELECTION -> gear = value.value as? Int
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> speed = (value.value as? Float)?.takeIf { it.isFinite() }
            }
            project()
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            if (closed) return
            if (propertyId == VehiclePropertyIds.GEAR_SELECTION) gear = null
            if (propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) speed = null
            project()
        }
    }
    private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener {
        if (!closed) { ux = it; project() }
    }
    private val powerListener = CarPowerManager.CarPowerPolicyListener { if (!closed) updatePolicy(it) }

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(car == null && !closed)
        if (Build.VERSION.SDK_INT < 34 || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            invalidate("unsupported"); return
        }
        val required = listOf("android.car.permission.CAR_POWERTRAIN", "android.car.permission.CAR_SPEED")
        if (required.any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            invalidate("permission_required"); return
        }
        mutable.value = mutable.value.copy(status = "connecting")
        try {
            car = Car.createCar(context, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (closed) return@createCar
                if (!ready) { close(); invalidate("disconnected"); connected.disconnect(); return@createCar }
                try {
                    properties = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                    restrictions = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                    val manager = requireNotNull(properties)
                    check(manager.registerCallback(propertyCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE))
                    check(manager.registerCallback(propertyCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_ONCHANGE))
                    gear = manager.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, 0).takeIf { it.status == CarPropertyValue.STATUS_AVAILABLE }?.value
                    speed = manager.getProperty<Float>(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0).takeIf { it.status == CarPropertyValue.STATUS_AVAILABLE }?.value?.takeIf { it.isFinite() }
                    ux = restrictions?.currentCarUxRestrictions
                    restrictions?.registerListener(uxListener)
                    mutable.value = mutable.value.copy(status = "connected")
                    if (context.checkSelfPermission("android.car.permission.READ_CAR_POWER_POLICY") == PackageManager.PERMISSION_GRANTED) {
                        power = connected.getCarManager(Car.POWER_SERVICE) as CarPowerManager
                        power?.addPowerPolicyListener(context.mainExecutor,
                            CarPowerPolicyFilter.Builder().setComponents(PowerComponent.AUDIO, PowerComponent.MEDIA, PowerComponent.MICROPHONE).build(), powerListener)
                        power?.currentPowerPolicy?.let(::updatePolicy)
                    }
                    project()
                } catch (_: Exception) { close(); invalidate("unavailable"); connected.disconnect() }
            }
        } catch (_: Exception) { invalidate("unavailable") }
    }

    private fun updatePolicy(policy: CarPowerPolicy) {
        val allowed = listOf(PowerComponent.AUDIO, PowerComponent.MEDIA, PowerComponent.MICROPHONE).all { policy.isComponentEnabled(it) }
        mutable.value = mutable.value.copy(componentPolicy = if (allowed) PowerAvailability.ALLOWED else PowerAvailability.DISALLOWED)
    }

    private fun project() {
        val position = when {
            gear == VehicleGear.GEAR_PARK && speed == 0f -> VehiclePosition.PARKED
            gear == VehicleGear.GEAR_PARK -> VehiclePosition.UNKNOWN // Conflicting/missing speed is not proof of park.
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

    private fun invalidate(status: String) {
        gear = null; speed = null; ux = null
        mutable.value = Snapshot(SafetyState(generation = mutable.value.safety.generation + 1), status = status)
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        closed = true
        runCatching { properties?.unregisterCallback(propertyCallback) }
        runCatching { restrictions?.unregisterListener() }
        runCatching { power?.removePowerPolicyListener(powerListener) }
        car?.disconnect(); car = null
        invalidate("closed")
    }
}
