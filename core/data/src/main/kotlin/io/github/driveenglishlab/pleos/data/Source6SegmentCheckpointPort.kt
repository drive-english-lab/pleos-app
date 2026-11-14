package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.data.wire.*
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.*

/** Actual existing source6 sync/start adapters. No invented endpoint or client completion. */
class Source6SegmentCheckpointPort(private val api:IncrementalLearningApi,private val sessions:LearningSessionSource,
    private val dao:ReviewDao,private val codecs:IncrementalContractJson):SegmentCheckpointPort {
    private val lane=Mutex()
    private suspend fun scope(owner:OwnerContext):LearningCallScope? = sessions.current()?.takeIf{it.owner==owner && api.isCurrent(it)}

    override suspend fun cursor(write:SegmentCursorWrite):WireSource6PersonalReadCheckpoint?=lane.withLock {
        val scope=scope(write.owner)?:return@withLock null
        // The closed public start contract cannot select an auxiliary purpose. Keep these
        // unavailable until its existing server/context resolver can prove that purpose.
        if(write.purpose !in setOf(SegmentPurpose.MAIN,SegmentPurpose.FOLLOWUP))return@withLock null
        val id=write.operationId.toString()
        val old=dao.operation(id)
        val request:WireSource6PersonalSyncRequest
        val operation:LocalReviewOperation
        if(old!=null){
            require(old.kind=="source6_cursor" && old.targetId==write.selectionId.toString() && old.profileId==scope.owner.profileId.toString() && old.privacyEpoch==scope.owner.privacyEpoch && old.ownerRevision==scope.owner.revision)
            request=codecs.decodeSource6("PersonalSyncRequest",old.payloadJson,WireSource6PersonalSyncRequest.serializer())
            val event=request.events.single() as? WireSource6PersonalCheckpointEvent?:return@withLock null
            require(event.payload.selectionId==write.selectionId.toString() && event.payload.contentRef==write.source && event.payload.cursor==write.cursor)
            operation=old
        }else{
            val current=readCheckpoint(scope,write.selectionId.toString())?:return@withLock null
            require(current.payload.contentRef==write.source)
            val event=WireSource6PersonalCheckpointEvent(stableRecordId("segment-cursor:$id").toString(),"checkpoint",current.version,
                WireSource6PersonalCheckpointInput(current.recordId,write.source,current.payload.streamId,write.selectionId.toString(),write.cursor))
            request=WireSource6PersonalSyncRequest(id,profile(scope),listOf(event),"6")
            operation=queue(scope,"source6_cursor",write.selectionId.toString(),"PersonalSyncRequest",id,request,WireSource6PersonalSyncRequest.serializer())
        }
        val event=request.events.single() as WireSource6PersonalCheckpointEvent
        val receipt=sync(scope,request)?:return@withLock null
        val ack=receipt.receipts.singleOrNull{it.eventId==event.eventId && it.recordId==event.payload.checkpointId && it.version>=1}?:return@withLock null
        val checkpoint=readCheckpoint(scope,write.selectionId.toString(),ack.version)?:return@withLock null
        if(checkpoint.payload.contentRef!=write.source || checkpoint.payload.cursor!=write.cursor || checkpoint.version!=ack.version)return@withLock null
        acknowledge(scope,operation,"PersonalSyncReceipt",receipt,WireSource6PersonalSyncReceipt.serializer())
        checkpoint
    }
    override suspend fun start(owner:OwnerContext,request:WireSource6PersonalPlaybackRunStart):WireSource6PersonalPlaybackRunReceipt?=lane.withLock {
        val scope=scope(owner)?:return@withLock null
        val operation=queue(scope,"source6_run_start",request.checkpointId,"PersonalPlaybackRunStart",request.operationId,request,WireSource6PersonalPlaybackRunStart.serializer())
        when(val reply=api.call("source6","/v2/playback-runs","post",scope,api.payload("source6","PersonalPlaybackRunStart",request,WireSource6PersonalPlaybackRunStart.serializer()),WireSource6PersonalPlaybackRunReceipt.serializer())){
            is LearningReply.Failed->null
            is LearningReply.Stored->{
                if(!api.isCurrent(scope))return@withLock null
                require(reply.value.checkpoint.recordId==request.checkpointId && reply.value.checkpoint.version>request.expectedCheckpointVersion)
                val run=reply.value.run as? WireSource6SegmentRunRecord6?:return@withLock null
                if(run.purpose !in setOf("main","followup"))return@withLock null
                storeCheckpoint(scope,reply.value.checkpoint)
                acknowledge(scope,operation,"PersonalPlaybackRunReceipt",reply.value,WireSource6PersonalPlaybackRunReceipt.serializer())
                reply.value
            }
        }
    }
    override suspend fun observation(owner:OwnerContext,eventId:UUID,value:WireSource6SegmentObservationInput6):Boolean=lane.withLock {
        val scope=scope(owner)?:return@withLock false
        require(value.eventId==eventId.toString())
        val event=WireSource6PersonalPlaybackObservationEvent(eventId.toString(),"playback_observation",JsonNull,value)
        val id=stableRecordId("segment-observation-sync:$eventId").toString()
        val request=WireSource6PersonalSyncRequest(id,profile(scope),listOf(event),"6")
        val operation=queue(scope,"source6_observation",value.observationId,"PersonalSyncRequest",id,request,WireSource6PersonalSyncRequest.serializer())
        val receipt=sync(scope,request)?:return@withLock false
        require(receipt.receipts.any{it.eventId==eventId.toString() && it.recordId==value.observationId && it.version>=1})
        acknowledge(scope,operation,"PersonalSyncReceipt",receipt,WireSource6PersonalSyncReceipt.serializer())
        true
    }
    private suspend fun sync(scope:LearningCallScope,request:WireSource6PersonalSyncRequest):WireSource6PersonalSyncReceipt? = when(val reply=api.call("source6","/v2/profile/sync","post",scope,
        api.payload("source6","PersonalSyncRequest",request,WireSource6PersonalSyncRequest.serializer()),WireSource6PersonalSyncReceipt.serializer())){
        is LearningReply.Failed->null
        is LearningReply.Stored->reply.value.takeIf{api.isCurrent(scope)}
    }
    private suspend fun readCheckpoint(scope:LearningCallScope,selectionId:String,minimumVersion:Long=0):WireSource6PersonalReadCheckpoint? {
        return withTimeoutOrNull(8_000) { readCheckpointPages(scope,selectionId,minimumVersion) }
    }
    private suspend fun readCheckpointPages(scope:LearningCallScope,selectionId:String,minimumVersion:Long):WireSource6PersonalReadCheckpoint? {
        var cursor:String?=null
        var found:WireSource6PersonalReadCheckpoint?=null
        repeat(100){
            val response=api.call("source6","/v2/profile/sync","get",scope,null,WireSource6PersonalSyncPage.serializer(),queryArguments=mapOf(
                "client_privacy_epoch" to scope.owner.privacyEpoch.toString(),"cursor" to cursor,"limit" to "50"))
            if(response !is LearningReply.Stored || response.value.resetRequired)return null
            for(record in response.value.records){
                if(record is WireSource6PersonalReadCheckpoint){storeCheckpoint(scope,record)
                    if(record.payload.selectionId==selectionId && record.version>=minimumVersion && (found?.version?:0)<record.version)found=record}
            }
            val next=response.value.cursor
            if(response.value.records.size<50 || next==null || next==cursor)return found
            cursor=next
        }
        return null
    }
    private suspend fun storeCheckpoint(scope:LearningCallScope,checkpoint:WireSource6PersonalReadCheckpoint){
        val json=incrementalCanonical(codecs.parse("source6",codecs.encode("source6","PersonalReadCheckpoint",checkpoint,WireSource6PersonalReadCheckpoint.serializer())))
        dao.storeSource6(scope.owner,LocalSource6Record(scope.owner.profileId.toString(),scope.owner.privacyEpoch,"checkpoint",checkpoint.recordId,checkpoint.version,json,LearningJson.hash(json)))
    }
    private suspend fun <T> queue(scope:LearningCallScope,kind:String,target:String,definition:String,id:String,value:T,serializer:SerializationStrategy<T>):LocalReviewOperation {
        val json=incrementalCanonical(codecs.parse("source6",codecs.encode("source6",definition,value,serializer)))
        return dao.queue(scope.owner,LocalReviewOperation(id,scope.owner.profileId.toString(),scope.owner.privacyEpoch,scope.owner.revision,kind,target,json,LearningJson.hash(json)))
    }
    private suspend fun <T> acknowledge(scope:LearningCallScope,operation:LocalReviewOperation,definition:String,value:T,serializer:SerializationStrategy<T>){
        require(api.isCurrent(scope));val json=incrementalCanonical(codecs.parse("source6",codecs.encode("source6",definition,value,serializer)))
        dao.acknowledge(scope.owner,operation,json)
    }
    private fun profile(scope:LearningCallScope)=WireSource6CommonProfileContext(scope.owner.profileId.toString(),scope.owner.privacyEpoch)
}
