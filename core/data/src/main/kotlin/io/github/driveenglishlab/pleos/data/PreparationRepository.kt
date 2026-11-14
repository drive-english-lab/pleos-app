package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.data.wire.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PreparationReadyIntent(val requestId:String,val contextToken:String,val desiredAction:String,
    val states:List<WirePreparationComponentState>,val requestVersion:Long)

/** Request waiters belong to a user context; common jobs do not own a playback intent. */
class PreparationRepository(private val api:IncrementalLearningApi,private val dao:ReviewDao,
    private val codecs:IncrementalContractJson) {
    private val lane=Mutex()
    suspend fun request(scope:LearningCallScope,value:WirePreparationPreparationRequest):LearningReply<WirePreparationPreparationResponse> = lane.withLock {
        if(!api.isCurrent(scope))return@withLock LearningReply.Failed("SESSION_CHANGED",false)
        val json=incrementalCanonical(codecs.parse("preparation",codecs.encode("preparation","PreparationRequest",value,WirePreparationPreparationRequest.serializer())))
        dao.storeWaiter(scope.owner,LocalPreparationWaiter(value.requestId,scope.owner.profileId.toString(),scope.owner.privacyEpoch,
            scope.owner.revision,value.contextToken,value.desiredAction,json,LearningJson.hash(json)))
        when(val reply=api.prepare(scope,value)) {
            is LearningReply.Failed -> reply
            is LearningReply.Stored -> {accept(scope,value.requestId,reply.value);reply}
        }
    }
    suspend fun read(scope:LearningCallScope,requestId:String):LearningReply<WirePreparationPreparationResponse> = lane.withLock {
        if(!api.isCurrent(scope))return@withLock LearningReply.Failed("SESSION_CHANGED",false)
        requireOwned(scope,requestId)
        when(val reply=api.preparation(scope,requestId)) {
            is LearningReply.Failed -> reply
            is LearningReply.Stored -> {accept(scope,requestId,reply.value);reply}
        }
    }
    suspend fun retry(scope:LearningCallScope,requestId:String,value:WirePreparationPreparationRetryRequest):LearningReply<WirePreparationPreparationRetryResponse> = lane.withLock {
        if(!api.isCurrent(scope))return@withLock LearningReply.Failed("SESSION_CHANGED",false)
        val waiter=requireOwned(scope,requestId)
        require(!waiter.cancelled && (value.expectedRequestVersion==waiter.requestVersion || dao.operation(value.operationId)!=null))
        val json=incrementalCanonical(codecs.parse("preparation",codecs.encode("preparation","PreparationRetryRequest",value,WirePreparationPreparationRetryRequest.serializer())))
        val operation=dao.queue(scope.owner,LocalReviewOperation(value.operationId,waiter.profileId,waiter.privacyEpoch,waiter.ownerRevision,
            "preparation_retry",requestId,json,LearningJson.hash(json)))
        when(val reply=api.retry(scope,requestId,value)) {
            is LearningReply.Failed -> reply
            is LearningReply.Stored -> {
                if(!api.isCurrent(scope))return@withLock LearningReply.Failed("SESSION_CHANGED",false)
                require(reply.value.requestId==requestId && reply.value.requestVersion>value.expectedRequestVersion)
                val response=incrementalCanonical(codecs.parse("preparation",codecs.encode("preparation","PreparationRetryResponse",reply.value,WirePreparationPreparationRetryResponse.serializer())))
                dao.acknowledge(scope.owner,operation,response)
                // Retry receipts have no job_refs. Preserve the last full public read and fetch
                // the new version explicitly; never fabricate a PreparationResponse.
                reply
            }
        }
    }
    suspend fun cancel(scope:LearningCallScope,id:String){if(api.isCurrent(scope))dao.cancelWaiter(scope.owner,id)}
    /** Still requires Host's current visibility/vehicle/lease and actual TTS check before output. */
    suspend fun readyIntent(scope:LearningCallScope,id:String,currentContextToken:String,requiredKinds:Set<String>):PreparationReadyIntent? {
        if(!api.isCurrent(scope))return null
        val waiter=requireOwned(scope,id)
        if(waiter.cancelled || waiter.contextToken!=currentContextToken || waiter.desiredAction=="prepare_only")return null
        val source=waiter.responseJson?:return null
        val value=codecs.decodePreparation("PreparationResponse",source,WirePreparationPreparationResponse.serializer())
        if(requiredKinds.isEmpty() || requiredKinds.any { kind -> value.states.none { it.kind==kind && it.status=="ready" && it.componentId!=null && it.payloadHash!=null && it.validationRevision!=null } })return null
        return PreparationReadyIntent(id,waiter.contextToken,waiter.desiredAction,value.states,value.requestVersion)
    }
    private suspend fun accept(scope:LearningCallScope,id:String,value:WirePreparationPreparationResponse) {
        require(api.isCurrent(scope) && value.requestId==id)
        val waiter=requireOwned(scope,id)
        val request=codecs.decodePreparation("PreparationRequest",waiter.requestJson,WirePreparationPreparationRequest.serializer())
        require(value.states.map{it.key}.distinct().size==value.states.size && value.states.all{state->request.components.any{it.kind==state.kind}})
        val json=incrementalCanonical(codecs.parse("preparation",codecs.encode("preparation","PreparationResponse",value,WirePreparationPreparationResponse.serializer())))
        dao.acceptPreparation(scope.owner,id,value.requestVersion,json)
    }
    private suspend fun requireOwned(scope:LearningCallScope,id:String):LocalPreparationWaiter {
        val value=dao.waiter(id)?:error("Unknown immutable waiter.")
        require(value.profileId==scope.owner.profileId.toString() && value.privacyEpoch==scope.owner.privacyEpoch && value.ownerRevision==scope.owner.revision)
        return value
    }
}
