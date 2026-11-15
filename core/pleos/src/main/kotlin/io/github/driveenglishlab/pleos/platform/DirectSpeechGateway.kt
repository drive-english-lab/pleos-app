package io.github.driveenglishlab.pleos.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.driveenglishlab.pleos.domain.*
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A reviewed provider/retention/mic-arbitration record, not an engine availability query. */
data class SpeechRuntimeApproval(val recognizer: ComponentName, val ttsEnginePackage: String,
    val recognizerCertificateSha256: String, val ttsCertificateSha256: String,
    val retentionReviewRef: String, val arbitrationReviewRef: String, val outputReviewRef: String,
    val originalEnglishReviewRef: String? = null, val literalFormReviewRef: String? = null,
    val processingNotice: ParkedSpeechProcessingNotice? = null) {
    init {
        require(listOf(retentionReviewRef, arbitrationReviewRef, outputReviewRef).all { it.isNotBlank() })
        require(listOf(recognizerCertificateSha256, ttsCertificateSha256).all { it.matches(Regex("[a-f0-9]{64}")) })
    }
}

sealed interface DirectSpeechEvent {
    data class Final(val lease: CaptureLease, val text: String, val capture: Capture) : DirectSpeechEvent
    data class Ended(val owner: OwnerContext, val outputId: UUID, val endedAtMs: Long, val evidenceRef: String) : DirectSpeechEvent
    data class Failed(val owner: OwnerContext?, val reason: String, val focusGeneration: Long, val captureId: UUID? = null, val outputId: UUID? = null) : DirectSpeechEvent
    data class Interrupted(val owner: OwnerContext, val focusGeneration: Long, val captureId: UUID?, val outputId: UUID?) : DirectSpeechEvent
}
data class DirectSpeechReadiness(val approved: Boolean = false, val outputReady: Boolean = false, val focusGranted: Boolean = false,
    val listening: Boolean = false)

/** Immutable identity/permission facts for one issued output, never a later focus lifetime. */
internal data class DirectSpeechOutputLifetime(val owner: OwnerContext, val outputId: UUID,
    val focusGeneration: Long, val audioPermissionGeneration: Long)

/** RAM-only submission gate shared by the generic adapter and its isolated regression fixture.
 * SDK cleanup is an explicit input, never inferred from result/locale/instance availability. */
internal class DirectInputFinalGate(private val lease:CaptureLease,private val captureGeneration:Long,private val focusGeneration:Long) {
    var text:String?=null;private set
    var ended=false;private set
    var finishRequested=false;private set
    var delivered=false;private set
    private var cancelled=false
    private var ambiguous=false
    private var cleanupConfirmed=false
    fun final(value:String):Boolean {
        if(cancelled || ambiguous || delivered)return false
        val normalized=normalizeFinalAnswer(value)?:return false
        if(text!=null && text!=normalized){ambiguous=true;text=null;return false}
        text=normalized;return true
    }
    fun end(){if(!cancelled && !delivered)ended=true}
    fun finish(){if(!cancelled && !delivered)finishRequested=true}
    fun cleanup(confirmed:Boolean){cleanupConfirmed=confirmed}
    fun cancel(){cancelled=true;text=null}
    fun take(currentLease:CaptureLease,currentCaptureGeneration:Long,currentFocusGeneration:Long,current:Boolean):String? {
        if(!current || cancelled || ambiguous || delivered || !finishRequested || !ended || !cleanupConfirmed ||
            currentLease!=lease || captureGeneration!=currentCaptureGeneration || focusGeneration!=currentFocusGeneration)return null
        val candidate=text?:return null
        text=null;delivered=true;return candidate
    }
}

/** No raw audio retention, locale-as-proof, automatic permission grant/start, or focus-gain resume. */
class DirectSpeechGateway(private val context: Context, private val approval: SpeechRuntimeApproval? = null,
    private val emit: (DirectSpeechEvent) -> Unit) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(DirectSpeechReadiness())
    val state = mutable.asStateFlow()
    private val audio = context.getSystemService(AudioManager::class.java)
    private var recognizer: SpeechRecognizer? = null
    private data class InputConsumer(val notice:ParkedSpeechProcessingNotice,val current:(CaptureLease)->Boolean,val emit:(DirectSpeechEvent)->Unit)
    private class InputRun(val lease:CaptureLease,val generation:Long,val focusGeneration:Long,val startedAt:Long) {
        val gate=DirectInputFinalGate(lease,generation,focusGeneration)
        val ended get()=gate.ended
        val text get()=gate.text
        val finishRequested get()=gate.finishRequested
        val delivered get()=gate.delivered
        var recognizer:SpeechRecognizer?=null;var requested=false;var ready=false;var stopRequested=false
        var cleaned=false;var retired=false;var settlingDeadline:Long?=null
        var initializationTimeout:Runnable?=null;var settlingTimeout:Runnable?=null;var lifetimeTimeout:Runnable?=null
    }
    private val inputMutable=MutableStateFlow(ParkedSpeechInputReadiness(cleanupUnknown=processInputCleanupUnknown.get()))
    private var inputConsumer:InputConsumer?=null
    private var inputPortCreated=false
    private var inputRun:InputRun?=null
    // Public generic APIs provide no independent physical-termination recovery proof here.
    // Replacing this gateway/wrapper in the same process must not erase an unknown mic lifetime.
    // Process restart is not proof either: production still defaults to no reviewed input route
    // and requires actual environment/arbitration review before any later activation.
    private var cleanupUnknown:Boolean
        get()=processInputCleanupUnknown.get()
        set(value){if(value)processInputCleanupUnknown.set(true)}
    private companion object { val processInputCleanupUnknown=AtomicBoolean(false) }

    /** Generic input is an explicitly selected, separately reviewed route. Android TTS readiness
     * never approves input, and this adapter cannot manufacture notice revisions/review refs. */
    fun parkedInputPort(processingNotice:String?,current:(CaptureLease)->Boolean,emit:(DirectSpeechEvent)->Unit):ParkedSpeechInputPort {
        check(!inputPortCreated && inputRun==null) { "Input route is fixed once per gateway" };inputPortCreated=true
        val notice=approval?.processingNotice?.takeIf{it.text==processingNotice}
        if(notice!=null)inputConsumer=InputConsumer(notice,current,emit)
        inputMutable.value=inputMutable.value.copy(processingNotice=notice,cleanupUnknown=cleanupUnknown)
        return object:ParkedSpeechInputPort {
            private var portClosed=false
            override val state=inputMutable.asStateFlow()
            override val currentFocusGeneration get()=this@DirectSpeechGateway.currentFocusGeneration
            override val currentCaptureGeneration get()=this@DirectSpeechGateway.currentCaptureGeneration
            override fun initialize(){if(!portClosed)initializeInput()}
            override fun acquireFocus(owner:OwnerContext)=!portClosed && inputMutable.value.approved && this@DirectSpeechGateway.acquireFocus(owner)
            override fun acceptsEvent(owner:OwnerContext?,generation:Long)=!portClosed && inputMutable.value.approved && !cleanupUnknown && this@DirectSpeechGateway.acceptsEvent(owner,generation)
            override fun start(value:CaptureLease,access:AccessState)=!portClosed && this@DirectSpeechGateway.start(value,access)
            override fun finish(value:CaptureLease){if(!portClosed)this@DirectSpeechGateway.finish(value)}
            override fun cancel()=this@DirectSpeechGateway.cancel()
            override fun releaseFocus()=this@DirectSpeechGateway.releaseFocus()
            // The input port shares the output gateway, so closing it retires input/focus only.
            override fun close(){if(portClosed)return;portClosed=true;releaseFocus();inputMutable.value=ParkedSpeechInputReadiness(cleanupUnknown=cleanupUnknown,processingNotice=notice)}
        }
    }
    private fun initializeInput() {
        check(Looper.myLooper()==Looper.getMainLooper())
        val policy=approval?:return
        if(closed || cleanupUnknown || inputConsumer==null || !certificateMatches(policy.recognizer.packageName,policy.recognizerCertificateSha256))return
        inputMutable.value=inputMutable.value.copy(approved=true)
    }
    private fun inputCurrent(run:InputRun):Boolean = !closed && !run.retired && inputRun===run && lease==run.lease && captureGeneration==run.generation &&
        focusGeneration==run.focusGeneration && focusOwner==run.lease.owner && mutable.value.focusGranted &&
        android.os.SystemClock.elapsedRealtime().let{now->now<run.startedAt+60_000 && (run.ready || now<run.startedAt+5_000) &&
            (run.cleaned || run.settlingDeadline?.let{now<it}!=false)} &&
        inputConsumer?.current?.invoke(run.lease)==true && context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
    private fun clearTimers(run:InputRun) {listOfNotNull(run.initializationTimeout,run.settlingTimeout,run.lifetimeTimeout).forEach(handler::removeCallbacks)}
    private fun inputFailure(run:InputRun,reason:String) {
        if(inputRun!==run || run.retired || run.delivered)return
        val event=DirectSpeechEvent.Failed(run.lease.owner,reason,run.focusGeneration,run.lease.captureId)
        cancelInput();inputConsumer?.emit?.invoke(event)
    }
    private fun settling(run:InputRun) {
        if(run.settlingTimeout!=null || run.cleaned || run.retired)return
        run.settlingDeadline=android.os.SystemClock.elapsedRealtime()+2_000
        run.settlingTimeout=Runnable{if(inputRun===run && !run.cleaned)inputFailure(run,"recognition_settling_timeout")}.also{handler.postDelayed(it,2_000)}
    }
    private fun stopInput(run:InputRun) {
        if(run.stopRequested || run.ended || run.retired)return
        run.stopRequested=true;settling(run)
        try{run.recognizer?.stopListening()}catch(_:Exception){inputFailure(run,"recognition_stop_unavailable")}
    }
    private fun cleanInput(run:InputRun,normal:Boolean):Boolean {
        if(run.cleaned)return !cleanupUnknown
        var removed=true;var destroyed=true
        try{run.recognizer?.setRecognitionListener(null)}catch(_:Exception){removed=false}
        try{run.recognizer?.destroy()}catch(_:Exception){destroyed=false}
        run.cleaned=removed && destroyed
        if(run.cleaned){if(recognizer===run.recognizer)recognizer=null;run.recognizer=null}
        // A fresh recognizer/default idle is not physical termination evidence for an old run.
        if(!run.cleaned || run.requested && !normal)cleanupUnknown=true
        inputMutable.value=inputMutable.value.copy(cleanupUnknown=cleanupUnknown)
        return run.cleaned && !cleanupUnknown
    }
    private fun deliverInput(run:InputRun) {
        if(!inputCurrent(run)){inputFailure(run,"recognition_context_changed");return}
        if(run.text==null || !run.requested || !run.ended)return
        if(!cleanInput(run,true)){inputFailure(run,"recognition_cleanup_unknown");return}
        run.initializationTimeout?.let(handler::removeCallbacks);run.settlingTimeout?.let(handler::removeCallbacks)
        inputMutable.value=inputMutable.value.copy(listening=false,initializing=false,finalReady=true)
        if(!run.finishRequested || run.delivered)return
        if(!inputCurrent(run)){inputFailure(run,"recognition_context_changed");return}
        run.gate.cleanup(true)
        val text=run.gate.take(run.lease,captureGeneration,focusGeneration,inputCurrent(run))?:return
        clearTimers(run);lease=null;inputRun=null
        inputMutable.value=inputMutable.value.copy(finalReady=false)
        val policy=requireNotNull(approval)
        val capture=if(policy.originalEnglishReviewRef!=null)Capture(CapturePath.PARKED_STT,
            if(policy.literalFormReviewRef!=null)CaptureFidelity.LITERAL_CONTRACT_VERIFIED else CaptureFidelity.UNKNOWN,
            emptyList(),SpokenLanguage.EN,EnglishEvidence.VERIFIED_PATH,policy.originalEnglishReviewRef)else Capture.unverified(CapturePath.PARKED_STT)
        // Normal endpoint + public cleanup preserve logical capture/focus generations for E1.
        inputConsumer?.emit?.invoke(DirectSpeechEvent.Final(run.lease,text,capture))
    }
    private fun cancelInput() {
        val run=inputRun
        captureGeneration++;lease=null;inputRun=null
        if(run!=null){run.retired=true;run.gate.cancel();clearTimers(run)
            try{if(run.requested && !run.ended)run.recognizer?.cancel()}catch(_:Exception){cleanupUnknown=true}
            cleanInput(run,run.ended)
        }
        inputMutable.value=inputMutable.value.copy(listening=false,initializing=false,finalReady=false,cleanupUnknown=cleanupUnknown)
    }
    private var tts: TextToSpeech? = null
    private var lease: CaptureLease? = null
    private var output: DirectSpeechOutputLifetime? = null
    private var ready = false
    private var captureGeneration = 0L
    private var closed = false
    private var focus: AudioFocusRequest? = null
    private var focusOwner: OwnerContext? = null
    private var focusGeneration = 0L
    /** Read-only identity for a pending same-capture confirmation; never grants focus. */
    val currentFocusGeneration: Long get() = focusGeneration
    val currentCaptureGeneration: Long get() = captureGeneration
    fun acceptsEvent(owner: OwnerContext?, generation: Long): Boolean = !closed && generation == focusGeneration && (owner == null || owner == focusOwner)

    private fun certificateMatches(packageName: String, expected: String): Boolean = try {
        @Suppress("DEPRECATION") val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        info.signingInfo?.apkContentsSigners?.any { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) } == expected
        } == true
    } catch (_: Exception) { false }

    fun initialize() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val policy = approval ?: return
        if (closed || mutable.value.approved || !certificateMatches(policy.recognizer.packageName, policy.recognizerCertificateSha256) ||
            !certificateMatches(policy.ttsEnginePackage, policy.ttsCertificateSha256)) return
        try {
            mutable.value = mutable.value.copy(approved = true)
            tts = TextToSpeech(context, { status -> handler.post {
                if (!closed) {
                    ready = status == TextToSpeech.SUCCESS
                    mutable.value = mutable.value.copy(outputReady = ready)
                }
            } }, policy.ttsEnginePackage).apply {
                setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = postOutputDone(utteranceId, policy.outputReviewRef)
                    @Deprecated("Platform legacy callback") override fun onError(utteranceId: String?) = postOutputError(utteranceId)
                })
            }
        } catch (_: Exception) { close(); emit(DirectSpeechEvent.Failed(null, "provider_unavailable", focusGeneration)) }
    }

    /** Resolve on the handler after any queued replacement/cancel/focus change, before mutation. */
    private fun takeCurrentOutput(utteranceId: String?): DirectSpeechOutputLifetime? {
        val original = output?.takeIf {
            !closed && utteranceId != null && it.outputId.toString() == utteranceId &&
                it.focusGeneration == focusGeneration && it.owner == focusOwner && mutable.value.focusGranted
        } ?: return null
        output = null // Identity and its audio/focus metadata retire together, exactly once.
        return original
    }
    private fun postOutputDone(utteranceId: String?, evidenceRef: String) { handler.post {
        val original = takeCurrentOutput(utteranceId) ?: return@post
        emit(DirectSpeechEvent.Ended(original.owner, original.outputId, android.os.SystemClock.elapsedRealtime(), evidenceRef))
    } }
    private fun postOutputError(utteranceId: String?) { handler.post {
        val original = takeCurrentOutput(utteranceId) ?: return@post
        emit(DirectSpeechEvent.Failed(original.owner, "speech_output_unavailable", original.focusGeneration, outputId = original.outputId))
    } }

    // Android SpeechRecognizer public methods require this main lane. Logical monotonic bounds
    // do not forcibly interrupt a blocked Binder call or prove physical microphone termination.
    private fun createRecognizer(run:InputRun): SpeechRecognizer {
        val policy = requireNotNull(approval)
        return SpeechRecognizer.createSpeechRecognizer(context,policy.recognizer).apply {
            run.recognizer=this
            setRecognitionListener(object:RecognitionListener {
                private fun current():Boolean {if(inputRun!==run || run.retired || run.delivered)return false
                    if(!inputCurrent(run)){inputFailure(run,"recognition_context_changed");return false};return true}
                override fun onReadyForSpeech(params:Bundle?){if(!current())return;run.ready=true
                    run.initializationTimeout?.let(handler::removeCallbacks);inputMutable.value=inputMutable.value.copy(initializing=false,listening=true)
                    mutable.value=mutable.value.copy(listening=true)}
                override fun onBeginningOfSpeech()=Unit
                override fun onRmsChanged(rmsdB:Float)=Unit
                override fun onBufferReceived(buffer:ByteArray?)=Unit
                override fun onPartialResults(partialResults:Bundle?)=Unit
                override fun onEvent(eventType:Int,params:Bundle?)=Unit
                override fun onEndOfSpeech(){if(!current())return;run.ready=true;run.initializationTimeout?.let(handler::removeCallbacks);run.gate.end();settling(run)
                    inputMutable.value=inputMutable.value.copy(listening=false,initializing=false);mutable.value=mutable.value.copy(listening=false);deliverInput(run)}
                override fun onError(error:Int){if(current())inputFailure(run,"recognition_unavailable")}
                override fun onResults(results:Bundle?){if(!current())return
                    run.ready=true;run.initializationTimeout?.let(handler::removeCallbacks)
                    val text=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    if(text.isBlank() || text.codePointCount(0,text.length)>1200){inputFailure(run,"no_final_text");return}
                    if(run.text!=null && run.text!=text){inputFailure(run,"recognition_final_ambiguous");return}
                    if(!run.gate.final(text)){inputFailure(run,"recognition_final_ambiguous");return};stopInput(run);deliverInput(run)
                }
            })
        }
    }

    fun acquireFocus(owner: OwnerContext): Boolean {
        if ((!mutable.value.approved && !inputMutable.value.approved) || closed || cleanupUnknown) return false
        if (focusOwner == owner && mutable.value.focusGranted) return true
        releaseFocus()
        val generation = ++focusGeneration
        focusOwner = owner
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ change ->
                if (generation != focusGeneration || focusOwner != owner || closed) return@setOnAudioFocusChangeListener
                mutable.value = mutable.value.copy(focusGranted = change == AudioManager.AUDIOFOCUS_GAIN)
                inputMutable.value=inputMutable.value.copy(focusGranted=mutable.value.focusGranted)
                if (change != AudioManager.AUDIOFOCUS_GAIN) {
                    val capture = lease?.captureId; val spoken = output?.outputId
                    cancel()
                    val event=DirectSpeechEvent.Interrupted(owner,generation,capture,spoken)
                    if(capture!=null)inputConsumer?.emit?.invoke(event)
                    if(spoken!=null)emit(event)
                }
            }, handler).build()
        focus = request
        val granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        mutable.value = mutable.value.copy(focusGranted = granted)
        inputMutable.value=inputMutable.value.copy(focusGranted=granted)
        return granted
    }
    fun start(value: CaptureLease, access: AccessState): Boolean {
        check(Looper.myLooper()==Looper.getMainLooper())
        if (!access.canCapture || !inputMutable.value.approved || cleanupUnknown || inputRun!=null || lease!=null ||
            !mutable.value.focusGranted || focusOwner!=value.owner || closed || inputConsumer?.current?.invoke(value)!=true ||
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return false
        output=null;tts?.stop();lease=value
        val run=InputRun(value,++captureGeneration,focusGeneration,android.os.SystemClock.elapsedRealtime());inputRun=run
        inputMutable.value=inputMutable.value.copy(initializing=true,listening=false,finalReady=false)
        return try {
            val native=createRecognizer(run);recognizer=native;run.recognizer=native
            run.initializationTimeout=Runnable{if(inputRun===run && !run.ready)inputFailure(run,"recognition_initialization_timeout")}.also{handler.postDelayed(it,5_000)}
            run.lifetimeTimeout=Runnable{if(inputRun===run && !run.delivered)inputFailure(run,"recognition_capture_timeout")}.also{handler.postDelayed(it,60_000)}
            run.requested=true
            native.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE,"en-US").putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false))
            true
        } catch(_:Exception){inputFailure(run,"recognition_start_unavailable");false}
    }
    fun finish(value:CaptureLease) {
        val run=inputRun?.takeIf{it.lease==value && !it.retired && !it.delivered}?:return
        if(!inputCurrent(run)){inputFailure(run,"recognition_context_changed");return}
        run.gate.finish();stopInput(run);deliverInput(run)
    }
    val speaking: Boolean get() = output != null
    fun outputAllowed(access: AccessState): Boolean {
        val original = output ?: return false
        return original.focusGeneration == focusGeneration &&
            original.audioPermissionGeneration == access.components.audio.permissionGeneration && permitsOutput(original.owner, access)
    }
    private fun permitsOutput(owner: OwnerContext, access: AccessState): Boolean {
        val actualOutput = access.copy(parkedOutputAvailable = mutable.value.approved && ready,
            directReplyFocusAllowed = mutable.value.focusGranted && focusOwner == owner)
        return !closed && access.detailsOpen && access.window.focused && actualOutput.parkedReplyAllowed &&
            ready && mutable.value.focusGranted && focusOwner == owner && lease == null && !cleanupUnknown
    }
    fun speak(owner: OwnerContext, id: UUID, text: String, language: String, access: AccessState): Boolean {
        // Select this reviewed engine/certificate route, independently from the news output route.
        if (!permitsOutput(owner, access)) return false
        val voice = tts ?: return false
        val locale = if (language == "en") Locale.US else Locale.KOREAN
        if (voice.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE || voice.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) return false
        output = DirectSpeechOutputLifetime(owner, id, focusGeneration, access.components.audio.permissionGeneration)
        if (voice.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id.toString()) != TextToSpeech.SUCCESS) { output = null; return false }
        return true
    }
    fun cancel() { cancelInput();output=null;tts?.stop();mutable.value=mutable.value.copy(listening=false) }
    fun releaseFocus() {
        val previous = focus
        focusGeneration++; focus = null; focusOwner = null
        cancel(); previous?.let { audio.abandonAudioFocusRequest(it) }
        mutable.value = mutable.value.copy(focusGranted = false)
        inputMutable.value=inputMutable.value.copy(focusGranted=false)
    }
    override fun close() { closed = true; releaseFocus(); recognizer?.destroy(); recognizer = null; tts?.shutdown(); tts = null; ready = false; mutable.value = DirectSpeechReadiness();inputMutable.value=ParkedSpeechInputReadiness(cleanupUnknown=cleanupUnknown,processingNotice=inputConsumer?.notice) }
}
