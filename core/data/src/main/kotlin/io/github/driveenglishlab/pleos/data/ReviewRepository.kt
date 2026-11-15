package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.data.wire.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Review/manual history is persisted separately from the normal next-news cursor. */
class ReviewRepository(private val api:IncrementalLearningApi,private val dao:ReviewDao,
    private val journal:ReviewJournal,private val codecs:IncrementalContractJson) {
    private val lane=Mutex()
    suspend fun cached(scope:LearningCallScope,selectionId:String):WireReviewListeningContext? {
        if(!api.isCurrent(scope))return null
        val value=dao.context(scope.owner.profileId.toString(),scope.owner.privacyEpoch,selectionId)?:return null
        return codecs.decodeReview("ListeningContext",value.payloadJson,WireReviewListeningContext.serializer())
    }
    suspend fun read(scope:LearningCallScope,selectionId:String):LearningReply<WireReviewListeningContextResponse> = lane.withLock {
        when(val reply=api.context(scope,selectionId)) {
            is LearningReply.Failed -> reply
            is LearningReply.Stored -> { if(!api.isCurrent(scope))return@withLock LearningReply.Failed("SESSION_CHANGED",false)
                require(reply.value.context.selectionId==selectionId);save(scope,reply.value.context);reply }
        }
    }
    suspend fun replay(scope:LearningCallScope,request:WireReviewHistoryReplayRequest):LearningReply<WireReviewHistoryReplayResponse> = lane.withLock {
        if(!api.isCurrent(scope))return@withLock LearningReply.Failed("SESSION_CHANGED",false)
        val operation=journal.enqueue(scope,"history_replay",request.sourceSelectionId,"HistoryReplayRequest",request,WireReviewHistoryReplayRequest.serializer())
        when(val reply=api.replay(scope,request)) {
            is LearningReply.Failed -> reply
            is LearningReply.Stored -> {
                if(!api.isCurrent(scope))return@withLock LearningReply.Failed("SESSION_CHANGED",false)
                val context=reply.value.context
                require(context.origin=="history_replay" && context.endPolicy=="manual_history" && context.sourceSelectionId==request.sourceSelectionId)
                journal.acknowledge(scope,operation,"HistoryReplayResponse",reply.value,WireReviewHistoryReplayResponse.serializer(),contextRecord(scope,context));reply
            }
        }
    }
    suspend fun command(scope:LearningCallScope,selectionId:String,request:WireReviewReviewCommandRequest):LearningReply<WireReviewReviewCommandResponse> = lane.withLock {
        if(!api.isCurrent(scope))return@withLock LearningReply.Failed("SESSION_CHANGED",false)
        val old=cached(scope,selectionId)
        val repeated=dao.operation(request.operationId)!=null
        if(old!=null && old.version!=request.expectedContextVersion && !repeated)
            return@withLock LearningReply.Failed("CONTEXT_VERSION_CONFLICT",false)
        val operation=journal.enqueue(scope,"command",selectionId,"ReviewCommandRequest",request,WireReviewReviewCommandRequest.serializer())
        when(val reply=api.command(scope,selectionId,request)) {
            is LearningReply.Failed -> reply
            is LearningReply.Stored -> {
                if(!api.isCurrent(scope))return@withLock LearningReply.Failed("SESSION_CHANGED",false)
                val context=reply.value.context
                require(context.selectionId==selectionId || request.command=="select_regular")
                if(context.selectionId==selectionId && old!=null) {
                    require(context.version>=old.version || repeated)
                    if(old.origin=="history_replay")require(context.origin=="history_replay" && context.endPolicy=="manual_history")
                    if(context.version>=old.version && old.cancelledOfferId!=null && context.offerId==old.cancelledOfferId)require(context.endPolicy!="regular_auto")
                }
                journal.acknowledge(scope,operation,"ReviewCommandResponse",reply.value,WireReviewReviewCommandResponse.serializer(),contextRecord(scope,context));reply
            }
        }
    }
    suspend fun observe(scope:LearningCallScope,request:WireReviewSentenceObservationRequest):LearningReply<WireReviewSentenceObservationResponse> = lane.withLock {
        if(!api.isCurrent(scope))return@withLock LearningReply.Failed("SESSION_CHANGED",false)
        val operation=journal.observe(scope,request)
        when(val reply=api.observe(scope,request)) {
            is LearningReply.Failed -> reply
            is LearningReply.Stored -> {if(!api.isCurrent(scope))return@withLock LearningReply.Failed("SESSION_CHANGED",false)
                require(reply.value.observationId==request.observation.observationId && reply.value.acceptedSequence==request.observation.sequence)
                journal.acknowledge(scope,operation,"SentenceObservationResponse",reply.value,WireReviewSentenceObservationResponse.serializer());reply}
        }
    }
    suspend fun fence(scope:LearningCallScope,request:WireReviewAssistanceFenceRequest):LearningReply<WireReviewAssistanceFenceResponse> = lane.withLock {
        if(!api.isCurrent(scope))return@withLock LearningReply.Failed("SESSION_CHANGED",false)
        require(request.throughSequence<=journal.frontier(scope,request.deviceStreamId))
        val operation=journal.enqueue(scope,"fence",request.attemptId,"AssistanceFenceRequest",request,WireReviewAssistanceFenceRequest.serializer())
        when(val reply=api.fence(scope,request)) {
            is LearningReply.Failed -> reply
            is LearningReply.Stored -> {if(!api.isCurrent(scope))return@withLock LearningReply.Failed("SESSION_CHANGED",false)
                if(reply.value.state=="ready")journal.acknowledge(scope,operation,"AssistanceFenceResponse",reply.value,WireReviewAssistanceFenceResponse.serializer());reply}
        }
    }
    /** Flushes actual observers in causal order, without creating any new observation or answer. */
    suspend fun flushPendingObservations(scope:LearningCallScope,deviceStreamId:String,throughSequence:Long):LearningReply<Unit> = lane.withLock {
        if(!api.isCurrent(scope))return@withLock LearningReply.Failed("SESSION_CHANGED",false)
        require(throughSequence<=journal.frontier(scope,deviceStreamId))
        for(operation in dao.pendingObservations(scope.owner.profileId.toString(),scope.owner.privacyEpoch,deviceStreamId,throughSequence)) {
            if(operation.ownerRevision!=scope.owner.revision)return@withLock LearningReply.Failed("SESSION_CHANGED",false)
            when(operation.kind) {
                "observation" -> {
                    val request=codecs.decodeReview("SentenceObservationRequest",operation.payloadJson,WireReviewSentenceObservationRequest.serializer())
                    when(val reply=api.observe(scope,request)){
                        is LearningReply.Failed->return@withLock reply
                        is LearningReply.Stored->{require(reply.value.acceptedSequence==request.observation.sequence && reply.value.observationId==request.observation.observationId)
                            journal.acknowledge(scope,operation,"SentenceObservationResponse",reply.value,WireReviewSentenceObservationResponse.serializer())}
                    }
                }
                "explanation_observation" -> {
                    val request=codecs.decodeSource6("ExplanationObservationRequest",operation.payloadJson,WireSource6ExplanationObservationRequest.serializer())
                    when(val reply=api.call("source6","/v2/article-explanations/{operation_id}/observations","post",scope,
                        api.payload("source6","ExplanationObservationRequest",request,WireSource6ExplanationObservationRequest.serializer()),WireSource6ExplanationObservationResponse.serializer(),
                        mapOf("operation_id" to requireNotNull(operation.targetId)))){
                        is LearningReply.Failed->return@withLock reply
                        is LearningReply.Stored->{require(reply.value.acceptedSequence==request.sequence && reply.value.observationId==request.observationId)
                            val json=incrementalCanonical(codecs.parse("source6",codecs.encode("source6","ExplanationObservationResponse",reply.value,WireSource6ExplanationObservationResponse.serializer())))
                            dao.acknowledge(scope.owner,operation,json)}
                    }
                }
                else->return@withLock LearningReply.Failed("UNKNOWN_ASSISTANCE_OBSERVER",false)
            }
        }
        LearningReply.Stored(scope,Unit)
    }
    /** Re-send only observations that already exist in the durable local stream. */
    suspend fun flushOriginalObservations(scope:LearningCallScope):LearningReply<Unit> {
        if(!api.isCurrent(scope))return LearningReply.Failed("SESSION_CHANGED",false)
        val frontiers=linkedMapOf<String,Long>()
        for(row in dao.pending(scope.owner.profileId.toString(),scope.owner.privacyEpoch,scope.owner.revision)) {
            if(row.kind !in setOf("observation","explanation_observation"))continue
            require(row.ownerRevision==scope.owner.revision && row.status=="pending" && row.payloadHash==LearningJson.hash(row.payloadJson))
            val pair=when(row.kind) {
                "observation"->{val request=codecs.decodeReview("SentenceObservationRequest",row.payloadJson,WireReviewSentenceObservationRequest.serializer())
                    request.observation.deviceStreamId to request.observation.sequence}
                "explanation_observation"->{val request=codecs.decodeSource6("ExplanationObservationRequest",row.payloadJson,WireSource6ExplanationObservationRequest.serializer())
                    request.deviceStreamId to request.sequence}
                else->continue
            }
            frontiers[pair.first]=maxOf(frontiers[pair.first]?:0,pair.second)
        }
        for((stream,through) in frontiers) when(val reply=flushPendingObservations(scope,stream,through)) {
            is LearningReply.Failed->return reply
            is LearningReply.Stored->Unit
        }
        return LearningReply.Stored(scope,Unit)
    }

    /** Original op/CAS only. Stored context is not an instruction to reopen entry UI.
     * Selection/resume/history commands remain explicit user retries: replaying them
     * in the background could create a new selection or select a resume pointer. */
    suspend fun flushOriginalCommands(scope:LearningCallScope):LearningReply<Unit> = lane.withLock {
        if(!api.isCurrent(scope))return@withLock LearningReply.Failed("SESSION_CHANGED",false)
        for(row in dao.pending(scope.owner.profileId.toString(),scope.owner.privacyEpoch,scope.owner.revision).filter{it.kind=="command"}) {
            if(!api.isCurrent(scope))return@withLock LearningReply.Failed("SESSION_CHANGED",false)
            require(row.profileId==scope.owner.profileId.toString() && row.privacyEpoch==scope.owner.privacyEpoch && row.ownerRevision==scope.owner.revision &&
                row.status=="pending" && row.payloadHash==LearningJson.hash(row.payloadJson))
            val request=codecs.decodeReview("ReviewCommandRequest",row.payloadJson,WireReviewReviewCommandRequest.serializer())
            require(request.operationId==row.operationId && request.profileId==row.profileId && request.learningEpoch==row.privacyEpoch)
            if(request.command in setOf("select_regular","resume_original"))continue
            val selection=requireNotNull(row.targetId)
            when(val reply=api.command(scope,selection,request)) {
                is LearningReply.Failed->return@withLock reply
                is LearningReply.Stored->{
                    if(reply.scope!=scope || !api.isCurrent(scope))return@withLock LearningReply.Failed("SESSION_CHANGED",false)
                    require(reply.value.operationId==row.operationId && reply.value.profileId==row.profileId && reply.value.learningEpoch==row.privacyEpoch &&
                        reply.value.context.selectionId==selection)
                    // ACK only: do not project cached entry/preview visibility during reconnect.
                    journal.acknowledge(scope,row,"ReviewCommandResponse",reply.value,WireReviewReviewCommandResponse.serializer())
                }
            }
        }
        LearningReply.Stored(scope,Unit)
    }

    /** ACK proves the new lesson exists; caller keeps the old active lesson until resume CAS succeeds. */
    suspend fun acknowledgeFreshSync(scope:LearningCallScope,id:String,receipt:LearningReply.Stored<WireSource6PersonalSyncReceipt>) {
        require(api.isCurrent(scope) && receipt.scope==scope)
        val intent=dao.freshIntent(id)?:error("Missing immutable fresh intent.")
        require(receipt.value.profileContext.profileId==intent.profileId && receipt.value.profileContext.privacyEpoch==intent.privacyEpoch &&
            receipt.value.receipts.any { it.recordId==intent.newLessonId && it.version>=1 && it.outcome in setOf("created","duplicate","updated") })
        dao.transitionFresh(scope.owner,id,"prepared","synced")
    }
    suspend fun acknowledgeFreshSelection(scope:LearningCallScope,id:String,receipt:LearningReply.Stored<WireSource6PersonalProfileView>) {
        require(api.isCurrent(scope) && receipt.scope==scope);val intent=dao.freshIntent(id)?:error("Missing immutable fresh intent.")
        require(receipt.value.profileContext.profileId==intent.profileId && receipt.value.profileContext.privacyEpoch==intent.privacyEpoch &&
            receipt.value.activeResume?.kind=="practice" && receipt.value.activeResume?.recordId==intent.newLessonId && receipt.value.resumeVersion>intent.expectedResumeVersion)
        dao.transitionFresh(scope.owner,id,"synced","selected")
    }
    private suspend fun save(scope:LearningCallScope,context:WireReviewListeningContext) {
        dao.storeContext(scope.owner,contextRecord(scope,context))
    }
    private fun contextRecord(scope:LearningCallScope,context:WireReviewListeningContext):LocalReviewContext {
        require(context.profileId==scope.owner.profileId.toString() && context.learningEpoch==scope.owner.privacyEpoch)
        val json=incrementalCanonical(codecs.parse("review",codecs.encode("review","ListeningContext",context,WireReviewListeningContext.serializer())))
        return LocalReviewContext(context.profileId,context.learningEpoch,context.selectionId,context.version,json,LearningJson.hash(json))
    }
}
