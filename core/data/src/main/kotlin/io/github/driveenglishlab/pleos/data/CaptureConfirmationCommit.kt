package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.serialization.json.*

/** Runs inside the caller's existing Room acceptance transaction. Never makes a network call. */
internal object CaptureConfirmationCommit {
    suspend fun accept(dao:ReviewDao,answer:AcceptedAnswer,protocol:CaptureConfirmationProtocol) {
        val capture=answer.capture
        if(capture.englishEvidence!=EnglishEvidence.SPECIFIC_CONFIRMATION || capture.languageEvidenceRef?.startsWith("clc1:")!=true)return
        val owner=answer.lease.owner
        val id=UUID.fromString(requireNotNull(capture.languageEvidenceRef).removePrefix("clc1:"))
        require(id==answer.lease.captureId && capture.languageEvidenceRef=="clc1:$id")
        val row=requireNotNull(dao.operation(id.toString()))
        require(row.kind==CaptureConfirmationWireRules.KIND && row.profileId==owner.profileId.toString() && row.privacyEpoch==owner.privacyEpoch &&
            row.ownerRevision==owner.revision && row.payloadHash==LearningJson.hash(row.payloadJson) && row.status in setOf("receipt_ready","accepted"))
        val wrapper=learningJson.parseToJsonElement(row.payloadJson).jsonObject
        require(wrapper.keys==setOf("request","confirmation_token"));UUID.fromString(wrapper.getValue("confirmation_token").jsonPrimitive.content)
        val request=learningJson.decodeFromJsonElement(CaptureConfirmationRequest.serializer(),wrapper.getValue("request"))
        CaptureConfirmationWireRules.request(wrapper.getValue("request").jsonObject)
        val receipt=learningJson.decodeFromString(CaptureConfirmationReceipt.serializer(),requireNotNull(row.receiptJson))
        CaptureConfirmationWireRules.receipt(request,receipt)
        require(request.confirmationContractVersion==1L && request.captureId==id.toString() && request.operationId==id.toString() &&
            request.attemptId==answer.lease.attemptId.toString() && row.targetId==request.attemptId && request.lessonId==answer.lease.lessonId.toString() &&
            request.profileContext==CaptureConfirmationProfile(owner.profileId.toString(),owner.privacyEpoch) && request.sourceProtocol==protocol.wire &&
            request.contentRef==CaptureConfirmationWireRules.ref(answer.lease.task,protocol) &&
            request.answerSha256==CaptureConfirmationHashes.answer(answer.text) && capture.formFidelity==CaptureFidelity.UNKNOWN)
        val expectedOriginal=CaptureConfirmationWireRules.capture(capture.copy(spokenLanguage=SpokenLanguage.UNKNOWN,
            englishEvidence=EnglishEvidence.UNVERIFIED,languageEvidenceRef=null))
        require(request.originalCapture==expectedOriginal && request.confirmedLanguage==LearningJson.wire(capture.spokenLanguage) &&
            request.confirmedLanguage in setOf("en","mixed"))
        if(row.status!="accepted")dao.transitionCaptureConfirmation(owner,row,"accepted")
    }
}
