package io.github.driveenglishlab.pleos.platform

import io.github.driveenglishlab.pleos.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Supplied only after project action/sender/parameter and environment verification. */
interface GleoProjectAdapter {
    val capabilities: VoiceCapabilities
    val callback: GleoTransportPort
    val schemaApproval: Capability
    val actionParamJsonVerified: Boolean
    /** Actual project/environment vehicle scope and registration lifetime, never inferred from schema approval. */
    val vehicleScopeApproval: Capability get() = Capability.Unavailable("gleo_vehicle_scope_not_verified")
    val permissionGeneration: Long? get() = null
    fun allowsVehicle(safety: SafetyState): Boolean = false
    /** Each additional pending-choice enum needs actual project schema approval of its own. */
    fun pendingChoiceCommandVerified(command:String):Boolean=false
    fun acceptsSender(uid: Int, packageName: String?): Boolean
    val events: Flow<GleoRuntimeEvent>
    /** Associate the exact pending callback content with a real adapter output token. */
    suspend fun bindReplyOutput(owner: OwnerContext, outputId: UUID, text: String): Boolean
    /** A specific actual capture binding, not a string/locale inference. */
    fun captureFor(action: GleoProductAction): GleoCaptureBinding?
}
sealed interface GleoRuntimeEvent {
    /** Actual adapter/route lifetime. Omitted legacy events cannot grant a current permission. */
    val permissionGeneration: Long?
    data class InputStarted(val owner: OwnerContext, val lessonId: UUID?, val inputId: UUID, override val permissionGeneration: Long? = null) : GleoRuntimeEvent
    data class InputChanged(val owner: OwnerContext, val activity: VoiceInputActivity, val atMs: Long, override val permissionGeneration: Long? = null) : GleoRuntimeEvent
    data class OutputEnded(val owner: OwnerContext, val outputId: UUID, val atMs: Long, val evidenceRef: String, override val permissionGeneration: Long? = null) : GleoRuntimeEvent
    data class ReplyAvailability(val owner: OwnerContext, val allowed: Boolean, override val permissionGeneration: Long? = null) : GleoRuntimeEvent
    data class Interrupted(val owner: OwnerContext, override val permissionGeneration: Long? = null) : GleoRuntimeEvent
}
data class GleoCaptureBinding(val inputId: UUID, val capture: Capture)

interface GleoProductHandler {
    suspend fun playback(command: String, title: String?, mode: String?,capture:GleoCaptureBinding?=null): String
    suspend fun question(text: String, title: String?): String
    suspend fun practice(command: String, text: String?, title: String?, capture: GleoCaptureBinding?): String
}

/** Arbitrary utterance text is never classified into playback commands. */
class GleoGateway(private val adapter: GleoProjectAdapter, private val handler: GleoProductHandler) : GleoProductRuntime {
    override val callback get() = adapter.callback
    override val actionParamJsonVerified get() = adapter.schemaApproval is Capability.Verified && adapter.actionParamJsonVerified
    override fun acceptsSender(uid: Int, packageName: String?) = adapter.schemaApproval is Capability.Verified && adapter.acceptsSender(uid, packageName)
    override suspend fun handle(action: GleoProductAction): GleoCallback = withContext(Dispatchers.Main.immediate) {
        if (!actionParamJsonVerified) return@withContext GleoCallback(action.function, "fail", "음성 연결을 확인하지 못했어요. 저장한 진행은 유지돼요", null, "voice")
        val p = action.parameters
        if(action.function=="DRIVE_ENGLISH_PLAYBACK" && p["command"] in GleoProductActionParser.pendingChoiceCommands &&
            !adapter.pendingChoiceCommandVerified(requireNotNull(p["command"])))
            return@withContext GleoCallback(action.function,"fail","이 대기 선택의 음성 연결을 확인하지 못했어요. 현재 기사는 유지돼요",null,"voice")
        val text = when (action.function) {
            "DRIVE_ENGLISH_PLAYBACK" -> handler.playback(requireNotNull(p["command"]), p["articleTitle"], p["mode"],adapter.captureFor(action))
            "DRIVE_ENGLISH_ARTICLE_QUESTION" -> handler.question(requireNotNull(p["questionText"]), p["articleTitle"])
            "DRIVE_ENGLISH_PRACTICE" -> handler.practice(requireNotNull(p["command"]), p["utteranceText"], p["articleTitle"], adapter.captureFor(action))
            else -> "요청을 확인하지 못했어요. 현재 학습은 유지돼요"
        }
        // Only transmission. Actual output/input events are the separate adapter Flow.
        GleoCallback(action.function, "success", text, null, "voice")
    }
}
