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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A reviewed provider/retention/mic-arbitration record, not an engine availability query. */
data class SpeechRuntimeApproval(val recognizer: ComponentName, val ttsEnginePackage: String,
    val recognizerCertificateSha256: String, val ttsCertificateSha256: String,
    val retentionReviewRef: String, val arbitrationReviewRef: String, val outputReviewRef: String,
    val originalEnglishReviewRef: String? = null, val literalFormReviewRef: String? = null) {
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

/** No raw audio retention, locale-as-proof, automatic permission grant/start, or focus-gain resume. */
class DirectSpeechGateway(private val context: Context, private val approval: SpeechRuntimeApproval? = null,
    private val emit: (DirectSpeechEvent) -> Unit) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(DirectSpeechReadiness())
    val state = mutable.asStateFlow()
    private val audio = context.getSystemService(AudioManager::class.java)
    private var recognizer: SpeechRecognizer? = null
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

    private fun createRecognizer(value: CaptureLease, generation: Long): SpeechRecognizer {
        val policy = requireNotNull(approval)
        return SpeechRecognizer.createSpeechRecognizer(context, policy.recognizer).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { if (lease != value || captureGeneration != generation) return; mutable.value = mutable.value.copy(listening = true) }
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit // Never retain raw/interim audio.
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    override fun onError(error: Int) {
                        if (lease != value || captureGeneration != generation) return
                        val original = lease; lease = null; mutable.value = mutable.value.copy(listening = false)
                        emit(DirectSpeechEvent.Failed(original?.owner, "recognition_unavailable", focusGeneration, original?.captureId))
                    }
                    override fun onResults(results: Bundle?) {
                        if (lease != value || captureGeneration != generation) return
                        val original = lease ?: return
                        lease = null; mutable.value = mutable.value.copy(listening = false)
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                        if (text.isBlank() || text.codePointCount(0, text.length) > 1200) {
                            emit(DirectSpeechEvent.Failed(original.owner, "no_final_text", focusGeneration, original.captureId)); return
                        }
                        // Only an environment-reviewed original-English path can make this claim.
                        val capture = if (policy.originalEnglishReviewRef != null) Capture(CapturePath.PARKED_STT,
                            if (policy.literalFormReviewRef != null) CaptureFidelity.LITERAL_CONTRACT_VERIFIED else CaptureFidelity.UNKNOWN,
                            emptyList(), SpokenLanguage.EN, EnglishEvidence.VERIFIED_PATH, policy.originalEnglishReviewRef)
                        else Capture.unverified(CapturePath.PARKED_STT)
                        emit(DirectSpeechEvent.Final(original, text, capture))
                    }
                })
            }
    }

    fun acquireFocus(owner: OwnerContext): Boolean {
        if (!mutable.value.approved || closed) return false
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
                if (change != AudioManager.AUDIOFOCUS_GAIN) {
                    val capture = lease?.captureId; val spoken = output?.outputId
                    cancel()
                    emit(DirectSpeechEvent.Interrupted(owner, generation, capture, spoken))
                }
            }, handler).build()
        focus = request
        val granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        mutable.value = mutable.value.copy(focusGranted = granted)
        return granted
    }
    fun start(value: CaptureLease, access: AccessState): Boolean {
        if (!access.canCapture || !mutable.value.approved || lease != null || !mutable.value.focusGranted || focusOwner != value.owner || closed) return false
        output = null; tts?.stop(); lease = value
        return try {
            recognizer?.destroy()
            recognizer = createRecognizer(value, ++captureGeneration)
            recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US").putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
            true
        } catch (_: Exception) { lease = null; false }
    }
    fun finish(value: CaptureLease) { if (lease == value) recognizer?.stopListening() }
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
            ready && mutable.value.focusGranted && focusOwner == owner && lease == null
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
    fun cancel() { captureGeneration++; lease = null; output = null; recognizer?.cancel(); tts?.stop(); mutable.value = mutable.value.copy(listening = false) }
    fun releaseFocus() {
        val previous = focus
        focusGeneration++; focus = null; focusOwner = null
        cancel(); previous?.let { audio.abandonAudioFocusRequest(it) }
        mutable.value = mutable.value.copy(focusGranted = false)
    }
    override fun close() { closed = true; releaseFocus(); recognizer?.destroy(); recognizer = null; tts?.shutdown(); tts = null; ready = false; mutable.value = DirectSpeechReadiness() }
}
