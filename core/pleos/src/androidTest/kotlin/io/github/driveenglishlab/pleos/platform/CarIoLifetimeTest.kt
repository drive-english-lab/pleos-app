package io.github.driveenglishlab.pleos.platform

import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.power.PowerComponent
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.domain.VehiclePosition
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Test-owned public I/O, queues and clock. No OS policy, permission, DB or provider mutation. */
@SdkSuppress(minSdkVersion = 34)
@RunWith(AndroidJUnit4::class)
class CarIoLifetimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(body: () -> Unit) = instrumentation.runOnMainSync(body)
    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { synchronized(tasks) { tasks.addLast(command) } }
        fun take(): Runnable? = synchronized(tasks) { if (tasks.isEmpty()) null else tasks.removeFirst() }
        fun size() = synchronized(tasks) { tasks.size }
    }
    private class FakeIo : PublicCarIo {
        override val supported = true
        override val gearPermission = true
        override val speedPermission = true
        override val policyPermission = true
        var connects = 0
        var propertyCallback: ((PublicCarIo.Property) -> Unit)? = null
        var uxCallback: ((PublicCarIo.Ux) -> Unit)? = null
        var policyCallback: ((PublicCarIo.Policy) -> Unit)? = null
        var lifecycle: ((PublicCarIo.Connection, Boolean) -> Unit)? = null
        val calls = mutableListOf<String>()
        var policyValue = policy(true, true, true)
        var propertyRead: ((Int) -> PublicCarIo.Property)? = null
        var policyRead: (() -> PublicCarIo.Policy?)? = null
        var registerProperty: ((Int) -> Unit)? = null
        var unregisterFailure = false
        var unregisterBody: (() -> Unit)? = null
        var disconnectFailure = false
        private fun record(name: String) { check(Looper.myLooper() != Looper.getMainLooper()); calls += name }
        val connection = object : PublicCarIo.Connection {
            override fun properties(callback: (PublicCarIo.Property) -> Unit): PublicCarIo.Properties {
                record("property.manager"); propertyCallback = callback
                return object : PublicCarIo.Properties {
                    override fun register(key: Int): Boolean { record("property.register.$key"); registerProperty?.invoke(key); return true }
                    override fun read(key: Int): PublicCarIo.Property {
                        record("property.read.$key")
                        return propertyRead?.invoke(key) ?: PublicCarIo.Property(key, 1, true,
                            if (key == VehiclePropertyIds.GEAR_SELECTION) VehicleGear.GEAR_PARK as Any else 0f)
                    }
                    override fun unregister() { record("property.unregister"); unregisterBody?.invoke(); if (unregisterFailure) error("test_cleanup") }
                }
            }
            override fun ux(callback: (PublicCarIo.Ux) -> Unit): PublicCarIo.Restrictions {
                record("ux.manager"); uxCallback = callback
                return object : PublicCarIo.Restrictions {
                    override fun register() { record("ux.register") }
                    override fun read(): PublicCarIo.Ux { record("ux.read"); return PublicCarIo.Ux(true) }
                    override fun unregister() { record("ux.unregister") }
                }
            }
            override fun policy(callback: (PublicCarIo.Policy) -> Unit): PublicCarIo.Policies {
                record("policy.manager"); policyCallback = callback
                return object : PublicCarIo.Policies {
                    override fun register() { record("policy.register") }
                    override fun read(): PublicCarIo.Policy? { record("policy.read"); return policyRead?.invoke() ?: policyValue }
                    override fun unregister() { record("policy.unregister") }
                }
            }
            override fun disconnect() { record("disconnect"); if (disconnectFailure) error("test_disconnect") }
        }
        override fun connect(lifecycle: (PublicCarIo.Connection, Boolean) -> Unit): PublicCarIo.Connection {
            record("connect"); connects++; this.lifecycle = lifecycle
            lifecycle(connection, true) // Explicit ready-before-return order.
            return connection
        }
        companion object {
            fun policy(audio: Boolean, media: Boolean, mic: Boolean): PublicCarIo.Policy {
                val components = listOf(PowerComponent.AUDIO to audio, PowerComponent.MEDIA to media, PowerComponent.MICROPHONE to mic)
                return PublicCarIo.Policy("test", components.filter { it.second }.map { it.first }.toIntArray(),
                    components.filterNot { it.second }.map { it.first }.toIntArray())
            }
            fun partial(component: Int, enabled: Boolean) = PublicCarIo.Policy("partial",
                if (enabled) intArrayOf(component) else intArrayOf(), if (enabled) intArrayOf() else intArrayOf(component))
        }
    }
    private inner class Fixture {
        val executors = List(3) { ManualExecutor() }
        var clock = 0L
        val runtime = CarIoRuntime(Handler(Looper.getMainLooper()), { clock }, executors)
        val io = FakeIo()
        val monitor = CarAccessMonitor(io, runtime, Handler(Looper.getMainLooper()))
        fun start() = main { monitor.start() }
        fun step(lane: Int): Boolean {
            val task = executors[lane].take() ?: return false
            task.run(); main {}; return true
        }
        fun drain() {
            repeat(100) {
                var progressed = false
                for (lane in 0..2) if (step(lane)) progressed = true
                if (!progressed) return
            }
            error("unbounded_work")
        }
        fun close() { main { monitor.close() }; drain() }
        fun tick() = main { clock += 8_001; runtime.expireDue() }
        fun event(component: Int, enabled: Boolean) = main { io.policyCallback!!(FakeIo.partial(component, enabled)) }
        fun current() = monitor.state.value
    }

    @Test fun normalReadyBeforeReturnAllCallsOffMainAndConfirmedCleanupAllowsNextSource() {
        val f = Fixture(); f.start(); f.drain()
        assertEquals(1, f.io.connects)
        assertEquals(f.current().toString(), VehiclePosition.PARKED, f.current().safety.position)
        assertTrue(f.current().safety.uxKnown); assertTrue(f.current().components.audio.allowed)
        assertEquals(1, f.io.calls.count { it == "property.manager" })
        f.close()
        assertEquals("closed", f.current().status)
        assertTrue(f.current().diagnostics.cleanup.contains("disconnect=SUCCEEDED"))
        val nextIo = FakeIo(); val next = CarAccessMonitor(nextIo, f.runtime, Handler(Looper.getMainLooper()))
        main { next.start() }; f.drain(); assertEquals(1, nextIo.connects)
        main { next.close() }; f.drain()
    }

    @Test fun blockedPropertyLeavesMainAndIndependentChannelsResponsiveClosePrecedesCleanup() {
        val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.io.propertyRead = { key -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); PublicCarIo.Property(key, 1, true, VehicleGear.GEAR_PARK) }
        f.start(); f.step(0) // connect
        f.step(0); f.step(0); f.step(0) // manager, two registrations; gear read is now active.
        val native = checkNotNull(f.executors[0].take())
        val worker = Thread { native.run() }.apply { start() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(4) { f.step(1); f.step(2) }
            var heartbeat = false
            main { heartbeat = true; assertTrue(f.current().safety.uxKnown); assertTrue(f.current().components.audio.allowed) }
            assertTrue(heartbeat)
            f.tick(); assertEquals(VehiclePosition.UNKNOWN, f.current().safety.position)
            main { f.monitor.close(); assertEquals("closed", f.current().status); assertFalse(f.current().components.audio.known) }
            assertFalse(f.io.calls.contains("property.unregister"))
            main { assertEquals(1, f.runtime.occupancy()[0].first) }
        } finally { release.countDown(); worker.join(3_000); main {}; f.drain() }
        assertFalse(worker.isAlive)
        assertEquals("closed", f.current().status)
        assertTrue(f.current().diagnostics.cleanup.contains("disconnect=SUCCEEDED"))
    }

    @Test fun microphoneThenAudioEventsInvalidateImmediatelyOldReadCannotRestoreAndDirtyIsSticky() {
        val f = Fixture(); f.start(); f.drain()
        val original = f.current().components
        f.event(PowerComponent.MICROPHONE, false) // First read active, not executed yet.
        assertEquals(original.audio, f.current().components.audio)
        assertFalse(f.current().components.microphone.known)
        f.event(PowerComponent.AUDIO, false) // One coalesced pending read; stale first lease.
        assertFalse(f.current().components.audio.known)
        f.event(PowerComponent.MICROPHONE, true)
        assertFalse(f.current().components.audio.known)
        assertEquals(original.media, f.current().components.media)
        f.step(2) // Old full read returns all-enabled; must not restore either dirty bit.
        assertFalse(f.current().components.audio.known); assertFalse(f.current().components.microphone.known)
        f.io.policyValue = FakeIo.policy(false, true, true)
        f.step(2)
        assertTrue(f.current().components.audio.known); assertFalse(f.current().components.audio.allowed)
        assertTrue(f.current().components.microphone.allowed)
        assertEquals(original.media.permissionGeneration, f.current().components.media.permissionGeneration)
        f.close()
    }

    @Test fun stalePolicyFailureAndMicrophoneOnlyTimeoutPreserveUnrelatedGrants() {
        val f = Fixture(); f.start(); f.drain()
        val old = f.current().components
        f.event(PowerComponent.MICROPHONE, false)
        f.event(PowerComponent.MICROPHONE, true)
        f.io.policyRead = { throw SecurityException("test_old_read") }
        f.step(2) // Error from superseded lease must not invalidate AUDIO/MEDIA.
        assertEquals(old.audio, f.current().components.audio)
        assertEquals(old.media, f.current().components.media)
        f.tick() // Active second lease times out logically, native slot remains occupied.
        assertFalse(f.current().components.microphone.known)
        assertEquals(old.audio, f.current().components.audio)
        assertEquals(old.media, f.current().components.media)
        f.io.policyRead = null
        f.step(2) // Late success is not recovery.
        assertFalse(f.current().components.microphone.known)
        f.close()
    }

    @Test fun partialContradictionCannotCreateGrantAndExactRepeatedFullKeepsOtherPermissions() {
        val f = Fixture(); f.start(); f.drain()
        val old = f.current().components
        main { f.io.policyCallback!!(PublicCarIo.Policy("contradiction", intArrayOf(PowerComponent.AUDIO), intArrayOf(PowerComponent.AUDIO))) }
        assertFalse(f.current().components.audio.known)
        assertEquals(old.media, f.current().components.media)
        f.step(2)
        assertTrue(f.current().components.audio.allowed)
        assertEquals(old.audio.permissionGeneration + 2, f.current().components.audio.permissionGeneration)
        assertEquals(old.media.permissionGeneration, f.current().components.media.permissionGeneration)
        val recovered = f.current().components
        f.event(PowerComponent.MEDIA, true); f.step(2)
        assertEquals(recovered.audio.permissionGeneration, f.current().components.audio.permissionGeneration)
        assertEquals(recovered.media.permissionGeneration, f.current().components.media.permissionGeneration)
        f.close()
    }

    @Test fun propertyPreRegistrationKeepsBothKeysAndLateReadCannotOverrideErrorEvent() {
        val f = Fixture()
        f.io.registerProperty = { key ->
            f.io.propertyCallback!!(PublicCarIo.Property(key, 4, true,
                if (key == VehiclePropertyIds.GEAR_SELECTION) VehicleGear.GEAR_PARK as Any else 0f))
        }
        f.start(); f.step(0); f.step(0); f.step(0); f.step(0)
        assertEquals(VehiclePosition.PARKED, f.current().safety.position)
        main { f.io.propertyCallback!!(PublicCarIo.Property(VehiclePropertyIds.GEAR_SELECTION, -1, false, null)) }
        f.step(0) // Initial read was already admitted, so the newer error wins.
        assertEquals(VehiclePosition.UNKNOWN, f.current().safety.position)
        f.drain(); f.close()
    }

    @Test fun failedCleanupQuarantinesOriginalQuotaAndOnlyOneNextStartCanWait() {
        val f = Fixture(); f.start(); f.drain(); f.io.unregisterFailure = true
        f.close()
        assertTrue(f.current().diagnostics.cleanup.contains("property=FAILED"))
        assertTrue(f.io.calls.contains("disconnect")) // Best effort does not erase failed unregister.
        val secondIo = FakeIo(); val second = CarAccessMonitor(secondIo, f.runtime, Handler(Looper.getMainLooper()))
        val thirdIo = FakeIo(); val third = CarAccessMonitor(thirdIo, f.runtime, Handler(Looper.getMainLooper()))
        main { second.start(); third.start() }; f.drain()
        assertEquals(0, secondIo.connects); assertEquals(0, thirdIo.connects)
        assertEquals("connection_busy", third.state.value.status)
        main { second.close(); third.close() }; f.drain()
    }

    @Test fun disconnectReconnectAndOldCallbacksCannotMutateNewSource() {
        val f = Fixture(); f.start(); f.drain()
        val oldCallback = f.io.propertyCallback!!
        main { f.io.lifecycle!!(f.io.connection, false) }; main {}; f.drain()
        assertEquals(2, f.io.connects)
        val before = f.current()
        main { oldCallback(PublicCarIo.Property(VehiclePropertyIds.GEAR_SELECTION, 50, true, VehicleGear.GEAR_DRIVE)) }
        assertEquals(before, f.current())
        f.close()
    }

    @Test fun waitingStartExpiresAtAdmissionAndCannotAcquireAfterOldCleanup() {
        val f = Fixture(); val old = Any()
        main { assertTrue(f.runtime.acquire(old) {}) }
        f.start(); assertEquals(0, f.io.connects)
        f.tick(); assertEquals("connection_timeout", f.current().status)
        main { f.runtime.release(old) }; f.drain()
        assertEquals(0, f.io.connects)
        f.close()
    }

    @Test fun elapsedAdmissionDeadlineBeforeWatchRunsStillRejectsDelayedNativeConnect() {
        val f = Fixture(); val old = Any()
        main { assertTrue(f.runtime.acquire(old) {}) }
        f.start()
        main { f.clock += 8_001; f.runtime.release(old) } // Do not fire the Handler/watch first.
        f.drain()
        assertEquals("connection_timeout", f.current().status); assertEquals(0, f.io.connects)
        f.close()
    }

    @Test fun lateRegistrationAfterCloseCleansOriginalBeforeWaitingSourceStarts() {
        val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.io.registerProperty = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        f.start(); f.step(0); f.step(0)
        val task = checkNotNull(f.executors[0].take())
        val worker = Thread { task.run() }.apply { start() }
        val nextIo = FakeIo(); val next = CarAccessMonitor(nextIo, f.runtime, Handler(Looper.getMainLooper()))
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            main { f.monitor.close(); next.start() }; assertEquals(0, nextIo.connects)
        } finally { release.countDown(); worker.join(3_000); main {}; f.drain() }
        assertEquals("closed", f.current().status)
        assertFalse(f.current().components.audio.known)
        assertTrue(f.io.calls.indexOf("property.unregister") > f.io.calls.indexOf("property.register.${VehiclePropertyIds.GEAR_SELECTION}"))
        assertEquals(1, nextIo.connects)
        main { next.close() }; f.drain()
    }

    @Test fun retiredReservationSkipsNativeEntryAndStillRunsOriginalCleanup() {
        val f = Fixture(); f.start(); f.step(0); f.step(0) // Registration reserved, not entered.
        main { f.monitor.close() }; f.drain()
        assertFalse(f.io.calls.any { it.startsWith("property.register") })
        assertTrue(f.io.calls.contains("disconnect"))
        assertEquals("closed", f.current().status)
    }

    @Test fun blockedCleanupRemainsUnconfirmedAndDoesNotFreeQuotaAtLogicalDeadline() {
        val f = Fixture(); f.start(); f.drain()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.io.unregisterBody = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        main { f.monitor.close() }
        val task = checkNotNull(f.executors[0].take())
        val worker = Thread { task.run() }.apply { start() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS)); f.step(1); f.step(2)
            f.tick()
            assertTrue(f.current().diagnostics.cleanup.contains("property=UNCONFIRMED"))
            assertFalse(f.io.calls.contains("disconnect"))
            main { assertEquals(1, f.runtime.occupancy()[0].first) }
        } finally { release.countDown(); worker.join(3_000); main {}; f.drain() }
        assertTrue(f.current().diagnostics.cleanup.contains("property=SUCCEEDED"))
        assertTrue(f.current().diagnostics.cleanup.contains("disconnect=SUCCEEDED"))
    }

    @Test fun connectReservedBeforeOriginalDeadlineCannotEnterNativeAfterThatDeadline() {
        val f = Fixture(); val old = Any()
        main { assertTrue(f.runtime.acquire(old) {}) }
        f.start()
        main { f.clock = 7_900; f.runtime.release(old); f.clock = 8_100 }
        f.drain() // Original watchdog has not been run; worker checks the same deadline.
        assertEquals(0, f.io.connects)
        assertEquals("connection_timeout", f.current().status)
        f.close()
    }

    @Test fun runtimeQueueDeadlineCoalescingAndCleanupPriorityNeverReplaceBlockedSlot() {
        val f = Fixture(); val owner = Any(); val calls = mutableListOf<String>(); var timedOut = 0
        main {
            assertTrue(f.runtime.acquire(owner) {})
            assertTrue(f.runtime.submit(CarIoRuntime.Channel.PROPERTY, owner, "blocked", { { calls += "blocked" } }, { timedOut++ }) { _, _, _ -> })
            assertTrue(f.runtime.submit(CarIoRuntime.Channel.PROPERTY, owner, "refresh", { { calls += "pending" } }, { timedOut++ }) { _, _, _ -> })
            assertTrue(f.runtime.submit(CarIoRuntime.Channel.PROPERTY, owner, "refresh", { { calls += "coalesced" } }, { timedOut++ }) { _, _, _ -> })
            assertFalse(f.runtime.submit(CarIoRuntime.Channel.PROPERTY, owner, "other", { { calls += "other" } }, {}) { _, _, _ -> })
            assertEquals(1 to 1, f.runtime.occupancy()[0])
        }
        f.tick()
        main { assertEquals(1 to 0, f.runtime.occupancy()[0]) }
        assertEquals(2, timedOut)
        main { f.runtime.retire(CarIoRuntime.Channel.PROPERTY, owner, { { calls += "cleanup" } }, {}) { _, _, _ -> } }
        f.step(0); f.step(0)
        assertEquals(listOf("cleanup"), calls) // Expired reservation never enters a public call.
        main { f.runtime.release(owner) }
    }
}
