package io.github.driveenglishlab.pleos.platform

import ai.pleos.playground.stt.listener.ResultListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Test-owned clock/lane/public callback vectors. No SDK, OS focus/grant, mic, provider or real
 * approval is created. Production null approval remains unavailable; nothing is added to DI. */
@RunWith(AndroidJUnit4::class)
class PleosSttGatewayTest {
    private class Runtime : ParkedSttRuntime {
        var time = 0L
        var permission = true
        var holdSdk = false
        var changed: ((Boolean) -> Unit)? = null
        val event = ArrayDeque<() -> Unit>()
        val sdk = ArrayDeque<() -> Unit>()
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        override var quarantined = false
        override var recoveryReady = false
        override fun assertEventLane() = Unit
        override fun now() = time
        override fun post(action: () -> Unit) { event.addLast(action) }
        override fun at(deadline: Long, action: () -> Unit) { timers.add(deadline to action) }
        override fun sdk(action: () -> Unit) { sdk.addLast(action) }
        override fun permissions() = permission
        override fun focus(changed: (Boolean) -> Unit): Boolean { this.changed = changed; return true }
        override fun abandonFocus() = Unit
        fun drain() {
            repeat(100) {
                if (event.isNotEmpty()) event.removeFirst().invoke()
                else if (!holdSdk && sdk.isNotEmpty()) sdk.removeFirst().invoke()
                else return
            }
            error("Synthetic lane did not settle")
        }
        fun advance(value: Long) {
            time = value
            while (true) {
                val next = timers.withIndex().filter { it.value.first <= time }.minByOrNull { it.value.first } ?: break
                timers.removeAt(next.index).second.invoke()
                drain()
            }
            drain()
        }
    }

    private class Engine : ParkedSttEngine {
        var listener: ResultListener? = null
        var removed: ResultListener? = null
        var ready: (() -> Unit)? = null
        var autoInitialize = true
        var throwAdd = false
        var throwRelease = false
        var requests = 0
        var stops = 0
        var releases = 0
        val calls = mutableListOf<String>()
        override fun initialize(success: () -> Unit, failure: (String) -> Unit) {
            calls += "initialize"; ready = success; if (autoInitialize) success()
        }
        override fun add(listener: ResultListener) {
            calls += "add"; this.listener = listener
            if (throwAdd) error("Synthetic partial registration failure")
        }
        override fun locale() { calls += "en-US" }
        override fun request() { calls += "request"; requests++ }
        override fun stop() { calls += "stop"; stops++ }
        override fun remove(listener: ResultListener) { calls += "remove"; removed = listener }
        override fun release() { calls += "release"; releases++; if (throwRelease) error("synthetic release failure") }
    }

    private class Fixture(approval: Boolean = true) {
        val runtime = Runtime()
        val engine = Engine()
        var factories = 0
        var throwFactory = false
        var current = true
        val events = mutableListOf<DirectSpeechEvent>()
        val owner = OwnerContext(UUID.randomUUID(), 3, 2, UUID.randomUUID())
        val access = AccessState(
            safety = SafetyState(VehiclePosition.PARKED, uxKnown = true, detailsAllowed = true, directInputAllowed = true),
            window = WindowState(true, true), power = PowerAvailability.UNKNOWN,
            microphoneGranted = true, directProviderVerified = true, inputArbitrationVerified = true,
            ownerValid = true, directReplyFocusAllowed = true,
            components = ComponentPolicies(generation = 7, microphone = ComponentAccess(true, true, 7, 11)))
        val task = PracticeContentRef(ContentRef(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), ListeningMode.ORIGINAL_FULL), UUID.randomUUID(), UUID.randomUUID())
        val lease = CaptureLease(UUID.randomUUID(), UUID.randomUUID(), owner, task, UUID.randomUUID(),
            access.safety.generation, access.window.generation, 11)
        val gateway = PleosSttGateway(
            if (approval) ParkedSpeechRuntimeApproval("synthetic-environment", "synthetic-retention", "synthetic-arbitration",
                ParkedSpeechProcessingNotice("Synthetic test notice", "synthetic-v1", "synthetic-notice-review")) else null,
            { current && it == lease }, { events += it }, runtime, {
                factories++; if (throwFactory) error("Synthetic handle construction failure") else engine
            })
        fun begin(): ResultListener {
            gateway.initialize(); assertTrue(gateway.acquireFocus(owner))
            assertTrue(gateway.start(lease, access)); runtime.drain()
            assertEquals(listOf("initialize", "add", "en-US", "request"), engine.calls)
            return requireNotNull(engine.listener)
        }
        fun final(listener: ResultListener, text: String = "  A city opened a bridge.  ") {
            listener.onUpdated(text, true); runtime.drain()
        }
        fun ended(listener: ResultListener) { listener.onEndedRecognition(); runtime.drain() }
        fun finals() = events.filterIsInstance<DirectSpeechEvent.Final>()
        fun failures() = events.filterIsInstance<DirectSpeechEvent.Failed>()
    }

    @Test fun missingReviewOrPermissionNeverCreatesSdkOrAutomaticallyStarts() {
        val absent = Fixture(false)
        absent.gateway.initialize()
        assertFalse(absent.gateway.acquireFocus(absent.owner))
        assertFalse(absent.gateway.start(absent.lease, absent.access))
        assertNull(absent.gateway.state.value.processingNotice)
        assertEquals(0, absent.factories)
        val denied = Fixture(); denied.runtime.permission = false; denied.gateway.initialize()
        assertFalse(denied.gateway.acquireFocus(denied.owner)); assertEquals(0, denied.factories)
        denied.runtime.permission = true
        assertEquals(0, denied.factories) // A grant itself is not a Begin.
    }

    @Test fun finalThenEndedRetainsLogicalIdentityAndWaitsForExplicitFinish() {
        val f = Fixture(); val listener = f.begin()
        val capture = f.gateway.currentCaptureGeneration; val focus = f.gateway.currentFocusGeneration
        f.final(listener); f.ended(listener)
        assertTrue(f.finals().isEmpty()); assertTrue(f.gateway.state.value.finalReady)
        assertEquals(capture, f.gateway.currentCaptureGeneration); assertEquals(focus, f.gateway.currentFocusGeneration)
        assertSame(listener, f.engine.removed); assertEquals(1, f.engine.stops); assertEquals(1, f.engine.releases)
        f.gateway.finish(f.lease)
        val final = f.finals().single()
        assertEquals(f.lease, final.lease); assertEquals("A city opened a bridge.", final.text)
        assertEquals(Capture.unverified(CapturePath.PARKED_STT), final.capture)
        assertTrue(f.gateway.acceptsEvent(f.owner, focus))
        f.gateway.finish(f.lease); f.final(listener, "Late replacement"); f.ended(listener)
        assertEquals(1, f.finals().size)
    }

    @Test fun finishEndedFinalAndEndedFinalFinishBothDeliverExactlyOnceAfterCleanup() {
        for (finishFirst in listOf(true, false)) {
            val f = Fixture(); val listener = f.begin()
            if (finishFirst) f.gateway.finish(f.lease)
            f.ended(listener); assertTrue(f.finals().isEmpty())
            f.final(listener)
            if (!finishFirst) { assertTrue(f.finals().isEmpty()); f.gateway.finish(f.lease) }
            assertEquals(1, f.finals().size); assertEquals(1, f.engine.releases)
            assertFalse(f.runtime.quarantined)
        }
    }

    @Test fun identicalFinalIsIdempotentAndConflictingFinalRejectsBeforeSubmission() {
        val same = Fixture(); val s = same.begin()
        same.final(s); same.final(s); assertEquals(1, same.engine.stops)
        same.gateway.finish(same.lease); same.ended(s); assertEquals(1, same.finals().size)
        val different = Fixture(); val d = different.begin()
        different.final(d); different.final(d, "A different answer.")
        different.gateway.finish(different.lease); different.ended(d)
        assertTrue(different.finals().isEmpty())
        assertEquals("stt_ambiguous_final", different.failures().single().reason)
    }

    @Test fun partialMagnitudeEndpointAndInvalidTextNeverBecomeFinal() {
        for (text in listOf("   ", "x".repeat(1201))) {
            val f = Fixture(); val listener = f.begin()
            listener.onUpdated("Never persist this interim", false)
            listener.onUpdatedEpdData(1, 3200); listener.onUpdatedRms(1.0); listener.onReady(); listener.onStartedRecognition()
            f.runtime.drain(); assertTrue(f.events.isEmpty())
            f.final(listener, text); f.gateway.finish(f.lease); f.ended(listener)
            assertTrue(f.finals().isEmpty()); assertEquals("stt_invalid_final", f.failures().single().reason)
        }
    }

    @Test fun initializationWatchdogSurvivesBlockedLaneAndLateSuccessNeverRequests() {
        val f = Fixture(); f.engine.autoInitialize = false
        f.gateway.initialize(); assertTrue(f.gateway.acquireFocus(f.owner)); assertTrue(f.gateway.start(f.lease, f.access))
        f.runtime.drain(); f.runtime.holdSdk = true
        f.runtime.advance(5_000)
        assertEquals("stt_initialization_timeout", f.failures().single().reason)
        assertTrue(f.runtime.quarantined); assertFalse(f.gateway.acquireFocus(f.owner))
        assertEquals(0, f.engine.requests); assertEquals(1, f.factories)
        f.engine.ready!!.invoke(); f.runtime.drain(); assertEquals(0, f.engine.requests)
        assertFalse(f.gateway.start(f.lease, f.access)); assertEquals(1, f.factories)
        f.runtime.holdSdk = false; f.runtime.drain()
        assertEquals(1, f.engine.releases); assertEquals(0, f.engine.requests)
    }

    @Test fun delayedCallbackAndDelayedSdkRequestCannotExtendOriginalDeadlines() {
        val callback = Fixture(); val listener = callback.begin()
        callback.gateway.finish(callback.lease); callback.runtime.drain()
        callback.runtime.time = 1_999
        listener.onUpdated("A bridge opened.", true) // Queued before the deadline, handled after it.
        listener.onEndedRecognition()
        callback.runtime.time = 2_000; callback.runtime.drain()
        assertTrue(callback.finals().isEmpty())
        assertEquals(1, callback.failures().size)

        val worker = Fixture(); worker.gateway.initialize(); worker.gateway.acquireFocus(worker.owner)
        assertTrue(worker.gateway.start(worker.lease, worker.access))
        worker.runtime.sdk.removeFirst().invoke() // initialize -> success callback
        worker.runtime.event.removeFirst().invoke() // initialization success -> configure SDK
        worker.runtime.sdk.removeFirst().invoke() // add/locale -> event recheck
        worker.runtime.event.removeFirst().invoke() // event recheck -> queued request
        worker.runtime.time = 5_000
        worker.runtime.drain() // The watchdog has deliberately not run; worker must reject anyway.
        assertEquals(0, worker.engine.requests); assertTrue(worker.finals().isEmpty())
        assertEquals(1, worker.failures().size)
    }

    @Test fun boundedActivePollDiscardsCandidateOnCurrentOrActualGrantLossWithoutCallback() {
        for (grantLoss in listOf(true, false)) {
            val f = Fixture(); val listener = f.begin(); f.final(listener); f.ended(listener)
            val generation = f.gateway.currentCaptureGeneration
            if (grantLoss) f.runtime.permission = false else f.current = false
            f.runtime.advance(250)
            assertFalse(f.gateway.state.value.finalReady)
            assertTrue(f.gateway.currentCaptureGeneration > generation)
            f.runtime.permission = true; f.current = true
            f.gateway.finish(f.lease)
            assertTrue(f.finals().isEmpty()); assertEquals(1, f.engine.requests)
        }
    }

    @Test fun settleDeadlineIsOriginalButCleanFinalReadyWaitsOnlyForOriginalWholeDeadline() {
        val settling = Fixture(); val a = settling.begin()
        settling.gateway.finish(settling.lease); settling.runtime.drain()
        settling.runtime.advance(1_999); settling.gateway.finish(settling.lease)
        settling.runtime.advance(2_000)
        assertEquals("stt_settle_timeout", settling.failures().single().reason)
        settling.final(a); settling.ended(a); assertTrue(settling.finals().isEmpty())
        val ready = Fixture(); val b = ready.begin(); ready.final(b); ready.ended(b)
        ready.runtime.advance(59_999); assertTrue(ready.gateway.state.value.finalReady)
        ready.gateway.finish(ready.lease); assertEquals(1, ready.finals().size)
        val timeout = Fixture(); val c = timeout.begin(); timeout.final(c); timeout.ended(c)
        timeout.runtime.advance(60_000); timeout.gateway.finish(timeout.lease)
        assertTrue(timeout.finals().isEmpty()); assertEquals("stt_capture_timeout", timeout.failures().single().reason)
    }

    @Test fun cleanupFailureCannotDeliverOrAcquireReplacementEvenWithNewPermission() {
        val f = Fixture(); val listener = f.begin(); f.engine.throwRelease = true
        f.gateway.finish(f.lease); f.final(listener); f.ended(listener)
        assertTrue(f.finals().isEmpty()); assertTrue(f.gateway.state.value.cleanupUnknown)
        assertEquals("stt_cleanup_unknown", f.failures().single().reason)
        assertFalse(f.gateway.acquireFocus(f.owner)); assertFalse(f.gateway.start(f.lease, f.access))
        assertEquals(1, f.factories)
    }

    @Test fun partialRegistrationCleansSameListenerButMissingConstructedHandleStaysQuarantined() {
        val registration = Fixture(); registration.engine.throwAdd = true
        registration.gateway.initialize(); registration.gateway.acquireFocus(registration.owner)
        assertTrue(registration.gateway.start(registration.lease, registration.access)); registration.runtime.drain()
        assertSame(registration.engine.listener, registration.engine.removed)
        assertEquals(1, registration.engine.releases); assertEquals(0, registration.engine.requests)
        assertTrue(registration.finals().isEmpty()); assertTrue(registration.runtime.recoveryReady)

        val construction = Fixture(); construction.throwFactory = true
        construction.gateway.initialize(); construction.gateway.acquireFocus(construction.owner)
        assertTrue(construction.gateway.start(construction.lease, construction.access)); construction.runtime.drain()
        assertTrue(construction.runtime.quarantined); assertFalse(construction.runtime.recoveryReady)
        assertFalse(construction.gateway.acquireFocus(construction.owner))
        assertEquals(1, construction.factories); assertTrue(construction.finals().isEmpty())
    }

    @Test fun currentOwnerWindowMicOrFocusLossDiscardsCandidateAndLateCallbacks() {
        for (focusLoss in listOf(true, false)) {
            val f = Fixture(); val listener = f.begin(); val generation = f.gateway.currentCaptureGeneration
            f.final(listener)
            if (focusLoss) { f.runtime.changed!!.invoke(false); f.runtime.drain() } else f.current = false
            f.ended(listener); f.gateway.finish(f.lease); f.final(listener)
            assertTrue(f.finals().isEmpty()); assertTrue(f.gateway.currentCaptureGeneration > generation)
            assertEquals(1, f.engine.requests)
        }
    }
}
