package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.data.wire.WireSource6PracticeContentRef6
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Independent closed command; these DTOs never change the legacy/source6 Capture or attempt wire. */
@Serializable data class CaptureConfirmationProfile(@SerialName("profile_id") val profileId:String,
    @SerialName("privacy_epoch") val privacyEpoch:Long)
@Serializable data class CaptureConfirmationRequest(
    @SerialName("confirmation_contract_version") val confirmationContractVersion:Long,
    @SerialName("profile_context") val profileContext:CaptureConfirmationProfile,
    @SerialName("operation_id") val operationId:String,
    @SerialName("capture_id") val captureId:String,
    @SerialName("attempt_id") val attemptId:String,
    @SerialName("lesson_id") val lessonId:String,
    @SerialName("source_protocol") val sourceProtocol:String,
    @SerialName("content_ref") val contentRef:JsonObject,
    @SerialName("original_capture") val originalCapture:JsonObject,
    @SerialName("answer_sha256") val answerSha256:String,
    @SerialName("confirmed_language") val confirmedLanguage:String,
)
@Serializable data class CaptureConfirmationReceipt(
    @SerialName("confirmation_contract_version") val confirmationContractVersion:Long,
    @SerialName("profile_context") val profileContext:CaptureConfirmationProfile,
    @SerialName("operation_id") val operationId:String,
    @SerialName("confirmation_ref") val confirmationRef:String,
    @SerialName("binding_hash") val bindingHash:String,
)
enum class CaptureConfirmationProtocol(val wire:String) { LEGACY5("legacy5"), SOURCE6("source6") }
data class CaptureConfirmationIntent(val operationId:UUID,val confirmationToken:UUID,
    val request:CaptureConfirmationRequest,val bindingHash:String,val status:String) {
    val answerSha256 get()=request.answerSha256
}

/** Python ensure_ascii=false/sort_keys/separators=(",",":") parity; no Unicode normalization. */
object CaptureConfirmationHashes {
    fun answer(normalizedText:String):String {
        require(normalizedText==normalizedText.trim() && normalizedText.codePointCount(0,normalizedText.length) in 1..1200)
        return separated("capture-language-answer-v1",normalizedText)
    }
    fun binding(request:JsonObject):String=separated("capture-language-binding-v1",canonical(request))
    fun canonical(value:JsonElement):String {
        when(value) {
            is JsonObject -> value.forEach{(_,v)->canonical(v)}
            is JsonArray -> value.forEach{canonical(it)}
            is JsonPrimitive -> if(value!==JsonNull && !value.isString)
                require(value.content in setOf("true","false") || value.content.matches(Regex("-?(0|[1-9][0-9]*)")))
        }
        return incrementalCanonical(value)
    }
    private fun separated(domain:String,value:String):String {
        val bytes=try {
            val encoded=Charsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
            ByteArray(encoded.remaining()).also{encoded.get(it)}
        }catch(_:java.nio.charset.CharacterCodingException){throw IllegalArgumentException("Invalid Unicode confirmation binding.")}
        return MessageDigest.getInstance("SHA-256").digest(domain.toByteArray(Charsets.UTF_8)+byteArrayOf(0)+bytes)
            .joinToString(""){"%02x".format(it)}
    }
}
internal object CaptureConfirmationWireRules {
    const val KIND="capture_language_confirmation_v1"
    fun request(value:JsonObject) {
        require(value.keys==setOf("confirmation_contract_version","profile_context","operation_id","capture_id","attempt_id","lesson_id","source_protocol","content_ref","original_capture","answer_sha256","confirmed_language"))
        require(value.getValue("operation_id")==value.getValue("capture_id"))
        CaptureConfirmationHashes.canonical(value)
    }
    fun ref(task:PracticeRef,protocol:CaptureConfirmationProtocol):JsonObject = when(protocol) {
        CaptureConfirmationProtocol.LEGACY5 -> learningJson.parseToJsonElement(LearningJson.practice(task).toString()).jsonObject
        CaptureConfirmationProtocol.SOURCE6 -> learningJson.encodeToJsonElement(WireSource6PracticeContentRef6.serializer(),task.source6Wire()).jsonObject
    }
    fun capture(value:Capture):JsonObject=learningJson.parseToJsonElement(LearningJson.capture(value).toString()).jsonObject
    fun original(value:Capture) {
        require(value.path==CapturePath.PARKED_STT && value.formFidelity==CaptureFidelity.UNKNOWN &&
            value.spokenLanguage==SpokenLanguage.UNKNOWN && value.englishEvidence==EnglishEvidence.UNVERIFIED && value.languageEvidenceRef==null)
    }
    fun receipt(request:CaptureConfirmationRequest,value:CaptureConfirmationReceipt) {
        require(value.confirmationContractVersion==1L && value.profileContext==request.profileContext && value.operationId==request.operationId &&
            value.confirmationRef=="clc1:${request.captureId}" &&
            value.bindingHash==CaptureConfirmationHashes.binding(learningJson.encodeToJsonElement(CaptureConfirmationRequest.serializer(),request).jsonObject))
    }
}
