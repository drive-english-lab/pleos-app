package io.github.driveenglishlab.pleos.platform

import ai.pleos.playground.tts.listener.EventListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.domain.*
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic fault injection into the actual Gateway cleanup boundary. No actual Pleos instance,
 * voice, permission, SDK initialization, provider, news or vehicle success is exercised. Reflection
 * attaches only a test-owned object to the existing private Any-SDK seam; production gets no factory. */
@RunWith(AndroidJUnit4::class)
class PleosTtsCleanupFailureTest {
    class FaultSdk(private val failRemove: Boolean, private val failRelease: Boolean) {
        var removeCalls = 0
        var releaseCalls = 0
        var removed: EventListener? = null
        fun removeEventListener(listener: EventListener) {
            removeCalls++; removed = listener
            if (failRemove) throw IllegalStateException("synthetic cleanup fault")
        }
        fun release() {
            releaseCalls++
            if (failRelease) throw UnsupportedOperationException("synthetic cleanup fault")
        }
    }
    private val text = "Owned cleanup fixture."
    private fun run() = SegmentRun(UUID.randomUUID(), 1, UUID.randomUUID(),
        OwnerContext(UUID.randomUUID(), 0, 1, UUID.randomUUID()), UUID.randomUUID(),
        SegmentSourceKey("prepared_components", "a".repeat(64)), SegmentPhase.MAIN, SegmentPurpose.MAIN,
        UUID.randomUUID(), "b".repeat(64), UUID.randomUUID(), 1, "c".repeat(64), UUID.randomUUID(), 0,
        segmentSha256(text), UUID.randomUUID())
    private fun read(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun write(target: Any, name: String, value: Any?) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    private fun status(observation: Any, name: String) = read(requireNotNull(read(observation, name)), "status").toString()
    private fun attach(gateway: PleosTtsGateway, run: SegmentRun, sdk: FaultSdk, initialized: Boolean,
        initializationPending: Boolean, registered: Boolean): Pair<Any, EventListener> {
        val listener = Proxy.newProxyInstance(EventListener::class.java.classLoader, arrayOf(EventListener::class.java)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "SyntheticCleanupListener"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        } as EventListener
        val type = Class.forName(PleosTtsGateway::class.java.name + '$' + "Invocation")
        val original = type.declaredConstructors.single { it.parameterTypes.size == 6 }.apply { isAccessible = true }
            .newInstance(run, sdk, listener, PleosTtsMode.ON_DEVICE, SegmentLanguage.EN, text)
        write(original, "initialized", initialized)
        write(original, "initializationRequested", initialized || initializationPending)
        (read(original, "initializationReported") as AtomicBoolean).set(initialized)
        write(original, "listenerRegistered", registered)
        write(gateway, "invocation", original)
        return original to listener
    }
    private fun dispose(gateway: PleosTtsGateway, original: Any) {
        gateway.javaClass.getDeclaredMethod("dispose", original.javaClass).apply { isAccessible = true }.invoke(gateway, original)
    }
    private fun observations(gateway: PleosTtsGateway) = read(gateway, "cleanupObservations") as List<*>

    @Test fun realCleanupBoundaryRetainsFailuresAndBlocksNewOutputWithoutInvokingProvider() = runBlocking {
        for ((removeFails, releaseFails) in listOf(true to false, false to true, true to true)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            var permissionChecks = 0
            val gateway = withContext(Dispatchers.Main.immediate) {
                PleosTtsGateway(InstrumentationRegistry.getInstrumentation().targetContext, scope,
                    PleosTtsProjectApproval("3.3.1", PleosTtsMode.ON_DEVICE, "synthetic-permission", "synthetic-instance",
                        "synthetic-stop", mapOf(SegmentLanguage.EN to "synthetic-language"), "synthetic-vehicle"),
                    permitted = { permissionChecks++; true })
            }
            try {
                val originalRun = run()
                val sdk = FaultSdk(removeFails, releaseFails)
                val attached = withContext(Dispatchers.Main.immediate) {
                    attach(gateway, originalRun, sdk, initialized = true, initializationPending = false, registered = true).also {
                        dispose(gateway, it.first)
                    }
                }
                val record = requireNotNull(observations(gateway).single())
                assertEquals(originalRun, read(record, "run"))
                assertEquals(read(attached.first, "instanceId"), read(record, "instanceId"))
                assertEquals(if (removeFails) "FAILED" else "SUCCEEDED", status(record, "listenerRemoval"))
                assertEquals(if (releaseFails) "FAILED" else "SUCCEEDED", status(record, "release"))
                assertEquals("NOT_APPLICABLE", status(record, "focusReturn"))
                assertSame(attached.second, sdk.removed)
                assertEquals(1, sdk.removeCalls); assertEquals(1, sdk.releaseCalls)
                assertEquals(removeFails, read(attached.first, "listenerRegistered"))
                if (removeFails) assertEquals(IllegalStateException::class.java.name,
                    read(requireNotNull(read(record, "listenerRemoval")), "exceptionClass"))
                if (releaseFails) assertEquals(UnsupportedOperationException::class.java.name,
                    read(requireNotNull(read(record, "release")), "exceptionClass"))
                assertFalse(gateway.quiescent)
                assertSame(attached.first, read(gateway, "blockedCleanup"))
                val refused = gateway.request(originalRun.copy(requestId = UUID.randomUUID()), text, SegmentLanguage.EN)
                assertEquals(SegmentSpeechAdmission.Unavailable("PLEOS_TTS_CLEANUP_UNCONFIRMED"), refused)
                assertEquals(0, permissionChecks)
                assertEquals(listOf(record), observations(gateway))
                assertEquals(originalRun, read(requireNotNull(observations(gateway).single()), "run"))
            } finally {
                withContext(Dispatchers.Main.immediate) { gateway.close() }
                scope.cancel()
            }
        }
    }

    @Test fun preInitializationCleanupDistinguishesAbsentListenerFromOutstandingResourceUncertainty() = runBlocking {
        for (pending in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val gateway = withContext(Dispatchers.Main.immediate) {
                PleosTtsGateway(InstrumentationRegistry.getInstrumentation().targetContext, scope)
            }
            try {
                val sdk = FaultSdk(failRemove = true, failRelease = false)
                val originalRun = run()
                withContext(Dispatchers.Main.immediate) {
                    val (original, _) = attach(gateway, originalRun, sdk, initialized = false,
                        initializationPending = pending, registered = false)
                    dispose(gateway, original)
                }
                val record = requireNotNull(observations(gateway).single())
                assertEquals(originalRun, read(record, "run"))
                assertEquals("NOT_APPLICABLE", status(record, "listenerRemoval"))
                assertEquals("NOT_APPLICABLE", status(record, "focusReturn"))
                assertEquals(if (pending) "UNCONFIRMED" else "SUCCEEDED", status(record, "release"))
                assertEquals(!pending, read(record, "initializationSettled"))
                assertEquals(0, sdk.removeCalls); assertEquals(1, sdk.releaseCalls)
                assertEquals(!pending, gateway.quiescent)
                assertEquals(pending, read(gateway, "blockedCleanup") != null)
            } finally {
                withContext(Dispatchers.Main.immediate) { gateway.close() }
                scope.cancel()
            }
        }
    }
}
