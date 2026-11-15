package io.github.driveenglishlab.pleos.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import io.github.driveenglishlab.pleos.domain.*
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small injectable public-ABI boundary. Test implementations never invoke an SDK/provider. */
internal interface ParkedSttEngine {
    fun initialize(success: () -> Unit, failure: (String) -> Unit)
    fun add(listener: ResultListener)
    fun locale()
    fun request()
    fun stop()
    fun remove(listener: ResultListener)
    fun release()
}

internal interface ParkedSttRuntime {
    fun assertEventLane()
    fun now(): Long
    fun post(action: () -> Unit)
    fun at(deadline: Long, action: () -> Unit)
    fun sdk(action: () -> Unit)
    fun permissions(): Boolean
    fun focus(changed: (Boolean) -> Unit): Boolean
    fun abandonFocus()
    var quarantined: Boolean
    var recoveryReady: Boolean
}

/** Process-wide single lane: a blocked binder never causes a replacement executor or instance. */
private object PleosSttLane {
    val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "pleos-stt-sdk").apply { isDaemon = true } }
    @Volatile var quarantined = false
    @Volatile var recoveryReady = false
}

private class AndroidParkedSttRuntime(private val context: Context) : ParkedSttRuntime {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null
    override var quarantined: Boolean
        get() = PleosSttLane.quarantined
        set(value) { PleosSttLane.quarantined = value }
    override var recoveryReady: Boolean
        get() = PleosSttLane.recoveryReady
        set(value) { PleosSttLane.recoveryReady = value }
    override fun assertEventLane() { check(Looper.myLooper() == Looper.getMainLooper()) }
    override fun now() = SystemClock.elapsedRealtime()
    override fun post(action: () -> Unit) { handler.post { action() } }
    override fun at(deadline: Long, action: () -> Unit) {
        handler.postDelayed({ action() }, (deadline - now()).coerceAtLeast(0))
    }
    override fun sdk(action: () -> Unit) { PleosSttLane.executor.execute(action) }
    override fun permissions() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission("pleos.car.permission.STT_SERVICE") == PackageManager.PERMISSION_GRANTED
    override fun focus(changed: (Boolean) -> Unit): Boolean {
        val value = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ changed(it == AudioManager.AUDIOFOCUS_GAIN) }, handler).build()
        request = value
        return audio.requestAudioFocus(value) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
    override fun abandonFocus() { request?.let(audio::abandonAudioFocusRequest); request = null }
}

private class PublicPleosStt(context: Context) : ParkedSttEngine {
    private val sdk = SpeechToText(context, Mode.ON_DEVICE) // Never use the SDK's HYBRID default.
    override fun initialize(success: () -> Unit, failure: (String) -> Unit) = sdk.initialize(object : OnInitializeListener {
        override fun onSuccess() = success()
        override fun onFailure(errorType: ErrorType) = failure(errorType.name)
    })
    override fun add(listener: ResultListener) = sdk.addListener(listener)
    override fun locale() = sdk.setLocale(Locale.US)
    override fun request() = sdk.request()
    override fun stop() = sdk.stop()
    override fun remove(listener: ResultListener) = sdk.removeListener(listener)
    override fun release() = sdk.release()
}

/** T036 N01–N12. Main/event lane owns logical state; only the single SDK lane performs service I/O.
 * No partial/raw audio is retained. A normal end retires mic resources, not the E1 logical capture.
 * A process-wide uncertain cleanup is deliberately not reset by initialize/new instance/focus gain.
 * The current predicate must read thread-safe immutable/current snapshots: it is also checked
 * at the SDK request boundary and callback origin, before the main/event-lane check.
 */
class PleosSttGateway internal constructor(
    private val approval: ParkedSpeechRuntimeApproval?,
    private val current: (CaptureLease) -> Boolean,
    private val emit: (DirectSpeechEvent) -> Unit,
    private val runtime: ParkedSttRuntime,
    private val factory: () -> ParkedSttEngine,
) : ParkedSpeechInputPort {
    constructor(context: Context, approval: ParkedSpeechRuntimeApproval? = null,
        current: (CaptureLease) -> Boolean = { false }, emit: (DirectSpeechEvent) -> Unit) :
        this(approval, current, emit, AndroidParkedSttRuntime(context.applicationContext),
            { PublicPleosStt(context.applicationContext) })

    private val mutable = MutableStateFlow(ParkedSpeechInputReadiness())
    override val state = mutable.asStateFlow()
    @Volatile private var closed = false
    @Volatile private var focusOwner: OwnerContext? = null
    @Volatile private var focusGeneration = 0L
    @Volatile private var captureGeneration = 0L
    @Volatile private var run: Run? = null
    override val currentFocusGeneration get() = focusGeneration
    override val currentCaptureGeneration get() = captureGeneration

    private class Run(val lease: CaptureLease, val generation: Long, val focus: Long, val began: Long) {
        val deadline = began + 60_000
        val initDeadline = began + 5_000
        @Volatile var settleDeadline: Long? = null
        @Volatile var retired = false
        @Volatile var engine: ParkedSttEngine? = null
        @Volatile var constructionAttempted = false
        @Volatile var registrationAttempted = false
        @Volatile var registered = false
        @Volatile var requested = false
        @Volatile var initInvoked = false
        var initSettled = false
        var initialized = false
        var finish = false
        @Volatile var stop = false
        @Volatile var ended = false
        var stopCalled = false // SDK lane only; do not issue stop twice across request/finish order.
        var stopReturned = false
        var removeReturned = false
        var releaseReturned = false
        var cleaning = false
        @Volatile var clean = false
        @Volatile var delivered = false
        var text: String? = null
        lateinit var listener: ResultListener
    }

    override fun initialize() {
        runtime.assertEventLane()
        if (closed) return
        mutable.value = mutable.value.copy(approved = approval != null,
            processingNotice = approval?.processingNotice, cleanupUnknown = runtime.quarantined)
    }

    override fun acceptsEvent(owner: OwnerContext?, generation: Long): Boolean =
        !closed && generation == focusGeneration && (owner == null || owner == focusOwner)

    override fun acquireFocus(owner: OwnerContext): Boolean {
        runtime.assertEventLane()
        if (closed || !mutable.value.approved || !permissions()) return false
        // Explicit user acquisition only, after the original lane proved cleanup returned safely.
        // This is never set by a fresh gateway/idle/focus-gain/default SDK instance.
        if (runtime.quarantined) {
            if (!runtime.recoveryReady) return false
            runtime.quarantined = false; runtime.recoveryReady = false
            mutable.value = mutable.value.copy(cleanupUnknown = false)
        }
        if (focusOwner == owner && mutable.value.focusGranted) return true
        releaseFocus()
        val generation = ++focusGeneration
        focusOwner = owner
        val granted = try { runtime.focus { gain -> runtime.post {
            if (!closed && generation == focusGeneration && focusOwner == owner) {
                mutable.value = mutable.value.copy(focusGranted = gain)
                if (!gain) {
                    val captureId = run?.lease?.captureId
                    cancel()
                    emit(DirectSpeechEvent.Interrupted(owner, generation, captureId, null))
                }
            }
        } } } catch (_: Exception) { false }
        mutable.value = mutable.value.copy(focusGranted = granted)
        return granted
    }

    override fun start(value: CaptureLease, access: AccessState): Boolean {
        runtime.assertEventLane()
        if (closed || !mutable.value.approved || runtime.quarantined || run != null || !access.canCapture ||
            !permissions() || !mutable.value.focusGranted || focusOwner != value.owner ||
            value.safetyGeneration != access.safety.generation || value.windowGeneration != access.window.generation ||
            value.componentGeneration != access.components.microphone.permissionGeneration || !isCurrent(value)) return false
        val valueRun = Run(value, ++captureGeneration, focusGeneration, runtime.now())
        run = valueRun
        valueRun.listener = listener(valueRun)
        mutable.value = mutable.value.copy(initializing = true, listening = false, finalReady = false)
        runtime.at(valueRun.initDeadline) {
            if (run === valueRun && !valueRun.initialized && !valueRun.retired) fail(valueRun, "stt_initialization_timeout")
        }
        runtime.at(valueRun.deadline) {
            if (run === valueRun && !valueRun.delivered && !valueRun.retired) fail(valueRun, "stt_capture_timeout")
        }
        monitor(valueRun)
        runtime.sdk {
            if (!valueRun.retired && runtime.now() < valueRun.initDeadline) try {
                valueRun.constructionAttempted = true
                val engine = factory()
                valueRun.engine = engine
                if (!valueRun.retired && runtime.now() < valueRun.initDeadline) {
                    valueRun.initInvoked = true
                    engine.initialize(
                    { runtime.post { initialized(valueRun) } },
                    { error -> runtime.post {
                        valueRun.initSettled = true
                        if (valueRun.retired) clean(valueRun) else fail(valueRun, reason(error))
                    } })
                } else runtime.post { fail(valueRun, "stt_initialization_timeout"); clean(valueRun) }
            } catch (_: Exception) { runtime.post {
                // An initialize exception does not prove that asynchronous binding stopped.
                fail(valueRun, "stt_initialization_failed")
            } }
        }
        return true
    }

    private fun live(value: Run): Boolean = run === value && !closed && !value.retired &&
        value.generation == captureGeneration && value.focus == focusGeneration &&
        mutable.value.focusGranted && focusOwner == value.lease.owner && permissions() &&
        withinDeadline(value) && isCurrent(value.lease)

    private fun permissions() = try { runtime.permissions() } catch (_: Exception) { false }
    private fun isCurrent(value: CaptureLease) = try { current(value) } catch (_: Exception) { false }

    private fun withinDeadline(value: Run) = runtime.now() < value.deadline &&
        (value.clean || value.settleDeadline?.let { runtime.now() < it } != false)

    private fun monitor(value: Run) {
        runtime.at(minOf(runtime.now() + 250, value.deadline)) {
            if (run === value && !value.retired && !value.delivered) {
                if (runtime.now() >= value.deadline) fail(value, "stt_capture_timeout")
                else if (!live(value)) fail(value, "stt_context_changed") else monitor(value)
            }
        }
    }

    private fun initialized(value: Run) {
        value.initSettled = true
        if (value.retired) { clean(value); return }
        if (!live(value) || runtime.now() >= value.initDeadline) { fail(value, "stt_context_changed"); return }
        if (value.initialized) return
        value.initialized = true
        // Registration and locale calls stay off the event lane. Recheck again before request.
        runtime.sdk {
            if (!value.retired) try {
                val engine = requireNotNull(value.engine)
                value.registrationAttempted = true
                engine.add(value.listener); value.registered = true
                engine.locale()
                runtime.post {
                    if (!live(value)) fail(value, "stt_context_changed") else runtime.sdk {
                        if (!value.retired && !closed && permissions() && isCurrent(value.lease) &&
                            value.focus == focusGeneration && value.generation == captureGeneration &&
                            runtime.now() < value.initDeadline && withinDeadline(value)) try {
                            value.requested = true
                            engine.request()
                            if (value.stop && !value.ended) sdkStop(value)
                            runtime.post {
                                if (live(value)) mutable.value = mutable.value.copy(initializing = false, listening = !value.ended)
                                else if (!value.retired) fail(value, "stt_context_changed")
                            }
                        } catch (_: Exception) { runtime.post { fail(value, "stt_request_failed") } }
                        else runtime.post { fail(value, "stt_context_changed") }
                    }
                }
            } catch (_: Exception) { runtime.post { fail(value, "stt_configuration_failed") } }
        }
    }

    private fun listener(value: Run) = object : ResultListener {
        override fun onUpdated(stt: String, completed: Boolean) {
            if (!completed) return // Do not enqueue or retain interim text.
            if (!live(value)) {
                runtime.post { fail(value, "stt_context_changed") }
                return
            }
            runtime.post {
                if (value.delivered || value.retired) return@post
                if (!live(value)) { fail(value, "stt_context_changed"); return@post }
                val final = normalizeFinalAnswer(stt)
                if (final == null) { fail(value, "stt_invalid_final"); return@post }
                if (value.text != null && value.text != final) { fail(value, "stt_ambiguous_final"); return@post }
                value.text = final
                if (!value.ended) stop(value) else clean(value)
                deliver(value)
            }
        }
        override fun onEndedRecognition() {
            val observedAt = runtime.now()
            runtime.post {
            value.ended = true // A late end records only the original run's physical cleanup fact.
            if (value.retired) { clean(value); return@post }
            if (!live(value)) { fail(value, "stt_context_changed"); return@post }
            settling(value, observedAt)
            mutable.value = mutable.value.copy(listening = false)
            if (value.text != null) clean(value)
        } }
        override fun onError(errorType: ErrorType) { runtime.post { fail(value, reason(errorType.name)) } }
        override fun onStartedRecognition() = Unit
        override fun onReady() = Unit
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
    }

    override fun finish(value: CaptureLease) {
        runtime.assertEventLane()
        val original = run?.takeIf { it.lease == value && !it.retired && !it.delivered } ?: return
        if (!live(original)) { fail(original, "stt_context_changed"); return }
        original.finish = true
        if (!original.ended) stop(original)
        deliver(original)
    }

    private fun settling(value: Run, observedAt: Long = runtime.now()) {
        if (value.settleDeadline != null) return
        val deadline = minOf(observedAt + 2_000, value.deadline)
        value.settleDeadline = deadline
        runtime.at(deadline) {
            if (run === value && !value.retired && !value.clean && !value.delivered)
                fail(value, "stt_settle_timeout")
        }
    }

    private fun stop(value: Run) {
        if (value.stop || value.ended) return
        value.stop = true
        settling(value)
        runtime.sdk {
            if (!value.clean) try { sdkStop(value) }
            catch (_: Exception) { runtime.post { fail(value, "stt_stop_failed") } }
        }
    }

    private fun sdkStop(value: Run) {
        if (!value.requested || value.stopCalled || value.ended) return
        value.stopCalled = true
        requireNotNull(value.engine).stop()
        value.stopReturned = true
    }

    private fun clean(value: Run) {
        if (value.cleaning || value.clean) return
        // initialize() returning is not its completion. A late callback must release this original
        // handle; until then the process barrier remains occupied and no replacement is created.
        if (value.initInvoked && !value.initSettled) return
        value.cleaning = true
        runtime.sdk {
            var removed = !value.registrationAttempted
            var released = false
            try { if (value.registrationAttempted) {
                requireNotNull(value.engine).remove(value.listener); removed = true; value.removeReturned = true
            } } catch (_: Exception) { }
            try {
                val engine = value.engine
                if (engine != null) { engine.release(); released = true; value.releaseReturned = true }
                else released = !value.constructionAttempted
            } catch (_: Exception) { }
            val completedAt = runtime.now()
            runtime.post {
                value.clean = removed && released && (!value.requested || value.ended)
                if (!value.clean) { quarantine(); if (!value.retired) fail(value, "stt_cleanup_unknown") }
                else if (value.retired) runtime.recoveryReady = true
                else if (value.settleDeadline?.let { completedAt >= it } == true)
                    fail(value, "stt_settle_timeout")
                else if (!value.retired) {
                    mutable.value = mutable.value.copy(initializing = false, listening = false,
                        finalReady = value.text != null && !value.finish)
                    deliver(value)
                }
            }
        }
    }

    private fun deliver(value: Run) {
        if (value.delivered || value.retired || !value.finish || !value.ended || !value.clean || value.text == null) return
        if (!live(value)) { fail(value, "stt_context_changed"); return }
        val text = requireNotNull(value.text)
        value.delivered = true; value.text = null
        mutable.value = mutable.value.copy(initializing = false, listening = false, finalReady = false)
        // Neither en-US nor ON_DEVICE is evidence of original language or literal form.
        emit(DirectSpeechEvent.Final(value.lease, text, Capture.unverified(CapturePath.PARKED_STT)))
    }

    private fun quarantine() {
        runtime.quarantined = true
        runtime.recoveryReady = false
        mutable.value = mutable.value.copy(cleanupUnknown = true)
    }

    private fun fail(value: Run, reason: String) {
        if (run !== value || value.retired || value.delivered) return
        retire(value)
        emit(DirectSpeechEvent.Failed(value.lease.owner, reason, value.focus, value.lease.captureId))
    }

    private fun retire(value: Run) {
        value.retired = true; value.text = null
        captureGeneration++
        run = null
        mutable.value = mutable.value.copy(initializing = false, listening = false, finalReady = false)
        // An outstanding initialize/request/service call is not cancelled by logical invalidation.
        if (!value.clean) {
            quarantine()
            if (!value.stop && !value.ended) stop(value)
            // Keep the original listener until matching ended or the original settle deadline.
            // A release return without ended never proves that a requested mic has stopped.
            if (!value.requested || value.ended) clean(value)
            else runtime.at(value.settleDeadline ?: minOf(runtime.now() + 2_000, value.deadline)) { clean(value) }
        }
    }

    override fun cancel() {
        runtime.assertEventLane()
        val value = run
        if (value != null) retire(value) else captureGeneration++
    }
    override fun releaseFocus() {
        runtime.assertEventLane()
        focusGeneration++; focusOwner = null
        cancel()
        try { runtime.abandonFocus() } catch (_: Exception) { quarantine() }
        mutable.value = mutable.value.copy(focusGranted = false)
    }
    override fun close() {
        runtime.assertEventLane()
        if (closed) return
        closed = true; releaseFocus()
        mutable.value = mutable.value.copy(approved = false, processingNotice = null)
    }

    private fun reason(error: String) = when (error) {
        "PERMISSION_DENIED" -> "stt_permission_denied"
        "UNAVAILABLE_LANGUAGE" -> "stt_english_unavailable"
        "NOT_SUPPORTED_API" -> "stt_not_supported"
        "ON_DEVICE_ENGINE_ERROR" -> "stt_on_device_engine_error"
        "NETWORK_ERROR" -> "stt_network_error"
        "UNAUTHENTICATED" -> "stt_unauthenticated"
        else -> "stt_recognition_failed"
    }
}
