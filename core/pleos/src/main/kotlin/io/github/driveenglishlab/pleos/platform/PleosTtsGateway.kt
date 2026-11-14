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
    val backgroundEvidence: String? = null) {
    init {
        require(sdkVersion == "3.3.1")
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
    private val approval: PleosTtsProjectApproval? = null,
    private val registration: suspend () -> PleosTtsRegistration? = { null },
    private val permitted: suspend (SegmentRun) -> Boolean = { false },
    private val appVisible: () -> Boolean = { false }, private val initializationTimeoutMs: Long = 15_000) : SegmentSpeechPort {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(PleosTtsReadiness())
    val state = mutable.asStateFlow()
    private val observations = Channel<SegmentSpeechEvent>(Channel.UNLIMITED)
    // News and auxiliary coordinators share this gateway. Broadcast original immutable events;
    // receiveAsFlow alone would let one consumer steal and discard another run's completion.
    override val events = observations.receiveAsFlow().shareIn(scope, SharingStarted.Eagerly, replay = 0)
    private var invocation: Invocation? = null
    private var closed = false
    private var stopUnverified = false
    val quiescent: Boolean get() = invocation == null && !stopUnverified
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener({ change ->
            val granted = change == AudioManager.AUDIOFOCUS_GAIN
            mutable.value = mutable.value.copy(focusGranted = granted)
            if (!granted) invocation?.let { original ->
                // The runtime invalidates generation/lease and persists before it calls stop().
                finish(original, SegmentSpeechEventKind.UNKNOWN, "pleos-tts-audio-focus-lost")
            }
            // Focus gain changes availability only; it never requests speech.
        }, handler).build()

    private class Invocation(val run: SegmentRun, val sdk: Any, val listener: Any, val mode: PleosTtsMode,
        val language: SegmentLanguage, val text: String) {
        val terminal = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)
        val stopObserved = CompletableDeferred<Unit>()
        var timeout: Job? = null
        var synthesisRequested = false
        override fun toString() = "PleosTtsInvocation(content=redacted)"
    }

    override suspend fun request(run: SegmentRun, text: String, language: SegmentLanguage): SegmentSpeechAdmission = withContext(Dispatchers.Main.immediate) {
        val reviewed = approval ?: return@withContext SegmentSpeechAdmission.Unavailable("PLEOS_TTS_PROJECT_NOT_REVIEWED")
        if (stopUnverified) return@withContext SegmentSpeechAdmission.Unavailable("PLEOS_TTS_PREVIOUS_STOP_UNVERIFIED")
        if (closed || invocation != null) return@withContext SegmentSpeechAdmission.Unavailable("PLEOS_TTS_BUSY")
        require(initializationTimeoutMs in 1_000..60_000)
        if (segmentSha256(text) != run.segmentTextHash) return@withContext SegmentSpeechAdmission.Unavailable("SEGMENT_TEXT_CHANGED")
        if (language !in reviewed.languageEvidence) return@withContext SegmentSpeechAdmission.Unavailable("PLEOS_TTS_LANGUAGE_NOT_VERIFIED")
        if (!appVisible() && reviewed.backgroundEvidence == null) return@withContext SegmentSpeechAdmission.Unavailable("PLEOS_TTS_BACKGROUND_NOT_VERIFIED")
        val permission = context.checkSelfPermission("pleos.car.permission.TTS_SERVICE") == PackageManager.PERMISSION_GRANTED
        mutable.value = mutable.value.copy(projectReviewed = true, permissionGranted = permission)
        if (!permission) return@withContext SegmentSpeechAdmission.Unavailable("PLEOS_TTS_PERMISSION_REQUIRED")
        if (!permitted(run)) return@withContext SegmentSpeechAdmission.Unavailable("OUTPUT_LEASE_OR_ACCESS_CHANGED")
        val credentials = if (reviewed.mode != PleosTtsMode.ON_DEVICE) registration() else null
        if (reviewed.mode != PleosTtsMode.ON_DEVICE && credentials == null) return@withContext SegmentSpeechAdmission.Unavailable("PLEOS_TTS_REGISTRATION_UNAVAILABLE")
        try {
            val type = Class.forName("ai.pleos.playground.tts.TextToSpeech")
            val modeType = Class.forName("ai.pleos.playground.tts.constant.Mode")
            val modes = modeType.enumConstants
                ?: return@withContext SegmentSpeechAdmission.Unavailable("PLEOS_TTS_MODE_NOT_AN_ENUM")
            val mode = modes.single { (it as? Enum<*>)?.name == reviewed.mode.name }
            val sdk = type.getConstructor(Context::class.java, modeType).newInstance(context.applicationContext, mode)
            lateinit var original: Invocation
            val listenerType = Class.forName("ai.pleos.playground.tts.listener.EventListener")
            val eventProxy = listenerProxy(listenerType) { name, _ -> scope.launch(Dispatchers.Main.immediate) {
                when (name) {
                    "onStart" -> if (active(original)) observations.trySend(SegmentSpeechEvent(UUID.randomUUID(), original.run,
                        SegmentSpeechEventKind.STARTED, reviewed.instanceCorrelationEvidence))
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
            invocation = original
            val granted = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            mutable.value = mutable.value.copy(sdkPresent = true, focusGranted = granted, initialized = false, serverConnected = false, reason = null)
            if (!granted) { dispose(original); return@withContext SegmentSpeechAdmission.Unavailable("PLEOS_TTS_AUDIO_FOCUS_DENIED") }
            type.getMethod("addEventListener", listenerType).invoke(sdk, eventProxy)
            val initializationType = Class.forName("ai.pleos.playground.tts.listener.OnInitializeListener")
            val initialization = listenerProxy(initializationType) { name, _ -> scope.launch(Dispatchers.Main.immediate) {
                if (!active(original)) return@launch
                if (name == "onSuccess") {
                    mutable.value = mutable.value.copy(initialized = true)
                    initialized(original, credentials)
                } else if (name == "onFailure") finish(original, SegmentSpeechEventKind.ERROR, "pleos-tts-initialization-failed")
            } }
            original.timeout = scope.launch(Dispatchers.Main.immediate) {
                delay(initializationTimeoutMs)
                if (active(original) && (!mutable.value.initialized || original.mode != PleosTtsMode.ON_DEVICE && !mutable.value.serverConnected))
                    finish(original, SegmentSpeechEventKind.ERROR, "pleos-tts-initialization-deadline")
            }
            type.getMethod("initialize", initializationType).invoke(sdk, initialization)
            SegmentSpeechAdmission.Preparing
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) {
            invocation?.takeIf { it.run == run }?.let { dispose(it) }
            mutable.value = mutable.value.copy(reason = "PLEOS_TTS_SDK_OR_SERVICE_UNAVAILABLE")
            SegmentSpeechAdmission.Unavailable("PLEOS_TTS_SDK_OR_SERVICE_UNAVAILABLE")
        }
    }

    private fun active(value: Invocation) = !closed && invocation === value && !value.terminal.get()
    private suspend fun initialized(value: Invocation, credentials: PleosTtsRegistration?) {
        if (!active(value) || !permitted(value.run)) { finish(value, SegmentSpeechEventKind.UNKNOWN, "pleos-tts-access-changed-before-output"); return }
        try {
            value.sdk.javaClass.getMethod("setAudioAttributes", AudioAttributes::class.java).invoke(value.sdk,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            value.sdk.javaClass.getMethod("setLocale", Locale::class.java).invoke(value.sdk, if (value.language == SegmentLanguage.EN) Locale.US else Locale.KOREA)
            if (value.mode == PleosTtsMode.ON_DEVICE) synthesize(value)
            else {
                val connectionType = Class.forName("ai.pleos.playground.tts.listener.OnServerConnectionListener")
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
        if (!active(value) || !mutable.value.focusGranted || !permitted(value.run) ||
            !appVisible() && approval?.backgroundEvidence == null) {
            finish(value, SegmentSpeechEventKind.UNKNOWN, "pleos-tts-access-changed-before-speak"); return
        }
        try {
            value.timeout?.cancel(); value.timeout = null
            value.synthesisRequested = true
            value.sdk.javaClass.getMethod("speak", String::class.java, java.lang.Boolean.TYPE).invoke(value.sdk, value.text, true)
        } catch (_: Exception) { finish(value, SegmentSpeechEventKind.ERROR, "pleos-tts-speak-unavailable") }
    }
    private fun finish(value: Invocation, kind: SegmentSpeechEventKind, evidence: String) {
        if (invocation !== value || !value.terminal.compareAndSet(false, true)) return
        // The listener object is bound to one reviewed isolated SDK instance and immutable run,
        // never to a mutable 'current request' lookup. No callback is retagged as a newer token.
        if (kind == SegmentSpeechEventKind.COMPLETED) dispose(value)
        observations.trySend(SegmentSpeechEvent(UUID.randomUUID(), value.run, kind, evidence))
    }
    override suspend fun stop(run: SegmentRun) = withContext(Dispatchers.Main.immediate) {
        val original = invocation?.takeIf { it.run == run } ?: return@withContext
        original.terminal.set(true)
        original.timeout?.cancel()
        try {
            original.sdk.javaClass.getMethod("stop").invoke(original.sdk)
            if (original.synthesisRequested && withTimeoutOrNull(1_000) { original.stopObserved.await(); true } != true) {
                stopUnverified = true
                mutable.value = mutable.value.copy(reason = "PLEOS_TTS_STOP_COMPLETION_UNVERIFIED")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) {
            stopUnverified = true
            mutable.value = mutable.value.copy(reason = "PLEOS_TTS_STOP_UNAVAILABLE")
        } finally { dispose(original) }
    }
    private fun dispose(value: Invocation) {
        value.timeout?.cancel(); value.timeout = null
        if (invocation !== value) return
        invocation = null
        try { value.sdk.javaClass.getMethod("removeEventListener", Class.forName("ai.pleos.playground.tts.listener.EventListener")).invoke(value.sdk, value.listener) } catch (_: Exception) { }
        try { value.sdk.javaClass.getMethod("release").invoke(value.sdk) } catch (_: Exception) { }
        audio.abandonAudioFocusRequest(focus)
        mutable.value = mutable.value.copy(initialized = false, serverConnected = false, focusGranted = false)
    }
    suspend fun close() = withContext(Dispatchers.Main.immediate) {
        closed = true
        invocation?.let { stop(it.run) }
        observations.close()
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
