package io.github.driveenglishlab.pleos.platform

import android.car.Car
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
import android.os.Handler
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

/** Small public-call seam. Implementations do not write monitor state. */
internal interface PublicCarIo {
    data class Property(val key: Int, val timestamp: Long, val available: Boolean, val value: Any?)
    data class Ux(val detailsAllowed: Boolean)
    data class Policy(val id: String?, val enabled: IntArray, val disabled: IntArray)
    interface Connection { fun properties(callback: (Property) -> Unit): Properties; fun ux(callback: (Ux) -> Unit): Restrictions
        fun policy(callback: (Policy) -> Unit): Policies; fun disconnect() }
    interface Properties { fun register(key: Int): Boolean; fun read(key: Int): Property; fun unregister() }
    interface Restrictions { fun register(); fun read(): Ux?; fun unregister() }
    interface Policies { fun register(); fun read(): Policy?; fun unregister() }
    val supported: Boolean
    val gearPermission: Boolean
    val speedPermission: Boolean
    val policyPermission: Boolean
    fun connect(lifecycle: (Connection, Boolean) -> Unit): Connection?
}

@RequiresApi(34)
@Suppress("DEPRECATION")
internal class AndroidPublicCarIo(context: Context, private val main: Handler) : PublicCarIo {
    private val app = context.applicationContext
    override val supported get() = app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    override val gearPermission get() = granted("android.car.permission.CAR_POWERTRAIN")
    override val speedPermission get() = granted("android.car.permission.CAR_SPEED")
    override val policyPermission get() = granted("android.car.permission.READ_CAR_POWER_POLICY")
    private fun granted(name: String) = app.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
    override fun connect(lifecycle: (PublicCarIo.Connection, Boolean) -> Unit): PublicCarIo.Connection? {
        // One stable wrapper covers both ready-before-return and createCar's return.
        val wrapper = Connection(main)
        val car = Car.createCar(app, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { native, ready ->
            wrapper.bind(native); lifecycle(wrapper, ready)
        } ?: return null
        wrapper.bind(car)
        return wrapper
    }
    private class Connection(private val main: Handler) : PublicCarIo.Connection {
        // Binding is the only worker/callback shared identity, never product state.
        @Volatile private var native: Car? = null
        @Synchronized fun bind(car: Car) { check(native == null || native === car); native = car }
        private fun car() = checkNotNull(native)
        override fun properties(callback: (PublicCarIo.Property) -> Unit): PublicCarIo.Properties {
            val manager = car().getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: error("property_manager_unavailable")
            val listener = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) { callback(property(value)) }
                override fun onErrorEvent(propertyId: Int, areaId: Int) {
                    callback(PublicCarIo.Property(propertyId, -1, false, null))
                }
            }
            return object : PublicCarIo.Properties {
                override fun register(key: Int) = manager.registerCallback(listener, key, CarPropertyManager.SENSOR_RATE_ONCHANGE)
                override fun read(key: Int) = property(manager.getProperty<Any>(key, 0))
                override fun unregister() = manager.unregisterCallback(listener)
            }
        }
        override fun ux(callback: (PublicCarIo.Ux) -> Unit): PublicCarIo.Restrictions {
            val manager = car().getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager ?: error("ux_manager_unavailable")
            val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { callback(uxValue(it)) }
            return object : PublicCarIo.Restrictions {
                override fun register() = manager.registerListener(listener)
                override fun read() = manager.currentCarUxRestrictions?.let(::uxValue)
                override fun unregister() = manager.unregisterListener()
            }
        }
        override fun policy(callback: (PublicCarIo.Policy) -> Unit): PublicCarIo.Policies {
            val manager = car().getCarManager(Car.POWER_SERVICE) as? CarPowerManager ?: error("policy_manager_unavailable")
            val listener = CarPowerManager.CarPowerPolicyListener { callback(PublicCarIo.Policy(it.policyId,
                it.enabledComponents.copyOf(), it.disabledComponents.copyOf())) }
            val executor = Executor { command -> main.post(command) }
            return object : PublicCarIo.Policies {
                override fun register() = manager.addPowerPolicyListener(executor, CarPowerPolicyFilter.Builder()
                    .setComponents(PowerComponent.AUDIO, PowerComponent.MEDIA, PowerComponent.MICROPHONE).build(), listener)
                override fun read() = manager.currentPowerPolicy?.let { PublicCarIo.Policy(it.policyId,
                    it.enabledComponents.copyOf(), it.disabledComponents.copyOf()) }
                override fun unregister() = manager.removePowerPolicyListener(listener)
            }
        }
        override fun disconnect() = car().disconnect()
        companion object {
            private fun property(value: CarPropertyValue<*>) = PublicCarIo.Property(value.propertyId, value.timestamp,
                value.status == CarPropertyValue.STATUS_AVAILABLE, value.value)
            private fun uxValue(value: CarUxRestrictions) = PublicCarIo.Ux(!value.isRequiresDistractionOptimization && value.activeRestrictions == 0)
        }
    }
}
