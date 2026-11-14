package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.data.wire.*
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Immutable record pages never ACK feedback, register support or select active resume. */
data class SpeakingRecordsState(val owner: OwnerContext? = null, val page: WireProgressSpeakingRecordPage? = null,
    val detail: WireProgressSpeakingRecordView? = null, val loading: Boolean = false, val failed: Boolean = false,
    val locallyHidden: Set<String> = emptySet(), val unhidePending: Set<String> = emptySet(), val conflicts: Set<String> = emptySet()) {
    val visibleItems get() = page?.items?.filter { page.visibilityFilter == "hidden" || it.lessonId !in locallyHidden && it.lessonId !in unhidePending } ?: emptyList()
}

class SpeakingRecordsRepository(private val api: LearningProgressApi,private val database: LearningDatabase,
    private val current: suspend (OwnerContext)->Boolean) {
    private val lane=Mutex()
    private val mutable=MutableStateFlow(SpeakingRecordsState())
    val state=mutable.asStateFlow()
    private val dao get()=database.progress()
    suspend fun bind(scope: LearningCallScope) {
        if (!current(scope.owner)) return
        val cache=dao.cache(scope.owner.profileId.toString(),scope.owner.privacyEpoch,"speaking_page","visible")
        mutable.value=SpeakingRecordsState(scope.owner,cache?.let { featureJson.decodeFromString(WireProgressSpeakingRecordPage.serializer(),it.payloadJson) })
        projectPending(scope)
    }
    suspend fun clear() { mutable.value=SpeakingRecordsState() }
    suspend fun refresh(scope: LearningCallScope,more: Boolean=false,visibility: String="visible"): LearningReply<Unit> = lane.withLock {
        if (!current(scope.owner) || mutable.value.owner!=scope.owner) return@withLock LearningReply.Failed("SESSION_CHANGED",false)
        val old=mutable.value.page
        val cursor=if (more) old?.nextCursor ?: return@withLock LearningReply.Stored(scope,Unit) else null
        mutable.value=mutable.value.copy(loading=true,failed=false)
        when(val result=api.listSpeakingRecords(scope,scope.owner.privacyEpoch,visibility,cursor,20)) {
            is LearningReply.Failed -> {
                if (current(scope.owner)) mutable.value=mutable.value.copy(loading=false,failed=true,
                    page=if (result.code in setOf("SNAPSHOT_CHANGED","RECORD_CONFLICT") && more) null else old)
                result
            }
            is LearningReply.Stored -> {
                if (!current(scope.owner)) return@withLock LearningReply.Failed("SESSION_CHANGED",false)
                if (more && old?.snapshotRevision!=result.value.snapshotRevision) {
                    mutable.value=mutable.value.copy(page=null,loading=false,failed=true)
                    return@withLock LearningReply.Failed("SNAPSHOT_CHANGED",true)
                }
                val page=if (more) result.value.copy(items=(old!!.items+result.value.items).distinctBy { it.lessonId }) else result.value
                // Persist the canonical transport page; aggregated scroll state is presentation-only.
                dao.saveCache(scope.owner,LocalProgressCache(scope.owner.profileId.toString(),scope.owner.privacyEpoch,"speaking_page",if (more) "$visibility/${LearningJson.hash(requireNotNull(cursor))}" else visibility,
                    result.value.snapshotRevision,featureJson.encodeToString(WireProgressSpeakingRecordPage.serializer(),result.value)))
                mutable.value=mutable.value.copy(page=page,loading=false,failed=false)
                projectPending(scope)
                LearningReply.Stored(scope,Unit)
            }
        }
    }
    suspend fun detail(scope: LearningCallScope,id: UUID): LearningReply<Unit> {
        if (!current(scope.owner)) return LearningReply.Failed("SESSION_CHANGED",false)
        return when(val result=api.readSpeakingRecord(scope,id,scope.owner.privacyEpoch)) {
            is LearningReply.Failed -> result
            is LearningReply.Stored -> {
                if (!current(scope.owner)) return LearningReply.Failed("SESSION_CHANGED",false)
                dao.saveCache(scope.owner,LocalProgressCache(scope.owner.profileId.toString(),scope.owner.privacyEpoch,"speaking_detail",id.toString(),result.value.snapshotRevision,
                    featureJson.encodeToString(WireProgressSpeakingRecordView.serializer(),result.value)))
                mutable.value=mutable.value.copy(detail=result.value)
                LearningReply.Stored(scope,Unit)
            }
        }
    }
    suspend fun closeDetail() { mutable.value=mutable.value.copy(detail=null) }
    suspend fun visibility(scope: LearningCallScope,id: UUID,hidden: Boolean): LearningReply<Unit> {
        val current=mutable.value
        val value=current.detail?.record?.takeIf { it.lessonId==id.toString() }?.visibility
            ?: current.page?.items?.singleOrNull { it.lessonId==id.toString() }?.visibility ?: return LearningReply.Failed("RECORD_NOT_LOADED",false)
        val request=WireProgressVisibilityRequest("5","1",UUID.randomUUID().toString(),scope.owner.profileWire(),value.version,hidden)
        val bytes=featureJson.encodeToString(WireProgressVisibilityRequest.serializer(),request)
        dao.queue(scope.owner,LocalProgressJournal(request.operationId,scope.owner.profileId.toString(),scope.owner.privacyEpoch,scope.owner.revision,
            "visibility:$id",bytes,LearningJson.hash(bytes),"pending",false))
        projectPending(scope) // Hide now; unhide waits for the authoritative ACK.
        return flush(scope)
    }
    private suspend fun projectPending(scope: LearningCallScope) {
        val rows=dao.unsettled(scope.owner.profileId.toString(),scope.owner.privacyEpoch).filter { it.kind.startsWith("visibility:") && !it.isolated }
        val latest=rows.associateBy { it.kind.removePrefix("visibility:") }
        val hidden=latest.filterValues { featureJson.decodeFromString(WireProgressVisibilityRequest.serializer(),it.payloadJson).hidden }.keys
        val restoring=latest.keys-hidden
        if (current(scope.owner) && mutable.value.owner==scope.owner) mutable.value=mutable.value.copy(locallyHidden=hidden,unhidePending=restoring,
            conflicts=latest.filterValues { it.state=="conflict" }.keys)
    }
    suspend fun flush(scope: LearningCallScope): LearningReply<Unit> {
        for (row in dao.pending(scope.owner.profileId.toString(),scope.owner.privacyEpoch).filter { it.kind.startsWith("visibility:") }) {
            if (!current(scope.owner) || row.ownerRevision!=scope.owner.revision) return LearningReply.Failed("SESSION_CHANGED",false)
            require(LearningJson.hash(row.payloadJson)==row.payloadHash)
            val id=UUID.fromString(row.kind.removePrefix("visibility:"))
            if (row.state=="pending" && !dao.transition(scope.owner,row.operationId,"pending","sending",false)) continue
            val request=featureJson.decodeFromString(WireProgressVisibilityRequest.serializer(),row.payloadJson)
            when(val result=api.setSpeakingRecordVisibility(scope,request,id)) {
                is LearningReply.Failed -> {
                    dao.transition(scope.owner,row.operationId,"sending",if (result.code in setOf("VERSION_MISMATCH","RECORD_CONFLICT")) "conflict" else "pending",false)
                    projectPending(scope)
                    return result
                }
                is LearningReply.Stored -> {
                    if (current(scope.owner) && mutable.value.owner==scope.owner) mutable.value=mutable.value.copy(
                        page=mutable.value.page?.let { page -> page.copy(items=page.items.filterNot { it.lessonId==id.toString() },nextCursor=null) },
                        detail=mutable.value.detail?.let { detail -> if (detail.record.lessonId==id.toString()) detail.copy(record=detail.record.copy(visibility=result.value.visibility)) else detail })
                    dao.transition(scope.owner,row.operationId,"sending","acknowledged",false)
                }
            }
        }
        projectPending(scope)
        return LearningReply.Stored(scope,Unit)
    }
}
