package io.github.driveenglishlab.pleos.platform

import android.content.ContextWrapper
import android.content.pm.PackageManager
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
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
    class FaultSdk(private var failRemove: Boolean, private var failRelease: Boolean) {
        var removeCalls = 0
        var releaseCalls = 0
        var removed: EventListener? = null
        fun removeEventListener(listener: EventListener) {
            removeCalls++; removed = listener
            if (failRemove) throw IllegalStateException("synthetic cleanup fault")
        }
        fun repair() { failRemove=false; failRelease=false }
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

    @Test fun originalDoneSurvivesCleanupFailureAndFocusReturnButCannotGrantChangedOwnerOrColdRecovery()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        // Explicit test-owned permission seam; does not grant the library/app an OS permission.
        val context=object:ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun checkSelfPermission(permission:String)=if(permission=="pleos.car.permission.TTS_SERVICE")PackageManager.PERMISSION_GRANTED else super.checkSelfPermission(permission)
        }
        val originalRun=run()
        val access=AccessState(ownerValid=true,safety=SafetyState(VehiclePosition.PARKED,true,true,false,false,1),
            window=WindowState(true,true,1),components=ComponentPolicies(1,ComponentAccess(true,true,1,2),ComponentAccess(true,true,1,3)),mediaRouteAvailable=true)
        val limits=TtsOutputLimits1(100,2,5_000,7_000,1_000,250)
        val review=PleosOnDeviceScope1("synthetic-done-review",1,TtsReviewState.REVIEWED,"synthetic.fixture",1,"syntheticDebug",
            "synthetic-build","synthetic-environment",setOf(34),"synthetic-environment",setOf(SegmentPurpose.MAIN),setOf(SegmentLanguage.EN),setOf(originalRun.manifestHash),true,true,limits)
        val approval=PleosTtsProjectApproval("3.3.1",PleosTtsMode.ON_DEVICE,"synthetic-permission","synthetic-instance","synthetic-stop",
            mapOf(SegmentLanguage.EN to "synthetic-language"),"synthetic-vehicle",onDeviceScope=review)
        var ownerCurrent=true
        val gateway=withContext(Dispatchers.Main.immediate){PleosTtsGateway(context,scope,approval,currentFacts={run->
            TtsCurrentScopeFact1(access,if(ownerCurrent)run.owner else null,true,true,TtsPermissionState.GRANTED,1,0,true,5)})}
        var cold:PleosTtsGateway?=null
        try {
            val result=async(start=CoroutineStart.UNDISPATCHED){withTimeout(3_000){gateway.events.first()}}
            val sdk=FaultSdk(true,true)
            withContext(Dispatchers.Main.immediate) {
                val original=attach(gateway,originalRun,sdk,true,false,true).first
                write(original,"synthesisRequested",true);write(original,"intentGeneration",5L)
                val now=android.os.SystemClock.elapsedRealtime()
                write(original,"binding",TtsRunReviewBinding1(review.reviewId,1,UUID.randomUUID(),originalRun,SegmentLanguage.EN,
                    originalRun.segmentTextHash,0,1,1,2,3,1,now,now+5_000))
                @Suppress("UNCHECKED_CAST")
                val readiness=read(gateway,"mutable") as MutableStateFlow<PleosTtsReadiness>
                readiness.value=readiness.value.copy(focusGranted=true)
                suspendCoroutine<Unit>{continuation->
                    try {
                        val returned=gateway.javaClass.getDeclaredMethod("finish",original.javaClass,SegmentSpeechEventKind::class.java,String::class.java,Continuation::class.java)
                            .apply{isAccessible=true}.invoke(gateway,original,SegmentSpeechEventKind.COMPLETED,"synthetic-native-done",continuation)
                        if(returned!==COROUTINE_SUSPENDED)continuation.resume(Unit)
                    } catch(error:Throwable){continuation.resumeWithException(error)}
                }
            }
            val done=result.await()
            assertEquals(SegmentSpeechEventKind.COMPLETED,done.kind);assertEquals(OutputContinuationState.BLOCKED,done.outputContinuation)
            assertFalse(gateway.ownsOutputFocus(originalRun));assertFalse(gateway.quiescent)
            val fact=gateway.outputObservations.value.single()
            assertTrue(fact.nativeDoneObserved);assertTrue(fact.doneScopeValid);assertFalse(fact.quiescent)
            assertEquals(TtsCleanupStatus.FAILED,fact.listenerRemoval);assertEquals(TtsCleanupStatus.FAILED,fact.release)
            assertTrue(gateway.completionDisposition(done).admissible) // actual DONE precedes normal focus cleanup
            ownerCurrent=false;assertFalse(gateway.completionDisposition(done).admissible);ownerCurrent=true
            val hold=CompletionContinuation1(done.eventId,originalRun,null,true,OutputContinuationState.BLOCKED,"synthetic-cleanup-fault",true)
            cold=withContext(Dispatchers.Main.immediate){PleosTtsGateway(context,scope,approval)}
            assertEquals(OutputContinuationState.UNKNOWN,cold!!.recoverCleanup(hold))
            sdk.repair();assertEquals(OutputContinuationState.READY,gateway.recoverCleanup(hold));assertTrue(gateway.quiescent)
            assertEquals(SegmentSpeechEventKind.COMPLETED,done.kind) // cleanup recovery never rewrites original proof
        } finally {withContext(Dispatchers.Main.immediate){gateway.close();cold?.close()};scope.cancel()}
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
