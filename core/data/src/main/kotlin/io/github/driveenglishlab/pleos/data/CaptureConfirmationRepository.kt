package io.github.driveenglishlab.pleos.data

import android.content.Context
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Only explicit foreground issuance. The operation journal contains metadata, never unaccepted text. */
class CaptureConfirmationRepository(context:Context,private val api:IncrementalLearningApi,private val database:LearningDatabase) {
    private val codecs=IncrementalContractJson(context.applicationContext)
    private val dao=database.review()
    private val lane=Mutex()

    suspend fun prepare(scope:LearningCallScope,lease:CaptureLease,originalCapture:Capture,text:String,
        confirmationToken:UUID,sourceProtocol:CaptureConfirmationProtocol,confirmedLanguage:SpokenLanguage=SpokenLanguage.EN):LearningReply<CaptureConfirmationIntent> = guarded {
        require(scope.owner==lease.owner);CaptureConfirmationWireRules.original(originalCapture)
        require(confirmedLanguage in setOf(SpokenLanguage.EN,SpokenLanguage.MIXED))
        if(!api.isCurrent(scope))return@guarded LearningReply.Failed("SESSION_CHANGED",false)
        val normalized=text.trim()
        val request=CaptureConfirmationRequest(1,CaptureConfirmationProfile(scope.owner.profileId.toString(),scope.owner.privacyEpoch),
            lease.captureId.toString(),lease.captureId.toString(),lease.attemptId.toString(),lease.lessonId.toString(),sourceProtocol.wire,
            CaptureConfirmationWireRules.ref(lease.task,sourceProtocol),CaptureConfirmationWireRules.capture(originalCapture),
            CaptureConfirmationHashes.answer(normalized),LearningJson.wire(confirmedLanguage))
        val encoded=codecs.encode("capture-confirmation","ConfirmationRequest",request,CaptureConfirmationRequest.serializer())
        val wrapper=buildJsonObject { put("request",codecs.parse("capture-confirmation",encoded));put("confirmation_token",confirmationToken.toString()) }.toString()
        val row=dao.queue(scope.owner,LocalReviewOperation(request.operationId,scope.owner.profileId.toString(),scope.owner.privacyEpoch,
            scope.owner.revision,CaptureConfirmationWireRules.KIND,request.attemptId,wrapper,LearningJson.hash(wrapper)))
        require(row.status in setOf("pending","receipt_ready"))
        LearningReply.Stored(scope,intent(row))
    }

    suspend fun issue(scope:LearningCallScope,operationId:UUID,confirmationToken:UUID,
        mayAct:suspend()->Boolean):LearningReply<CaptureConfirmationReceipt> = guarded {
        if(!api.isCurrent(scope))return@guarded LearningReply.Failed("SESSION_CHANGED",false)
        if(!mayAct()){cancel(scope.owner,operationId,confirmationToken);return@guarded LearningReply.Failed("CONFIRMATION_CONTEXT_CHANGED",false)}
        val row=requireRow(scope.owner,operationId,confirmationToken)
        if(row.status !in setOf("pending","receipt_ready"))return@guarded LearningReply.Failed("CONFIRMATION_CLOSED",false)
        val request=intent(row).request
        row.receiptJson?.let { json ->
            val receipt=codecs.decode("capture-confirmation","ConfirmationReceipt",json,CaptureConfirmationReceipt.serializer())
            CaptureConfirmationWireRules.receipt(request,receipt)
            return@guarded if(api.isCurrent(scope) && mayAct())LearningReply.Stored(scope,receipt) else LearningReply.Failed("CONFIRMATION_CONTEXT_CHANGED",false)
        }
        val payload=api.payload("capture-confirmation","ConfirmationRequest",request,CaptureConfirmationRequest.serializer())
        val reply=api.call("capture-confirmation","/v1/capture-language-confirmations","post",scope,payload,CaptureConfirmationReceipt.serializer())
        val receipt=when(reply) { is LearningReply.Failed -> return@guarded reply; is LearningReply.Stored -> reply.value }
        CaptureConfirmationWireRules.receipt(request,receipt)
        val json=codecs.encode("capture-confirmation","ConfirmationReceipt",receipt,CaptureConfirmationReceipt.serializer())
        if(!api.isCurrent(scope))return@guarded LearningReply.Failed("SESSION_CHANGED",false)
        val live=mayAct()
        val current=dao.storeCaptureConfirmationReceipt(scope.owner,operationId.toString(),row.payloadHash,json,live)
        if(live && current.status=="receipt_ready")LearningReply.Stored(scope,receipt) else LearningReply.Failed("CONFIRMATION_CONTEXT_CHANGED",false)
    }

    /** Cancellation never waits for an HTTP lane; its durable CAS wins over a late response. */
    suspend fun cancel(owner:OwnerContext,operationId:UUID,confirmationToken:UUID,orphaned:Boolean=false) {
        try {
            if(!dao.isCurrentOwner(owner))return
            val row=dao.operation(operationId.toString())?:return // ASKING may close before any positive intent.
            if(row.profileId!=owner.profileId.toString() || row.privacyEpoch!=owner.privacyEpoch || row.ownerRevision!=owner.revision || row.kind!=CaptureConfirmationWireRules.KIND)return
            if(intent(row).confirmationToken!=confirmationToken)return
            dao.closeCaptureConfirmation(owner,operationId.toString(),row.payloadHash,orphaned)
        } catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){
            // No live candidate remains. Leave uncertain original metadata inert; cold restore retries orphaning.
            // This is not an acknowledgement of a successful storage transition.
        }
    }
    /** Cold restore cannot recreate a RAM candidate, UI token grant, answer text, or HTTP request. */
    suspend fun orphanUnaccepted(owner:OwnerContext) {
        if(!dao.isCurrentOwner(owner))return
        for(row in dao.captureConfirmations(owner.profileId.toString(),owner.privacyEpoch,owner.revision))
            if(row.status in setOf("pending","receipt_ready"))dao.closeCaptureConfirmation(owner,row.operationId,row.payloadHash,true)
    }
    private suspend fun requireRow(owner:OwnerContext,id:UUID,token:UUID):LocalReviewOperation {
        require(dao.isCurrentOwner(owner))
        val row=requireNotNull(dao.operation(id.toString()))
        require(row.profileId==owner.profileId.toString() && row.privacyEpoch==owner.privacyEpoch && row.ownerRevision==owner.revision && row.kind==CaptureConfirmationWireRules.KIND)
        require(intent(row).confirmationToken==token);return row
    }
    private fun intent(row:LocalReviewOperation):CaptureConfirmationIntent {
        require(row.payloadHash==LearningJson.hash(row.payloadJson))
        val wrapper=learningJson.parseToJsonElement(row.payloadJson).jsonObject
        require(wrapper.keys==setOf("request","confirmation_token"))
        val request=codecs.decode("capture-confirmation","ConfirmationRequest",wrapper.getValue("request").toString(),CaptureConfirmationRequest.serializer())
        require(request.operationId==row.operationId && request.attemptId==row.targetId && request.profileContext==CaptureConfirmationProfile(row.profileId,row.privacyEpoch))
        return CaptureConfirmationIntent(UUID.fromString(row.operationId),UUID.fromString(wrapper.getValue("confirmation_token").jsonPrimitive.content),
            request,CaptureConfirmationHashes.binding(wrapper.getValue("request").jsonObject),row.status)
    }
    private suspend fun <T> guarded(block:suspend()->LearningReply<T>):LearningReply<T> = lane.withLock {
        try { block() } catch(cancelled:CancellationException){throw cancelled}
        catch(_:IllegalArgumentException){LearningReply.Failed("INVALID_CONFIRMATION_BINDING",false)}
        catch(_:Exception){LearningReply.Failed("CONFIRMATION_STORAGE_UNAVAILABLE",true)}
    }
}
