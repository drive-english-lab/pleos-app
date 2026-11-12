package io.github.driveenglishlab.pleos.platform.vehicle

import android.car.Car
import android.annotation.SuppressLint
import androidx.annotation.RequiresApi
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
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live facts only. Neither gearD at zero speed nor a remembered P opens details. */
data class DrivingAccess(
    val connected: Boolean = false,
    val permissionGranted: Boolean = false,
    val gear: Int? = null,
    val speed: Float? = null,
    val uxKnown: Boolean = false,
    val requiresOptimization: Boolean? = null,
    val restrictions: Int? = null,
    val componentAllowed: Boolean? = null,
    val status: String = "connecting",
) {
    val detailsAllowed: Boolean get() = connected && permissionGranted && gear == 4 && speed?.isFinite() == true && speed == 0f && uxKnown && requiresOptimization == false && restrictions == 0
    val mayRequestPermission: Boolean get() = connected && uxKnown && requiresOptimization == false && restrictions == 0 && !permissionGranted
    val label: String get() = when { detailsAllowed -> "P · 주차 중"; gear == 8 && speed == 0f -> "D · 정차 중"; gear == 8 -> "D · 주행 중"; gear == 2 -> "R · 제한 중"; gear == 1 -> "N · 제한 중"; else -> "차량 상태 확인 중" }
}

/** AAOS public API; component policy is not an actual Suspend signal. */
@Suppress("DEPRECATION")
@SuppressLint("MissingPermission") // Each read/registration is guarded by current runtime permissions.
@RequiresApi(34)
class DrivingAccessSource(private val context: Context) : AutoCloseable {
    private val mutable = MutableStateFlow(DrivingAccess())
    val state = mutable.asStateFlow()
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var restrictions: CarUxRestrictionsManager? = null
    private var power: CarPowerManager? = null
    private var gear: Int? = null
    private var speed: Float? = null
    private var ux: CarUxRestrictions? = null
    private var components: Boolean? = null
    private var connected = false
    private var observingProperties = false
    private var closed = false
    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (closed) return
            if (value.status != CarPropertyValue.STATUS_AVAILABLE) { onErrorEvent(value.propertyId, value.areaId); return }
            when (value.propertyId) {
                VehiclePropertyIds.GEAR_SELECTION -> gear = value.value as? Int
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> speed = (value.value as? Float)?.takeIf { it.isFinite() }
            }
            publish()
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            if (propertyId == VehiclePropertyIds.GEAR_SELECTION) gear = null
            if (propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) speed = null
            publish()
        }
    }
    private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { if (!closed) { ux = it; publish() } }
    private val powerListener = CarPowerManager.CarPowerPolicyListener { if (!closed) readPolicy(it) }

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        if (Build.VERSION.SDK_INT < 34 || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) { mutable.value = DrivingAccess(status = "unsupported"); return }
        if (car != null) { refresh(); return }
        try {
            car = Car.createCar(context, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { current, ready ->
                if (closed) return@createCar
                if (!ready) {
                    connected = false
                    observingProperties = false // Car clears manager callbacks on disconnect.
                    properties = null; restrictions = null; power = null
                    gear = null; speed = null; ux = null; components = null
                    publish("disconnected"); return@createCar
                }
                connected = true
                try {
                    restrictions = current.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                    ux = restrictions?.currentCarUxRestrictions
                    restrictions?.registerListener(uxListener)
                    properties = current.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                    if (context.checkSelfPermission("android.car.permission.READ_CAR_POWER_POLICY") == PackageManager.PERMISSION_GRANTED) {
                        power = current.getCarManager(Car.POWER_SERVICE) as CarPowerManager
                        power?.addPowerPolicyListener(context.mainExecutor, CarPowerPolicyFilter.Builder().setComponents(PowerComponent.AUDIO, PowerComponent.MEDIA).build(), powerListener)
                        power?.currentPowerPolicy?.let(::readPolicy)
                    }
                    refresh()
                } catch (_: Exception) { gear = null; speed = null; ux = null; publish("unavailable") }
            }
        } catch (_: Exception) { connected = false; publish("unavailable") }
    }
    fun refresh() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed || !connected) return
        if (!hasPermissions()) {
            if (observingProperties) runCatching { properties?.unregisterCallback(propertyCallback) }
            observingProperties = false; gear = null; speed = null; publish("permission_required"); return
        }
        try {
            val manager = properties ?: return
            if (!observingProperties) {
                check(manager.registerCallback(propertyCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE))
                check(manager.registerCallback(propertyCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_ONCHANGE))
                observingProperties = true
            }
            gear = manager.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, 0).takeIf { it.status == CarPropertyValue.STATUS_AVAILABLE }?.value
            speed = manager.getProperty<Float>(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0).takeIf { it.status == CarPropertyValue.STATUS_AVAILABLE }?.value?.takeIf { it.isFinite() }
            ux = restrictions?.currentCarUxRestrictions
            publish()
        } catch (_: Exception) { gear = null; speed = null; publish("unavailable") }
    }
    private fun hasPermissions() = listOf("android.car.permission.CAR_POWERTRAIN", "android.car.permission.CAR_SPEED").all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun readPolicy(policy: CarPowerPolicy) {
        components = policy.isComponentEnabled(PowerComponent.AUDIO) && policy.isComponentEnabled(PowerComponent.MEDIA)
        publish()
    }
    private fun publish(status: String = if (connected) "connected" else "disconnected") {
        if (closed) return
        val next = DrivingAccess(connected, hasPermissions(), gear, speed, ux != null, ux?.isRequiresDistractionOptimization, ux?.activeRestrictions, components, status)
        if (mutable.value != next) {
            mutable.value = next
            Log.i("DriveEnglishVehicle", "gear=${next.gear} speed=${next.speed} known=${next.uxKnown} uxr=${next.restrictions} DO=${next.requiresOptimization} details=${next.detailsAllowed} permissions=${next.permissionGranted} status=${next.status}")
        }
    }
    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper()); closed = true
        runCatching { properties?.unregisterCallback(propertyCallback) }; runCatching { restrictions?.unregisterListener() }; runCatching { power?.removePowerPolicyListener(powerListener) }
        car?.disconnect(); car = null
        mutable.value = DrivingAccess(status = "closed")
    }
}
