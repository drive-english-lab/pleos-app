package io.github.driveenglishlab.pleos.domain

import java.util.UUID

/** User session credentials only. Server/model keys never enter this port or Room. */
class LearningSession(val subjectId: UUID, val sessionId: UUID, val installationId: UUID, private val accessToken: String) {
    init { require(accessToken.isNotBlank() && !accessToken.contains('\r') && !accessToken.contains('\n') && !accessToken.startsWith("sb_")) }
    fun authorizationHeader() = "Bearer $accessToken"
    override fun toString() = "LearningSession(credentials=redacted)"
}
data class LearningCallScope(val owner: OwnerContext, val session: LearningSession)
fun interface LearningSessionSource { suspend fun current(): LearningCallScope? }

sealed interface LearningReply<out T> {
    class Stored<T>(val scope: LearningCallScope, val value: T) : LearningReply<T> {
        override fun toString() = "LearningReply.Stored(payload=redacted)"
    }
    data class Failed(val code: String, val retryable: Boolean, val retryAfterSeconds: Long? = null) : LearningReply<Nothing>
}

data class GleoCallback(val functionName: String, val status: String, val message: String, val data: String?, val mode: String)
sealed interface GleoDelivery {
    data object CallbackSent : GleoDelivery
    data class Unavailable(val reason: String) : GleoDelivery
}
interface GleoTransportPort {
    val capabilities: VoiceCapabilities
    fun callback(value: GleoCallback): GleoDelivery
}
