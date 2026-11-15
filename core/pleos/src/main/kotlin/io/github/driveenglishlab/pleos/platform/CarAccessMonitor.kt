package io.github.driveenglishlab.pleos.platform

import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.power.PowerComponent
import android.content.Context
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

/** Channel status and cleanup returns are observations, never synthesized permissions. */
data class VehicleSignalDiagnostics(
    val source: String = "android.car/public-api34",
    val sourceGeneration: Long = 0,
    val gear: String = "not_started",
    val speed: String = "not_started",
    val ux: String = "not_started",
    val components: String = "not_started",
    val policyId: String? = null,
    val connectionFailure: String? = null,
    val propertyIo: String = "not_started",
    val uxIo: String = "not_started",
    val policyIo: String = "not_started",
    val cleanup: String = "not_requested",
)

/** Main is the single state writer. Public Binder calls use the process-owned bounded lanes. */
@RequiresApi(34)
class CarAccessMonitor internal constructor(
    private val io: PublicCarIo,
    private val runtime: CarIoRuntime,
    private val main: Handler,
) : AutoCloseable {
    constructor(context: Context) : this(AndroidPublicCarIo(context.applicationContext, Handler(Looper.getMainLooper())),
        CarIoRuntime.shared, Handler(Looper.getMainLooper()))
    data class Snapshot(
        val safety: SafetyState = SafetyState(),
        val components: ComponentPolicies = ComponentPolicies(),
        val status: String = "not_started",
        val diagnostics: VehicleSignalDiagnostics = VehicleSignalDiagnostics(),
    ) {
        val componentPolicy: PowerAvailability get() = PowerAvailability.UNKNOWN
    }
    private enum class Action { NOT_APPLICABLE, NOT_ATTEMPTED, PENDING, SUCCEEDED, FAILED, UNCONFIRMED }
    private class Entry(val channel: CarIoRuntime.Channel) {
        var handle: Any? = null
        var operation = 0L
        var registered = false
        var registrationAttempted = false
        var cleanup = Action.NOT_ATTEMPTED
        var retiring = false
        var cleanupScheduled = false
        var policyEventRevision = 0L
        var dirty = 7
        var bufferedPolicy: PublicCarIo.Policy? = null
        var bufferedUx: PublicCarIo.Ux? = null
        val bufferedProperties = mutableMapOf<Int, PublicCarIo.Property>()
        var uxRevision = 0L
        val propertyRevisions = mutableMapOf<Int, Long>()
    }
    private class Session(val attempt: Long, val source: Long, val connectionDeadline: Long) {
        var connection: PublicCarIo.Connection? = null
        var creationReturned = false
        var nativeStarted = false
        var cancelConnectionDeadline: (() -> Unit)? = null
        var ready: Pair<PublicCarIo.Connection, Boolean>? = null
        var attached = false
        var retiring = false
        var disconnectScheduled = false
        var disconnect = Action.NOT_ATTEMPTED
        var released = false
        val entries = CarIoRuntime.Channel.entries.map(::Entry)
    }
    /** Fixed-key mailbox also bounds callbacks arriving before manager registration returns. */
    private inner class Mailbox<T>(private val consume: (T) -> Unit) {
        private val values = LinkedHashMap<Int, T>()
        private var posted = false
        fun offer(key: Int, value: T) {
            if (Looper.myLooper() == main.looper) { consume(value); return }
            synchronized(this) {
                values[key] = value
                if (posted) return
                posted = true
            }
            main.post {
                val batch = synchronized(this) { val batch = values.values.toList(); values.clear(); posted = false; batch }
                batch.forEach(consume)
            }
        }
    }
    private val mutable = MutableStateFlow(Snapshot())
    val state = mutable.asStateFlow()
    private var session: Session? = null
    private var attempt = 0L
    private var sourceGeneration = 0L
    private var policyGeneration = 0L
    private var started = false
    private var closed = false
    private var gear: Int? = null
    private var speed: Float? = null
    private var ux: PublicCarIo.Ux? = null
    private var gearTimestamp = -1L
    private var speedTimestamp = -1L

    fun start() {
        checkMain(); check(!started && !closed); started = true
        if (Build.VERSION.SDK_INT < 34 || !io.supported) { invalidate("unsupported"); return }
        connect()
    }
    /** One explicit retry intent uses the same process quota and original-resource cleanup.
     * A queued/in-flight connection retains its original deadline; repeat taps do not extend it.
     */
    fun retry(): Boolean {
        checkMain()
        if (!started || closed || Build.VERSION.SDK_INT < 34 || !io.supported) return false
        val current = mutable.value
        val components = current.components
        if (current.status == "connected" && current.safety.position != VehiclePosition.UNKNOWN && current.safety.uxKnown &&
            current.diagnostics.gear == "available" && current.diagnostics.speed == "available" &&
            listOf(components.audio, components.media, components.microphone).all { it.known && it.sourceGeneration == components.generation }) return true
        val original = session
        if (original != null && !original.retiring && !original.released && current.status == "connecting") return false
        original?.let {
            it.cancelConnectionDeadline?.invoke()
            runtime.cancelWaiting(it)
            if (it.nativeStarted && !it.released && !it.retiring) retire(it)
            else if (!it.nativeStarted) it.retiring = true
        }
        // connect invalidates source/component generations before any new public read. acquire
        // waits behind the old native session; failed/unknown cleanup never releases its quota.
        connect()
        return mutable.value.status == "connecting"
    }
    private fun connect() {
        invalidate("connecting")
        val original = Session(++attempt, sourceGeneration, runtime.admissionDeadline())
        session = original
        original.cancelConnectionDeadline = runtime.connectionDeadline(original.connectionDeadline) {
            if (current(original) && !original.attached) {
                invalidate("connection_timeout"); runtime.cancelWaiting(original)
                if (original.nativeStarted) retire(original) else original.retiring = true
            }
        }
        if (!runtime.acquire(original) {
            if (current(original) && !runtime.expired(original.connectionDeadline)) create(original)
            else {
                original.cancelConnectionDeadline?.invoke()
                if (current(original)) invalidate("connection_timeout")
                original.retiring = true
                runtime.release(original)
            }
        }) {
            original.cancelConnectionDeadline?.invoke()
            invalidate("connection_busy"); session = null
        }
    }
    private fun current(s: Session): Boolean = !closed && session === s && !s.retiring &&
        attempt == s.attempt && sourceGeneration == s.source
    private fun current(s: Session, e: Entry) = current(s) && !e.retiring && s.entries[e.channel.ordinal] === e
    private fun create(s: Session) {
        s.nativeStarted = true
        val callback: (PublicCarIo.Connection, Boolean) -> Unit = { connection, ready ->
            main.post {
                if (current(s)) {
                    s.ready = connection to ready
                    if (s.creationReturned) acceptConnection(s)
                }
            }
        }
        runtime.submit(CarIoRuntime.Channel.PROPERTY, s, "connect", deadline = s.connectionDeadline, prepare = { { io.connect(callback) } },
            timeout = { if (current(s)) { invalidate("connection_timeout"); retire(s) } },
            finish = { result, failure, expired ->
                s.creationReturned = true
                s.connection = result as? PublicCarIo.Connection ?: s.ready?.first
                if (!current(s) || expired) { retire(s); cleanup(s); return@submit }
                if (failure != null || s.connection == null) {
                    invalidate("car_connection_unavailable", failure?.javaClass?.simpleName); retire(s)
                } else {
                    acceptConnection(s)
                }
            })
    }
    private fun acceptConnection(s: Session) {
        val event = s.ready ?: return
        if (!current(s) || event.first !== s.connection) return
        if (!event.second) {
            invalidate("disconnected"); retire(s)
            // At most one next-start intent; no new pool or overlapping native session.
            if (!closed) connect()
            return
        }
        if (s.attached) return
        if (runtime.expired(s.connectionDeadline)) { invalidate("connection_timeout"); retire(s); return }
        s.attached = true
        s.cancelConnectionDeadline?.invoke()
        mutable.value = mutable.value.copy(status = "connected")
        attachProperties(s, s.entries[0]); attachUx(s, s.entries[1]); attachPolicy(s, s.entries[2])
    }

    private fun attachProperties(s: Session, e: Entry) {
        val gearAllowed = io.gearPermission; val speedAllowed = io.speedPermission
        diagnostic(gear = if (gearAllowed) "initializing" else "permission_required",
            speed = if (speedAllowed) "initializing" else "permission_required")
        if (!gearAllowed && !speedAllowed) { e.cleanup = Action.NOT_APPLICABLE; return }
        val mailbox = Mailbox<PublicCarIo.Property> { value ->
            if (current(s, e)) {
                e.propertyRevisions[value.key] = (e.propertyRevisions[value.key] ?: 0L) + 1L
                if (!e.registered) e.bufferedProperties[value.key] = value else property(value)
            }
        }
        val connection = checkNotNull(s.connection)
        action(s, e, "manager", body = { connection.properties { value ->
            if ((value.key == VehiclePropertyIds.GEAR_SELECTION && gearAllowed) ||
                (value.key == VehiclePropertyIds.PERF_VEHICLE_SPEED && speedAllowed)) mailbox.offer(value.key, value)
        } }, adopt = { result ->
            val port = result as PublicCarIo.Properties; e.handle = port
            registerProperties(s, e, port, gearAllowed, speedAllowed)
        }, resource = true)
    }
    private fun registerProperties(s: Session, e: Entry, port: PublicCarIo.Properties, gearAllowed: Boolean, speedAllowed: Boolean) {
        val keys = buildList { if (gearAllowed) add(VehiclePropertyIds.GEAR_SELECTION); if (speedAllowed) add(VehiclePropertyIds.PERF_VEHICLE_SPEED) }
        fun read(index: Int) {
            if (index >= keys.size || !current(s, e)) return
            val key = keys[index]
            val revision = e.propertyRevisions[key] ?: 0L
            action(s, e, "read_$key", body = { port.read(key) }, adopt = {
                if (revision == (e.propertyRevisions[key] ?: 0L)) property(it as PublicCarIo.Property)
                read(index + 1)
            }, failureCurrent = { revision == (e.propertyRevisions[key] ?: 0L) })
        }
        fun register(index: Int) {
            if (index == keys.size) {
                e.registered = true
                val buffered = e.bufferedProperties.values.toList(); e.bufferedProperties.clear(); buffered.forEach(::property)
                read(0); return
            }
            e.registrationAttempted = true
            action(s, e, "register_${keys[index]}", body = { check(port.register(keys[index])); Unit }, adopt = { register(index + 1) })
        }
        register(0)
    }
    private fun property(value: PublicCarIo.Property) {
        when (value.key) {
            VehiclePropertyIds.GEAR_SELECTION -> {
                if (value.timestamp >= 0 && (value.timestamp < gearTimestamp || value.available && value.timestamp == gearTimestamp)) return
                if (value.timestamp >= 0) gearTimestamp = value.timestamp
                gear = if (value.available) value.value as? Int else null
                diagnostic(gear = if (gear == null) "read_unavailable" else "available")
            }
            VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                if (value.timestamp >= 0 && (value.timestamp < speedTimestamp || value.available && value.timestamp == speedTimestamp)) return
                if (value.timestamp >= 0) speedTimestamp = value.timestamp
                speed = if (value.available) (value.value as? Float)?.takeIf { it.isFinite() } else null
                diagnostic(speed = if (speed == null) "read_unavailable" else "available")
            }
        }
        project()
    }
    private fun attachUx(s: Session, e: Entry) {
        val mailbox = Mailbox<PublicCarIo.Ux> { value ->
            if (current(s, e)) {
                e.uxRevision++
                if (!e.registered) e.bufferedUx = value else { ux = value; diagnostic(ux = "available"); project() }
            }
        }
        val connection = checkNotNull(s.connection)
        action(s, e, "manager", body = { connection.ux { mailbox.offer(0, it) } }, adopt = { result ->
            val port = result as PublicCarIo.Restrictions; e.handle = port; e.registrationAttempted = true
            action(s, e, "register", body = { port.register() }, adopt = {
                e.registered = true
                e.bufferedUx?.let { ux = it; e.bufferedUx = null; diagnostic(ux = "available"); project() }
                val revision = e.uxRevision
                action(s, e, "read", body = { port.read() }, adopt = { value ->
                    if (revision == e.uxRevision) {
                        ux = value as? PublicCarIo.Ux
                        diagnostic(ux = if (ux == null) "current_value_unavailable" else "available"); project()
                    }
                }, failureCurrent = { revision == e.uxRevision })
            })
        }, resource = true)
    }
    private fun attachPolicy(s: Session, e: Entry) {
        if (!io.policyPermission) { unknownComponents("permission_required"); e.cleanup = Action.NOT_APPLICABLE; return }
        val mailbox = Mailbox<PublicCarIo.Policy> { value ->
            if (current(s, e)) {
                // Invalidate immediately even when registration has not returned.
                policyEvent(e, value)
                if (!e.registered) e.bufferedPolicy = value else readPolicy(s, e, e.handle as PublicCarIo.Policies)
            }
        }
        val connection = checkNotNull(s.connection)
        action(s, e, "manager", body = { connection.policy { mailbox.offer(0, it) } }, adopt = { result ->
            val port = result as PublicCarIo.Policies; e.handle = port; e.registrationAttempted = true
            action(s, e, "register", body = { port.register() }, adopt = {
                e.registered = true; e.bufferedPolicy = null
                readPolicy(s, e, port)
            })
        }, resource = true)
    }
    private fun policyEvent(e: Entry, value: PublicCarIo.Policy) {
        e.policyEventRevision++
        val on = value.enabled.toSet(); val off = value.disabled.toSet()
        val old = mutable.value.components
        var dirty = 0
        fun invalidate(component: Int, bit: Int, access: ComponentAccess): ComponentAccess {
            if (component !in on && component !in off) return access
            val contradictory = component in on && component in off
            val same = !contradictory && access.known && access.sourceGeneration == old.generation && access.allowed == (component in on)
            if (same) return access
            dirty = dirty or bit
            return if (access.known) ComponentAccess(permissionGeneration = access.permissionGeneration + 1) else access
        }
        val updated = old.copy(audio = invalidate(PowerComponent.AUDIO, 1, old.audio),
            media = invalidate(PowerComponent.MEDIA, 2, old.media), microphone = invalidate(PowerComponent.MICROPHONE, 4, old.microphone))
        e.dirty = e.dirty or dirty
        mutable.value = mutable.value.copy(components = updated)
    }
    private fun readPolicy(s: Session, e: Entry, port: PublicCarIo.Policies) {
        // Same-key pending request is coalesced by runtime; lease is frozen only on dispatch.
        var revision = -1L; var mask = 0
        val operation = e.operation + 1
        val accepted = runtime.submit(e.channel, s, "policy_read", prepare = {
            revision = e.policyEventRevision; mask = e.dirty
            { port.read() }
        }, timeout = {
            if (current(s, e) && revision == e.policyEventRevision) policyFailure(mask, "read_timeout")
        }, finish = { value, failure, expired ->
            if (s.retiring) { cleanup(s); return@submit }
            if (!current(s, e) || revision != e.policyEventRevision) return@submit
            if (!expired && failure == null && value is PublicCarIo.Policy) {
                val observed = projectComponents(value.enabled, value.disabled, ++policyGeneration)
                mutable.value = mutable.value.copy(components = advanceComponents(mutable.value.components, observed),
                    diagnostics = mutable.value.diagnostics.copy(components = "current_policy", policyId = value.id, policyIo = "read:succeeded"))
                e.dirty = e.dirty and mask.inv()
            } else if (!expired) {
                if (failure is SecurityException) { e.dirty = 7; policyFailure(7, "permission_required") }
                else policyFailure(mask, "read_unavailable")
            }
        })
        if (accepted) { e.operation = operation; ioDiagnostic(e.channel, "read:pending") }
    }
    private fun policyFailure(mask: Int, status: String) {
        val old = mutable.value.components
        fun reset(bit: Int, value: ComponentAccess) = if (mask and bit != 0 && value.known)
            ComponentAccess(permissionGeneration = value.permissionGeneration + 1) else value
        mutable.value = mutable.value.copy(components = old.copy(audio = reset(1, old.audio), media = reset(2, old.media), microphone = reset(4, old.microphone)),
            diagnostics = mutable.value.diagnostics.copy(components = status, policyIo = status))
    }
    private fun action(s: Session, e: Entry, name: String, body: () -> Any?, adopt: (Any?) -> Unit,
        resource: Boolean = false, failureCurrent: () -> Boolean = { true }) {
        val operation = ++e.operation
        ioDiagnostic(e.channel, "$name:pending")
        val accepted = runtime.submit(e.channel, s, name, prepare = { body }, timeout = {
            if (current(s, e) && operation == e.operation && failureCurrent()) channelUnknown(e, "${name}_timeout")
        }, finish = { value, failure, expired ->
            // Store only the original resource, including late acquisition, for its cleanup.
            if (resource && value != null) e.handle = value
            if (s.retiring) { cleanup(s); return@submit }
            if (!current(s, e) || operation != e.operation) return@submit
            if (failure != null || expired) {
                if (failureCurrent()) channelUnknown(e, if (expired) "${name}_timeout" else if (failure is SecurityException) "permission_required" else "${name}_unavailable")
                // A timed-out/failed registration is uncertain; retire only this channel.
                if (name.startsWith("register") || resource) { e.retiring = true; cleanupEntry(s, e) }
            } else { ioDiagnostic(e.channel, "$name:succeeded"); adopt(value) }
        })
        if (!accepted && current(s, e)) channelUnknown(e, "busy")
    }
    private fun channelUnknown(e: Entry, status: String) {
        ioDiagnostic(e.channel, status)
        when (e.channel) {
            CarIoRuntime.Channel.PROPERTY -> { gear = null; speed = null; diagnostic(gear = status, speed = status); project() }
            CarIoRuntime.Channel.UXR -> { ux = null; diagnostic(ux = status); project() }
            CarIoRuntime.Channel.POLICY -> unknownComponents(status)
        }
    }
    private fun retire(s: Session) {
        s.cancelConnectionDeadline?.invoke()
        s.retiring = true
        s.entries.forEach { it.retiring = true }
        cleanup(s)
    }
    private fun cleanup(s: Session) {
        if (!s.creationReturned) return
        s.entries.forEach { cleanupEntry(s, it) }
        if (!s.entries.all { it.cleanup in setOf(Action.SUCCEEDED, Action.FAILED, Action.NOT_APPLICABLE) }) return
        if (s.disconnectScheduled) return
        s.disconnectScheduled = true
        val connection = s.connection
        if (connection == null) { s.disconnect = Action.NOT_APPLICABLE; finishRetirement(s); return }
        s.disconnect = Action.PENDING
        runtime.retire(CarIoRuntime.Channel.PROPERTY, s, prepare = { { connection.disconnect() } }, timeout = {
            s.disconnect = Action.UNCONFIRMED; cleanupDiagnostic(s)
        }, finish = { _, failure, _ ->
            s.disconnect = if (failure == null) Action.SUCCEEDED else Action.FAILED
            finishRetirement(s)
        })
    }
    private fun cleanupEntry(s: Session, e: Entry) {
        if (!e.retiring || e.cleanupScheduled) return
        // A queued cleanup waits behind any original acquire/register/read native call.
        e.cleanupScheduled = true; e.cleanup = Action.PENDING
        runtime.retire(e.channel, s, prepare = {
            val handle = e.handle
            val needed = e.registrationAttempted
            {
                if (needed) when (handle) {
                    is PublicCarIo.Properties -> handle.unregister()
                    is PublicCarIo.Restrictions -> handle.unregister()
                    is PublicCarIo.Policies -> handle.unregister()
                }
                needed && handle != null
            }
        }, timeout = { e.cleanup = Action.UNCONFIRMED; cleanupDiagnostic(s) }, finish = { value, failure, _ ->
            e.cleanup = when { failure != null -> Action.FAILED; value == true -> Action.SUCCEEDED; else -> Action.NOT_APPLICABLE }
            cleanupDiagnostic(s)
            if (s.retiring) cleanup(s)
        })
    }
    private fun finishRetirement(s: Session) {
        cleanupDiagnostic(s)
        if (s.released) return
        if (s.disconnect !in setOf(Action.SUCCEEDED, Action.NOT_APPLICABLE)) return
        if (s.entries.any { it.cleanup !in setOf(Action.SUCCEEDED, Action.NOT_APPLICABLE) }) return
        if (CarIoRuntime.Channel.entries.all(runtime::idle)) {
            s.released = true
            runtime.release(s)
        }
    }
    private fun cleanupDiagnostic(s: Session) {
        if (session !== s && !closed) return
        val value = "property=${s.entries[0].cleanup}/uxr=${s.entries[1].cleanup}/policy=${s.entries[2].cleanup}/disconnect=${s.disconnect}"
        mutable.value = mutable.value.copy(diagnostics = mutable.value.diagnostics.copy(cleanup = value))
    }
    private fun ioDiagnostic(channel: CarIoRuntime.Channel, status: String) {
        val d = mutable.value.diagnostics
        mutable.value = mutable.value.copy(diagnostics = when (channel) {
            CarIoRuntime.Channel.PROPERTY -> d.copy(propertyIo = status)
            CarIoRuntime.Channel.UXR -> d.copy(uxIo = status)
            CarIoRuntime.Channel.POLICY -> d.copy(policyIo = status)
        })
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
        val permitted = ux?.detailsAllowed == true
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
    override fun close() {
        checkMain()
        if (closed) return
        closed = true; attempt++
        invalidate("closed")
        session?.let { original ->
            original.cancelConnectionDeadline?.invoke(); runtime.cancelWaiting(original)
            if (original.nativeStarted) retire(original)
        }
    }
    private fun checkMain() = check(Looper.myLooper() == main.looper)
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
