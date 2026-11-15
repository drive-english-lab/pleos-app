package io.github.driveenglishlab.pleos.platform

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import io.github.driveenglishlab.pleos.domain.*
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted

/** These are project/environment review records, never inferred from class or permission presence.
 * SDK3.3.1 onDone()/onStop() are untagged; per-instance callback isolation and stop ordering must
 * be verified before an instance-bound event can count as this request's completion.
 */
enum class PleosTtsMode { HYBRID, SERVER, ON_DEVICE }
data class PleosTtsProjectApproval(val sdkVersion: String, val mode: PleosTtsMode = PleosTtsMode.HYBRID,
    val projectPermissionEvidence: String, val instanceCorrelationEvidence: String, val stopOrderingEvidence: String,
    val languageEvidence: Map<SegmentLanguage, String>, val vehicleMediaEvidence: String,
    val backgroundEvidence: String? = null, val onDeviceScope:PleosOnDeviceScope1?=null) {
    init {
        require(sdkVersion == "3.3.1" && (onDeviceScope==null || mode==PleosTtsMode.ON_DEVICE))
        listOf(projectPermissionEvidence, instanceCorrelationEvidence, stopOrderingEvidence, vehicleMediaEvidence).forEach(::requireTtsEvidence)
        languageEvidence.values.forEach(::requireTtsEvidence); backgroundEvidence?.let(::requireTtsEvidence)
    }
}
class PleosTtsRegistration(val clientId: String, val clientSecret: String) {
    init { require(clientId.isNotBlank() && clientSecret.isNotBlank()) }
    override fun toString() = "PleosTtsRegistration(credentials=redacted)"
}
data class PleosTtsReadiness(val projectReviewed: Boolean = false, val sdkPresent: Boolean = false, val permissionGranted: Boolean = false,
    val initialized: Boolean = false, val serverConnected: Boolean = false, val focusGranted: Boolean = false,
    val reason: String? = "PROJECT_NOT_REVIEWED")

/** Optional public-SDK bridge. No Android TextToSpeech or file/server-audio fallback exists.
 * The documented optional artifact is loaded only when supplied by the approved project build.
 */
class PleosTtsGateway(private val context: Context, private val scope: CoroutineScope,
    approval: PleosTtsProjectApproval? = null,
    private val registration: suspend () -> PleosTtsRegistration? = { null },
    private val permitted: suspend (SegmentRun) -> Boolean = { false },
    private val appVisible: () -> Boolean = { false }, private val initializationTimeoutMs: Long = 15_000,
    private val outputPermitted: suspend (SegmentRun) -> Boolean = permitted,
    private val runtimeIdentity:TtsRuntimeIdentity1?=null,
    private val currentFacts:suspend(SegmentRun)->TtsCurrentScopeFact1?={null},
    private val programForRun:(SegmentRun)->SegmentProgram?={null},
    private val onScopeLost:suspend(SegmentRun,String)->Unit={_,_->}) : SegmentSpeechPort {
    private val approval=approval?.let{it.copy(languageEvidence=it.languageEvidence.toMap(),onDeviceScope=it.onDeviceScope?.let{s->s.copy(
        allowedApiLevels=s.allowedApiLevels.toSet(),allowedPurposes=s.allowedPurposes.toSet(),allowedLanguages=s.allowedLanguages.toSet(),allowedManifestHashes=s.allowedManifestHashes.toSet())})}
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(PleosTtsReadiness())
    val state = mutable.asStateFlow()
    private val observations = Channel<SegmentSpeechEvent>(Channel.UNLIMITED)
    // News and auxiliary coordinators share this gateway. Broadcast original immutable events;
    // receiveAsFlow alone would let one consumer steal and discard another run's completion.
    override val events = observations.receiveAsFlow().shareIn(scope, SharingStarted.Eagerly, replay = 0)
    private val scopeReview=approval?.onDeviceScope?.let{it.copy(allowedApiLevels=it.allowedApiLevels.toSet(),allowedPurposes=it.allowedPurposes.toSet(),allowedLanguages=it.allowedLanguages.toSet(),allowedManifestHashes=it.allowedManifestHashes.toSet())}
    private var invocation: Invocation? = null
    var scopeGeneration:Long=0;private set
    private var permissionState=TtsPermissionState.UNKNOWN
    private var permissionGeneration=0L
    // The app's public compile SDK exposes no permissions-change listener for this OEM grant.
    // Check at admission/callbacks/current-access edges and poll only while this invocation is active.
    // Poll latency and a blocked SDK/main call are not an atomic permission/output guarantee.
    private val externalCleanupHolds=mutableMapOf<UUID,CompletionContinuation1>()
    private data class CompletedBinding(val event:SegmentSpeechEvent,val binding:TtsRunReviewBinding1,val intentGeneration:Long)
    private val completedBindings=linkedMapOf<UUID,CompletedBinding>()
    private val outputFacts=MutableStateFlow<List<TtsOutputObservation1>>(emptyList())
    val outputObservations=outputFacts.asStateFlow()
    private var closed = false
    private var stopUnverified = false
    private var blockedCleanup: Invocation? = null
    private enum class CleanupStatus { NOT_APPLICABLE, NOT_ATTEMPTED, SUCCEEDED, FAILED, UNCONFIRMED }
    private data class CleanupResult(val status: CleanupStatus, val exceptionClass: String? = null)
    private data class CleanupObservation(val instanceId: UUID, val run: SegmentRun,
        var listenerRemoval: CleanupResult, var release: CleanupResult, var focusReturn: CleanupResult,
        val initializationSettled: Boolean, val stopUnverified: Boolean) {
        val confirmed: Boolean get() = initializationSettled && !stopUnverified &&
            listOf(listenerRemoval, release, focusReturn).all { it.status in setOf(CleanupStatus.NOT_APPLICABLE, CleanupStatus.SUCCEEDED) }
    }
    // Retain failed/unknown original facts; a cleanup exception is never an availability grant.
    private val cleanupObservations = mutableListOf<CleanupObservation>()
    val quiescent: Boolean get() = invocation == null && blockedCleanup == null && !stopUnverified && externalCleanupHolds.isEmpty()
    private fun focusFor(value:Invocation)=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener({change->
            // The request/listener belongs to one invocation. An old focus callback cannot retag a newer run.
            if(invocation===value && !value.terminal.get()) {
                val granted=change==AudioManager.AUDIOFOCUS_GAIN
                mutable.value=mutable.value.copy(focusGranted=granted)
                if(!granted)scope.launch(Dispatchers.Main.immediate){finish(value,SegmentSpeechEventKind.UNKNOWN,"pleos-tts-audio-focus-lost")}
            }
        },handler).build()

    private class Invocation(val run: SegmentRun, val sdk: Any, val listener: Any, val mode: PleosTtsMode,
        val language: SegmentLanguage, val text: String) {
        val terminal = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)
        val stopObserved = CompletableDeferred<Unit>()
        val initializationReported = AtomicBoolean(false)
        val instanceId: UUID = UUID.randomUUID()
        var initializationRequested = false
        var focusRequested = false
        var focusReturned = false
        var focusRequest:AudioFocusRequest?=null
        var initialized = false
        var listenerRegistered = false
        var timeout: Job? = null
        var synthesisRequested = false
        var binding:TtsRunReviewBinding1?=null
        var intentGeneration:Long?=null
        var nativeDone=false
        var doneValid=false
        var forwardedKind:SegmentSpeechEventKind?=null
        var monitor:Job?=null
        var outputDeadline:Long?=null
        var stopRequested=false
        override fun toString() = "PleosTtsInvocation(content=redacted)"
    }

    override suspend fun request(run: SegmentRun, text: String, language: SegmentLanguage): SegmentSpeechAdmission = withContext(Dispatchers.Main.immediate) {
        val reviewed = approval ?: return@withContext SegmentSpeechAdmission.Unavailable("PLEOS_TTS_PROJECT_NOT_REVIEWED")
        if (externalCleanupHolds.isNotEmpty())return@withContext SegmentSpeechAdmission.Unavailable("PLEOS_TTS_PERSISTED_CLEANUP_HOLD")
        if (blockedCleanup != null) return@withContext SegmentSpeechAdmission.Unavailable("PLEOS_TTS_CLEANUP_UNCONFIRMED")
        if (stopUnverified) return@withContext SegmentSpeechAdmission.Unavailable("PLEOS_TTS_PREVIOUS_STOP_UNVERIFIED")
        if (closed || invocation != null) return@withContext SegmentSpeechAdmission.Unavailable("PLEOS_TTS_BUSY")
        require(initializationTimeoutMs in 1_000..60_000)
        if (segmentSha256(text) != run.segmentTextHash) return@withContext SegmentSpeechAdmission.Unavailable("SEGMENT_TEXT_CHANGED")
        if (language !in reviewed.languageEvidence) return@withContext SegmentSpeechAdmission.Unavailable("PLEOS_TTS_LANGUAGE_NOT_VERIFIED")
        if (!appVisible() && reviewed.backgroundEvidence == null) return@withContext SegmentSpeechAdmission.Unavailable("PLEOS_TTS_BACKGROUND_NOT_VERIFIED")
        val permission = refreshPermission()==TtsPermissionState.GRANTED
        mutable.value = mutable.value.copy(projectReviewed = true, permissionGranted = permission)
        if (!permission) return@withContext SegmentSpeechAdmission.Unavailable("PLEOS_TTS_PERMISSION_REQUIRED")
        if (!permitted(run)) return@withContext SegmentSpeechAdmission.Unavailable("OUTPUT_LEASE_OR_ACCESS_CHANGED")
        val originalFact=if(reviewed.mode==PleosTtsMode.ON_DEVICE)facts(run,false) else null
        val reviewScope=scopeReview
        val binding=if(reviewed.mode==PleosTtsMode.ON_DEVICE) {
            val identity=runtimeIdentity?:return@withContext SegmentSpeechAdmission.Unavailable("PLEOS_TTS_RUNTIME_IDENTITY_UNAVAILABLE")
            val reviewedScope=reviewScope?:return@withContext SegmentSpeechAdmission.Unavailable("PLEOS_TTS_ON_DEVICE_SCOPE_UNAVAILABLE")
            val program=programForRun(run)?:return@withContext SegmentSpeechAdmission.Unavailable("PLEOS_TTS_PROGRAM_BINDING_UNAVAILABLE")
            if(originalFact==null || !PleosTtsScopePolicy.staticAllows(reviewedScope,identity,program,run,text,language) ||
                !PleosTtsScopePolicy.currentAllows(reviewedScope,run,originalFact,false))
                return@withContext SegmentSpeechAdmission.Unavailable("PLEOS_TTS_REVIEW_SCOPE_OR_CURRENT_CONDITIONS")
            val now=android.os.SystemClock.elapsedRealtime()
            TtsRunReviewBinding1(reviewedScope.reviewId,reviewedScope.revision,UUID.randomUUID(),run,language,run.segmentTextHash,
                scopeGeneration,originalFact.access.window.generation,originalFact.access.components.generation,
                originalFact.access.components.audio.permissionGeneration,originalFact.access.components.media.permissionGeneration,
                permissionGeneration,now,Math.addExact(now,minOf(initializationTimeoutMs,reviewedScope.limits.initializationTimeoutMs)))
        }else null
        val credentials = if (reviewed.mode != PleosTtsMode.ON_DEVICE) registration() else null
        if (reviewed.mode != PleosTtsMode.ON_DEVICE && credentials == null) return@withContext SegmentSpeechAdmission.Unavailable("PLEOS_TTS_REGISTRATION_UNAVAILABLE")
        try {
            val type = sdkType("ai.pleos.playground.tts.TextToSpeech")
            val modeType = sdkType("ai.pleos.playground.tts.constant.Mode")
            val modes = modeType.enumConstants
                ?: return@withContext SegmentSpeechAdmission.Unavailable("PLEOS_TTS_MODE_NOT_AN_ENUM")
            val mode = modes.single { (it as? Enum<*>)?.name == reviewed.mode.name }
            val sdk = type.getConstructor(Context::class.java, modeType).newInstance(context.applicationContext, mode)
            lateinit var original: Invocation
            val listenerType = sdkType("ai.pleos.playground.tts.listener.EventListener")
            val eventProxy = listenerProxy(listenerType) { name, _ -> scope.launch(Dispatchers.Main.immediate) {
                when (name) {
                    "onStart" -> if (active(original) && original.synthesisRequested) {
                        if(currentOutput(original))observations.trySend(SegmentSpeechEvent(UUID.randomUUID(),original.run,SegmentSpeechEventKind.STARTED,reviewed.instanceCorrelationEvidence))
                        else finish(original,SegmentSpeechEventKind.UNKNOWN,"pleos-tts-start-scope-lost")
                    }
                    "onDone" -> finish(original, SegmentSpeechEventKind.COMPLETED, reviewed.instanceCorrelationEvidence)
                    "onStop" -> {
                        original.stopObserved.complete(Unit)
                        if (original.stopped.compareAndSet(false, true)) {
                            if (!original.terminal.get()) finish(original, SegmentSpeechEventKind.STOPPED, reviewed.stopOrderingEvidence)
                            else observations.trySend(SegmentSpeechEvent(UUID.randomUUID(), original.run, SegmentSpeechEventKind.STOPPED, reviewed.stopOrderingEvidence))
                        }
                    }
                    "onError" -> finish(original, SegmentSpeechEventKind.ERROR, "pleos-tts-sdk-reported-error")
                    // onReady and RMS are not output completion.
                }
            } }
            original = Invocation(run, sdk, eventProxy, reviewed.mode, language, text)
            original.binding=binding;original.intentGeneration=originalFact?.outputIntentGeneration
            invocation = original
            original.focusRequested = true
            val focus=focusFor(original);original.focusRequest=focus
            val granted = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            mutable.value = mutable.value.copy(sdkPresent = true, focusGranted = granted, initialized = false, serverConnected = false, reason = null)
            if (!granted) { dispose(original); return@withContext SegmentSpeechAdmission.Unavailable("PLEOS_TTS_AUDIO_FOCUS_DENIED") }
            val initializationType = sdkType("ai.pleos.playground.tts.listener.OnInitializeListener")
            val initialization = listenerProxy(initializationType) { name, _ -> scope.launch(Dispatchers.Main.immediate) {
                if (name !in setOf("onSuccess", "onFailure") ||
                    !original.initializationReported.compareAndSet(false, true)) return@launch
                original.initialized = name == "onSuccess"
                if (!active(original)) {
                    // A late initialization cannot start output. Retire only its original instance.
                    if (invocation === original || blockedCleanup === original) dispose(original)
                    return@launch
                }
                if (name == "onSuccess") {
                    mutable.value = mutable.value.copy(initialized = true)
                    initialized(original, credentials)
                } else finish(original, SegmentSpeechEventKind.ERROR, "pleos-tts-initialization-failed")
            } }
            original.timeout = scope.launch(Dispatchers.Main.immediate) {
                delay(binding?.let{(it.initializationDeadlineElapsedMs-android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0)}?:initializationTimeoutMs)
                if (active(original) && !original.synthesisRequested)
                    finish(original, SegmentSpeechEventKind.ERROR, "pleos-tts-initialization-deadline")
            }
            original.monitor=scope.launch(Dispatchers.Main.immediate){
                val interval=reviewScope?.limits?.permissionRefreshIntervalMs?:1_000L
                while(active(original)){delay(interval);recheckActive()}
            }
            original.initializationRequested = true
            type.getMethod("initialize", initializationType).invoke(sdk, initialization)
            SegmentSpeechAdmission.Preparing
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { invocation?.takeIf { it.run == run }?.let { stop(it.run) } }
            throw cancelled
        } catch (_: Exception) {
            invocation?.takeIf { it.run == run }?.let { dispose(it) }
            mutable.value = mutable.value.copy(reason = "PLEOS_TTS_SDK_OR_SERVICE_UNAVAILABLE")
            SegmentSpeechAdmission.Unavailable("PLEOS_TTS_SDK_OR_SERVICE_UNAVAILABLE")
        }
    }

    /** Actual focus belongs to this exact live SDK invocation, never to a prior run or availability flag. */
    fun ownsOutputFocus(run: SegmentRun): Boolean = invocation?.let {
        it.run == run && active(it) && mutable.value.focusGranted
    } == true

    private fun active(value: Invocation) = !closed && invocation === value && !value.terminal.get()
    /** Every asynchronous permission callback must return to this same initialized invocation. */
    private suspend fun currentOutput(value: Invocation): Boolean {
        if (!active(value) || !value.initialized || !ownsOutputFocus(value.run)) return false
        val now=android.os.SystemClock.elapsedRealtime()
        if(!value.synthesisRequested && value.binding?.let{now>it.initializationDeadlineElapsedMs}==true || value.outputDeadline?.let{now>it}==true)return false
        if(value.mode==PleosTtsMode.ON_DEVICE) {
            val binding=value.binding?:return false;val reviewed=scopeReview?:return false
            val fact=facts(value.run,true)?:return false
            return active(value) && fact.outputIntentGeneration==value.intentGeneration && PleosTtsScopePolicy.currentAllows(reviewed,value.run,fact,true,binding)
        }
        if (!permitted(value.run) || !active(value) || !ownsOutputFocus(value.run)) return false
        return outputPermitted(value.run) && active(value) && ownsOutputFocus(value.run)
    }
    private suspend fun initialized(value: Invocation, credentials: PleosTtsRegistration?) {
        if (!currentOutput(value)) { finish(value, SegmentSpeechEventKind.UNKNOWN, "pleos-tts-access-changed-before-output"); return }
        try {
            // SDK 3.3.1 initializes its delegate asynchronously; listener methods require onSuccess.
            val listenerType = sdkType("ai.pleos.playground.tts.listener.EventListener")
            value.sdk.javaClass.getMethod("addEventListener", listenerType).invoke(value.sdk, value.listener)
            value.listenerRegistered = true
            value.sdk.javaClass.getMethod("setAudioAttributes", AudioAttributes::class.java).invoke(value.sdk,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            value.sdk.javaClass.getMethod("setLocale", Locale::class.java).invoke(value.sdk, if (value.language == SegmentLanguage.EN) Locale.US else Locale.KOREA)
            if (value.mode == PleosTtsMode.ON_DEVICE) synthesize(value)
            else {
                val connectionType = sdkType("ai.pleos.playground.tts.listener.OnServerConnectionListener")
                val callback = listenerProxy(connectionType) { name, _ -> scope.launch(Dispatchers.Main.immediate) {
                    if (!active(value)) return@launch
                    if (name == "onConnected") {
                        mutable.value = mutable.value.copy(serverConnected = true)
                        synthesize(value)
                    } else if (name == "onFailed") finish(value, SegmentSpeechEventKind.ERROR, "pleos-tts-registration-or-connection-failed")
                } }
                val registration = requireNotNull(credentials)
                value.sdk.javaClass.getMethod("registerApp", String::class.java, String::class.java, connectionType)
                    .invoke(value.sdk, registration.clientId, registration.clientSecret, callback)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { finish(value, SegmentSpeechEventKind.ERROR, "pleos-tts-configuration-unavailable") }
    }
    private suspend fun synthesize(value: Invocation) {
        if (!currentOutput(value) || !value.listenerRegistered ||
            !appVisible() && approval?.backgroundEvidence == null) {
            finish(value, SegmentSpeechEventKind.UNKNOWN, "pleos-tts-access-changed-before-speak"); return
        }
        try {
            value.timeout?.cancel(); value.timeout = null
            value.synthesisRequested = true
            scopeReview?.limits?.outputTimeoutMs?.let{limit->
                value.outputDeadline=Math.addExact(android.os.SystemClock.elapsedRealtime(),limit)
                value.timeout=scope.launch(Dispatchers.Main.immediate){delay(limit);if(active(value))finish(value,SegmentSpeechEventKind.UNKNOWN,"pleos-tts-output-deadline")}
            }
            value.sdk.javaClass.getMethod("speak", String::class.java, java.lang.Boolean.TYPE).invoke(value.sdk, value.text, true)
        } catch (_: Exception) { finish(value, SegmentSpeechEventKind.ERROR, "pleos-tts-speak-unavailable") }
    }
    private suspend fun finish(value: Invocation, kind: SegmentSpeechEventKind, evidence: String) {
        if(invocation!==value || value.terminal.get())return
        var accepted=kind
        if(kind==SegmentSpeechEventKind.COMPLETED) {
            value.nativeDone=true
            value.doneValid=value.synthesisRequested && value.listenerRegistered && currentOutput(value) &&
                value.outputDeadline?.let{android.os.SystemClock.elapsedRealtime()<=it}!=false
            if(!value.doneValid)accepted=SegmentSpeechEventKind.UNKNOWN
        }
        if(invocation!==value || !value.terminal.compareAndSet(false,true))return
        value.forwardedKind=accepted
        if(accepted==SegmentSpeechEventKind.COMPLETED) {
            // Capture DONE while the original focus is held; cleanup cannot erase that factual event.
            dispose(value)
            val cleanup=cleanupObservations.lastOrNull{it.instanceId==value.instanceId}
            val continuation=if(cleanup?.confirmed==true)OutputContinuationState.READY else if(cleanup?.let{listOf(it.listenerRemoval,it.release,it.focusReturn).any{v->v.status==CleanupStatus.FAILED}}==true)
                OutputContinuationState.BLOCKED else OutputContinuationState.UNKNOWN
            val event=SegmentSpeechEvent(UUID.randomUUID(),value.run,accepted,evidence,continuation)
            value.binding?.let{binding->completedBindings[event.eventId]=CompletedBinding(event,binding,requireNotNull(value.intentGeneration));while(completedBindings.size>32)completedBindings.remove(completedBindings.keys.first())}
            observations.trySend(event)
        }else {
            scopeGeneration++
            scope.launch(Dispatchers.Main.immediate){onScopeLost(value.run,evidence)}
            observations.trySend(SegmentSpeechEvent(UUID.randomUUID(),value.run,accepted,evidence))
            stop(value.run)
        }
    }
    override suspend fun stop(run: SegmentRun) = withContext(Dispatchers.Main.immediate) {
        val original = invocation?.takeIf { it.run == run } ?: return@withContext
        if(original.stopRequested)return@withContext
        original.stopRequested=true
        original.terminal.set(true)
        original.timeout?.cancel()
        try {
            // Before synthesis there is no physical output to stop and no onStop to claim.
            // In particular SDK.stop() is not legal before initialize/onSuccess.
            if (original.synthesisRequested) {
                original.sdk.javaClass.getMethod("stop").invoke(original.sdk)
                if (withTimeoutOrNull(scopeReview?.limits?.stopCallbackWaitMs?:1_000) { original.stopObserved.await(); true } != true) {
                    stopUnverified = true
                    mutable.value = mutable.value.copy(reason = "PLEOS_TTS_STOP_COMPLETION_UNVERIFIED")
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) {
            stopUnverified = true
            mutable.value = mutable.value.copy(reason = "PLEOS_TTS_STOP_UNAVAILABLE")
        } finally { dispose(original) }
    }
    private fun cleanupFailure(error: Exception): CleanupResult {
        var cause: Throwable = error
        while (cause is java.lang.reflect.InvocationTargetException && cause.targetException !== cause)
            cause = cause.targetException ?: break
        return CleanupResult(CleanupStatus.FAILED, cause.javaClass.name)
    }
    private fun dispose(value: Invocation) {
        value.timeout?.cancel(); value.timeout = null;value.monitor?.cancel();value.monitor=null
        if (invocation !== value && blockedCleanup !== value) return
        value.terminal.set(true)
        val observation = CleanupObservation(value.instanceId, value.run,
            CleanupResult(if (value.listenerRegistered) CleanupStatus.NOT_ATTEMPTED else CleanupStatus.NOT_APPLICABLE),
            CleanupResult(CleanupStatus.NOT_ATTEMPTED),
            CleanupResult(if (!value.focusRequested) CleanupStatus.NOT_APPLICABLE else if (value.focusReturned) CleanupStatus.SUCCEEDED else CleanupStatus.NOT_ATTEMPTED),
            !value.initializationRequested || value.initializationReported.get(), stopUnverified)
        cleanupObservations += observation
        while(cleanupObservations.size>16)cleanupObservations.removeAt(0)
        if (value.listenerRegistered) {
            observation.listenerRemoval = try {
                value.sdk.javaClass.getMethod("removeEventListener", sdkType("ai.pleos.playground.tts.listener.EventListener")).invoke(value.sdk, value.listener)
                value.listenerRegistered = false
                CleanupResult(CleanupStatus.SUCCEEDED)
            } catch (error: Exception) { cleanupFailure(error) }
        }
        observation.release = try {
            value.sdk.javaClass.getMethod("release").invoke(value.sdk)
            // Returning before an outstanding initialize callback cannot prove resource quiescence.
            CleanupResult(if (observation.initializationSettled) CleanupStatus.SUCCEEDED else CleanupStatus.UNCONFIRMED)
        } catch (error: Exception) { cleanupFailure(error) }
        if (value.focusRequested && !value.focusReturned) {
            observation.focusReturn = try {
                when (audio.abandonAudioFocusRequest(requireNotNull(value.focusRequest))) {
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> { value.focusReturned = true; CleanupResult(CleanupStatus.SUCCEEDED) }
                    AudioManager.AUDIOFOCUS_REQUEST_FAILED -> CleanupResult(CleanupStatus.FAILED)
                    else -> CleanupResult(CleanupStatus.UNCONFIRMED)
                }
            } catch (error: Exception) { cleanupFailure(error) }
        }
        invocation = null
        blockedCleanup = if (observation.confirmed) null else value
        mutable.value = mutable.value.copy(
            initialized = if (observation.release.status == CleanupStatus.SUCCEEDED) false else mutable.value.initialized,
            serverConnected = if (observation.release.status == CleanupStatus.SUCCEEDED) false else mutable.value.serverConnected,
            focusGranted = if (value.focusReturned) false else mutable.value.focusGranted,
            reason = if (observation.confirmed) mutable.value.reason else "PLEOS_TTS_CLEANUP_UNCONFIRMED")
        value.binding?.let{binding->
            outputFacts.value=(outputFacts.value.filterNot{it.instanceId==value.instanceId}+TtsOutputObservation1(binding.bindingToken,value.run.runId,value.run.requestId,
                value.instanceId,binding.scopeGeneration,value.nativeDone,value.doneValid,value.forwardedKind,
                TtsCleanupStatus.valueOf(observation.listenerRemoval.status.name),TtsCleanupStatus.valueOf(observation.release.status.name),
                TtsCleanupStatus.valueOf(observation.focusReturn.status.name),observation.initializationSettled,value.stopObserved.isCompleted,
                observation.confirmed,if(observation.confirmed)null else "PLEOS_TTS_CLEANUP_UNCONFIRMED")).takeLast(16)
        }
    }
    suspend fun close() = withContext(Dispatchers.Main.immediate) {
        closed = true
        invocation?.let { stop(it.run) }
        observations.close()
    }
    private fun sdkType(name:String):Class<*> = Class.forName(name,true,context.classLoader)
    private fun refreshPermission():TtsPermissionState {
        val value=try{if(context.checkSelfPermission("pleos.car.permission.TTS_SERVICE")==PackageManager.PERMISSION_GRANTED)TtsPermissionState.GRANTED else TtsPermissionState.DENIED}catch(_:Exception){TtsPermissionState.UNKNOWN}
        if(value!=permissionState){permissionState=value;permissionGeneration++}
        return value
    }
    private suspend fun facts(run:SegmentRun,focusCurrent:Boolean):TtsCurrentScopeFact1? = currentFacts(run)?.copy(
        ttsPermission=refreshPermission(),ttsPermissionGeneration=permissionGeneration,scopeGeneration=scopeGeneration,focusCurrent=focusCurrent)
    suspend fun recheckCurrent()=withContext(Dispatchers.Main.immediate){refreshPermission();recheckActive()}
    fun reviewedManifest(hash:String):Boolean=scopeReview?.let{it.reviewState==TtsReviewState.REVIEWED && hash in it.allowedManifestHashes}==true
    /** Read-only reviewed timing for opt-in product verification, never an approval setter. */
    fun reviewedOutputLimits(hash:String):TtsOutputLimits1?=scopeReview?.takeIf{reviewedManifest(hash)}?.limits?.copy()
    private suspend fun recheckActive(){val value=invocation?:return;if(active(value) && value.mode==PleosTtsMode.ON_DEVICE) {
        val reviewed=scopeReview;val binding=value.binding;val fact=facts(value.run,ownsOutputFocus(value.run))
        if(reviewed==null || binding==null || fact==null || fact.outputIntentGeneration!=value.intentGeneration ||
            !PleosTtsScopePolicy.currentAllows(reviewed,value.run,fact,true,binding))finish(value,SegmentSpeechEventKind.UNKNOWN,"pleos-tts-current-scope-lost")
    }}
    /** Consumer validation uses original accepted DONE and current owner/lease/source, not post-abandon focus. */
    suspend fun completionDisposition(event:SegmentSpeechEvent):CompletionDisposition1 = withContext(Dispatchers.Main.immediate) {
        val original=completedBindings[event.eventId]?.takeIf{it.event==event}?:return@withContext CompletionDisposition1.unavailable("TTS_ORIGINAL_DONE_UNAVAILABLE")
        val reviewed=scopeReview?:return@withContext CompletionDisposition1.unavailable("TTS_REVIEW_SCOPE_UNAVAILABLE")
        val fact=facts(event.run,false)?:return@withContext CompletionDisposition1.unavailable("TTS_CURRENT_SCOPE_UNAVAILABLE")
        if(original.intentGeneration!=fact.outputIntentGeneration || !PleosTtsScopePolicy.currentAllows(reviewed,event.run,fact,false,original.binding))
            return@withContext CompletionDisposition1.unavailable("TTS_DONE_CURRENT_FENCE_CHANGED")
        CompletionDisposition1(true,original.binding.bindingToken,original.binding.scopeGeneration,event.outputContinuation?:OutputContinuationState.UNKNOWN,
            if(event.outputContinuation==OutputContinuationState.READY)null else "TTS_CLEANUP_HOLD")
    }
    fun retainCleanupHold(value:CompletionContinuation1){if(value.state!=OutputContinuationState.READY)externalCleanupHolds[value.completedEventId]=value}
    suspend fun recoverCleanup(value:CompletionContinuation1):OutputContinuationState=withContext(Dispatchers.Main.immediate) {
        if(value.state==OutputContinuationState.READY)return@withContext OutputContinuationState.READY
        val original=blockedCleanup?.takeIf{it.run==value.completedRun}?:run {
            val accepted=completedBindings[value.completedEventId]?.event?.run==value.completedRun
            val cleaned=cleanupObservations.any{it.run==value.completedRun && it.confirmed}
            return@withContext if(accepted && cleaned)OutputContinuationState.READY else OutputContinuationState.UNKNOWN
        }
        if(original.stopObserved.isCompleted)stopUnverified=false
        dispose(original)
        if(blockedCleanup==null && !stopUnverified){externalCleanupHolds.remove(value.completedEventId);OutputContinuationState.READY}else OutputContinuationState.UNKNOWN
    }
    private fun listenerProxy(type: Class<*>, callback: (String, Array<out Any?>?) -> Unit): Any = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
        when (method.name) {
            "toString" -> "PleosTtsListener(content=redacted)"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> { callback(method.name, args); null }
        }
    }
}

private fun requireTtsEvidence(value: String) { require(value.codePointCount(0, value.length) in 1..160) }
