package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.data.wire.*
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.*
import java.util.UUID

data class PendingArticleQuestionDelivery(val operationId:String,val payloadHash:String,val result:WireSource6InferenceQAResponse,
    val intent:String,val outputId:UUID,val evidence:String)

internal fun incrementalCanonical(value: JsonElement): String = when (value) {
    is JsonObject -> value.entries.sortedBy { it.key }.joinToString(",", "{", "}") { JsonPrimitive(it.key).toString()+":"+incrementalCanonical(it.value) }
    is JsonArray -> value.joinToString(",", "[", "]") { incrementalCanonical(it) }
    else -> value.toString()
}

/** Immutable requests survive process death. Nothing in this journal means audio was delivered. */
class ReviewJournal(private val dao: ReviewDao, private val codecs: IncrementalContractJson) {
    /** Private device event, not a fabricated public observation or completed-delivery receipt. */
    suspend fun recordQuestionDelivery(scope:LearningCallScope,result:WireSource6InferenceQAResponse,intent:String,outputId:UUID,evidence:String){
        val response=codecs.parse("source6",codecs.encode("source6","InferenceQAResponse",result,WireSource6InferenceQAResponse.serializer()))
        val json=incrementalCanonical(buildJsonObject{put("response",response);put("intent",intent);put("output_id",outputId.toString());put("evidence",evidence)})
        dao.queue(scope.owner,LocalReviewOperation(stableRecordId("qa-delivery:$outputId").toString(),scope.owner.profileId.toString(),scope.owner.privacyEpoch,scope.owner.revision,
            "qa_delivery_unverified",result.operationId,json,LearningJson.hash(json)))
    }
    suspend fun pendingQuestionDeliveries(scope:LearningCallScope):List<PendingArticleQuestionDelivery> =
        dao.unresolvedQuestionDeliveries(scope.owner.profileId.toString(),scope.owner.privacyEpoch).map{row->
            require(row.payloadHash==LearningJson.hash(row.payloadJson));val value=codecs.parse("source6",row.payloadJson).jsonObject
            require(value.keys==setOf("response","intent","output_id","evidence"))
            PendingArticleQuestionDelivery(row.operationId,row.payloadHash,codecs.decodeSource6("InferenceQAResponse",value.getValue("response").toString(),WireSource6InferenceQAResponse.serializer()),
                value.getValue("intent").jsonPrimitive.content,UUID.fromString(value.getValue("output_id").jsonPrimitive.content),value.getValue("evidence").jsonPrimitive.content)
        }
    suspend fun questionDeliveryJoined(scope:LearningCallScope,value:PendingArticleQuestionDelivery)=dao.joinQuestionDelivery(scope.owner,value.operationId,value.payloadHash)
    suspend fun <T> enqueue(scope: LearningCallScope, kind: String, targetId: String?, definition: String,
        request: T, serializer: SerializationStrategy<T>): LocalReviewOperation {
        val json = incrementalCanonical(codecs.parse("review", codecs.encode("review",definition,request,serializer)))
        val payload = codecs.parse("review",json).jsonObject
        val operation = LocalReviewOperation(payload["operation_id"]!!.jsonPrimitive.content,scope.owner.profileId.toString(),scope.owner.privacyEpoch,
            scope.owner.revision,kind,targetId,json,LearningJson.hash(json))
        return dao.queue(scope.owner,operation)
    }
    suspend fun observe(scope:LearningCallScope,request:WireReviewSentenceObservationRequest):LocalReviewOperation {
        val json=incrementalCanonical(codecs.parse("review",codecs.encode("review","SentenceObservationRequest",request,WireReviewSentenceObservationRequest.serializer())))
        val operation=LocalReviewOperation(request.operationId,scope.owner.profileId.toString(),scope.owner.privacyEpoch,scope.owner.revision,"observation",null,json,LearningJson.hash(json))
        val observation=request.observation
        dao.queueObservation(scope.owner,operation,LocalReviewObservation(operation.profileId,operation.privacyEpoch,observation.deviceStreamId,
            observation.sequence,observation.observationId,operation.operationId,operation.payloadHash))
        return dao.operation(operation.operationId)!!
    }
    suspend fun frontier(scope:LearningCallScope,streamId:String)=dao.frontier(scope.owner.profileId.toString(),scope.owner.privacyEpoch,streamId)
    suspend fun explanation(scope:LearningCallScope,questionOperationId:String,request:WireSource6ExplanationObservationRequest):LocalReviewOperation {
        val json=incrementalCanonical(codecs.parse("source6",codecs.encode("source6","ExplanationObservationRequest",request,WireSource6ExplanationObservationRequest.serializer())))
        val operation=LocalReviewOperation(request.observationId,scope.owner.profileId.toString(),scope.owner.privacyEpoch,scope.owner.revision,
            "explanation_observation",questionOperationId,json,LearningJson.hash(json))
        dao.queueObservation(scope.owner,operation,LocalReviewObservation(operation.profileId,operation.privacyEpoch,request.deviceStreamId,
            request.sequence,request.observationId,operation.operationId,operation.payloadHash))
        return dao.operation(operation.operationId)!!
    }
    suspend fun pending(scope:LearningCallScope)=dao.pending(scope.owner.profileId.toString(),scope.owner.privacyEpoch,scope.owner.revision)
    suspend fun <T> acknowledge(scope:LearningCallScope,operation:LocalReviewOperation,definition:String,value:T,serializer:SerializationStrategy<T>,context:LocalReviewContext?=null) {
        val json=incrementalCanonical(codecs.parse("review",codecs.encode("review",definition,value,serializer)))
        if(context==null)dao.acknowledge(scope.owner,operation,json) else dao.acknowledgeContext(scope.owner,operation,json,context)
    }
    suspend fun prepareFresh(scope:LearningCallScope,request:WireReviewFreshPracticeIntent):LocalFreshPracticeIntent {
        val json=codecs.encode("review","FreshPracticeIntent",request,WireReviewFreshPracticeIntent.serializer())
        require(request.profileId==scope.owner.profileId.toString() && request.learningEpoch==scope.owner.privacyEpoch && request.state=="prepared")
        val pinned=incrementalCanonical(codecs.parse("review",json).jsonObject["pinned_ref"]!!)
        return dao.prepareFresh(scope.owner,LocalFreshPracticeIntent(request.operationId,request.profileId,request.learningEpoch,scope.owner.revision,
            request.sourceLessonId,request.newLessonId,request.expectedResumeVersion,pinned,LearningJson.hash(incrementalCanonical(codecs.parse("review",json)))))
    }
}
