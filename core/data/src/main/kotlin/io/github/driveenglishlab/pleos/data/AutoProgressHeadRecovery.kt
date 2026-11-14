package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Logical head proof is separate from the native selection stream and current history context. */
data class ConfirmedAutoProgressHead(val autoProgressId: UUID, val version: Long, val selectionId: UUID,
    val nativeStreamId: UUID, val content: WireSource6ContentRef6, val proofOperationId: UUID)
sealed interface AutoProgressHeadRecovery {
    data class Confirmed(val head: ConfirmedAutoProgressHead) : AutoProgressHeadRecovery
    data class Unavailable(val reason: String) : AutoProgressHeadRecovery
}

/** Read-only local recovery against a supplied actual server progress ID/version. No guessed
 * native head, new GET/field, selection, reset, stream migration, or automatic output.
 */
class AutoProgressHeadRecoveryRepository(private val api: IncrementalLearningApi, private val codecs: IncrementalContractJson,
    private val intents: ServiceIntentDao, private val journal: ReviewDao, private val legacy:LearningDao? = null) {
    /** Uses only the existing format ACK and a real Context2 GET. A caller supplies no guessed head. */
    suspend fun recordConfirmedFormatHead(scope:LearningCallScope,operationId:UUID):AutoProgressHeadRecovery {
        if(!api.isCurrent(scope) || !journal.isCurrentOwner(scope.owner))return AutoProgressHeadRecovery.Unavailable("SESSION_CHANGED")
        return try {
            val format=acknowledgedFormat(scope,operationId.toString())
            val response=api.call("review2","/v1/review/listening-contexts/{selection_id}","get",scope,null,
                WireReview2ListeningContextResponse2.serializer(),mapOf("selection_id" to format.selectionId),
                mapOf("review_contract_version" to "2"))
            if(response is LearningReply.Failed)return AutoProgressHeadRecovery.Unavailable(response.code)
            val actual=(response as LearningReply.Stored).value
            val next=formatHead(scope,format,actual)
            val previous=recover(scope,next.autoProgressId,next.version-1)
            if(previous !is AutoProgressHeadRecovery.Confirmed)return previous
            requireFollows(previous.head,format,next)
            val existing=journal.source6Record(scope.owner.profileId.toString(),scope.owner.privacyEpoch,FORMAT_PROOF,operationId.toString())
            if(existing!=null){
                val saved=storedFormat(scope,existing)
                if(saved.head!=next)return AutoProgressHeadRecovery.Unavailable("FORMAT_HEAD_NO_LONGER_CURRENT")
            }else{
                val context=codecs.parse("review2",codecs.encode("review2","ListeningContextResponse2",actual,WireReview2ListeningContextResponse2.serializer()))
                val payload=incrementalCanonical(buildJsonObject {
                    put("operation_id",operationId.toString());put("owner_revision",scope.owner.revision)
                    put("request_hash",format.requestHash);put("receipt_hash",format.receiptHash);put("context",context)
                    format.legacyRequestJson?.let { put("legacy_request",it) };format.legacyReceiptJson?.let { put("legacy_receipt",it) }
                })
                val row=LocalSource6Record(scope.owner.profileId.toString(),scope.owner.privacyEpoch,FORMAT_PROOF,operationId.toString(),1,payload,LearningJson.hash(payload))
                if(format.legacyRequestJson!=null)journal.storeConfirmedLegacyFormatHead(scope.owner,operationId.toString(),requireNotNull(legacy),
                    format.legacyRequestJson,requireNotNull(format.legacyReceiptJson),row)
                else journal.storeConfirmedFormatHead(scope.owner,operationId.toString(),format.requestHash,format.receiptHash,row)
            }
            if(!api.isCurrent(scope) || !journal.isCurrentOwner(scope.owner))AutoProgressHeadRecovery.Unavailable("SESSION_CHANGED")
            else recover(scope,next.autoProgressId,next.version)
        }catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){AutoProgressHeadRecovery.Unavailable("INVALID_FORMAT_HEAD_PROOF")}
    }

    suspend fun recover(scope: LearningCallScope, autoProgressId: UUID, serverVersion: Long): AutoProgressHeadRecovery {
        require(serverVersion >= 1)
        if (!api.isCurrent(scope) || !journal.isCurrentOwner(scope.owner)) return AutoProgressHeadRecovery.Unavailable("SESSION_CHANGED")
        return try {
            val rows = intents.confirmedOutcomes(scope.owner.profileId.toString(), scope.owner.privacyEpoch, scope.owner.revision)
            val proofs = mutableListOf<ConfirmedAutoProgressHead>()
            for (row in rows) {
                val operation = journal.operation(row.operationId) ?: continue
                if(operation.kind=="review3_next"){
                    review3Head(scope,autoProgressId,row,operation)?.let { proofs+=it }
                    continue
                }
                if (operation.kind != "review2_next" || operation.status != "acknowledged" || operation.receiptJson != row.admissionJson ||
                    operation.profileId != row.profileId || operation.privacyEpoch != row.privacyEpoch || operation.ownerRevision != row.ownerRevision ||
                    operation.payloadHash != row.requestHash || LearningJson.hash(operation.payloadJson) != row.requestHash) continue
                val request = codecs.decode("review2", "ReviewNextRequest2", operation.payloadJson, WireReview2ReviewNextRequest2.serializer())
                if (request.streamId != autoProgressId.toString()) continue
                val admission = codecs.decode("review2", "NextAdmissionReceipt2", requireNotNull(row.admissionJson), WireReview2NextAdmissionReceipt2.serializer())
                val outcome = codecs.decode("review2", "NextOutcome2", requireNotNull(row.outcomeJson), WireReview2NextOutcome2.serializer())
                if (outcome.state !in setOf("selected", "resumed")) continue
                val selected = outcome.selection ?: continue
                val checkpoint = outcome.checkpoint ?: continue
                val context = outcome.context ?: continue
                require(admission.operationId == row.operationId && admission.requestHash == row.requestHash && request.operationId == row.operationId &&
                    request.profileContext.profileId == scope.owner.profileId.toString() && request.profileContext.privacyEpoch == scope.owner.privacyEpoch &&
                    context.profileId == scope.owner.profileId.toString() && context.learningEpoch == scope.owner.privacyEpoch &&
                    context.autoProgressRef?.autoProgressId == request.streamId && context.autoProgressRef?.version == outcome.streamVersion &&
                    context.selectionId == selected.selectionId && checkpoint.payload.selectionId == selected.selectionId && checkpoint.payload.contentRef == selected.contentRef &&
                    checkpoint.payload.streamId == selected.streamId)
                val wire = codecs.encode("review2", "Source6ContentRef6", selected.contentRef, WireReview2Source6ContentRef6.serializer())
                val source = codecs.decodeSource6Content(wire)
                proofs += ConfirmedAutoProgressHead(autoProgressId, outcome.streamVersion, UUID.fromString(selected.selectionId), UUID.fromString(selected.streamId), source, UUID.fromString(row.operationId))
            }
            val formats=journal.source6Records(scope.owner.profileId.toString(),scope.owner.privacyEpoch,FORMAT_PROOF)
                .mapNotNull { row ->
                    val body=learningJson.parseToJsonElement(row.payloadJson).jsonObject
                    if(body["owner_revision"]!=JsonPrimitive(scope.owner.revision))null else storedFormat(scope,row)
                }.filter { it.head.autoProgressId==autoProgressId && it.head.version<=serverVersion }.sortedBy { it.head.version }
            for(format in formats){
                val previous=proofs.filter { it.version==format.head.version-1 }
                if(previous.isEmpty())continue
                require(previous.map { Triple(it.selectionId,it.nativeStreamId,it.content) }.distinct().size==1)
                requireFollows(previous.first(),format.evidence,format.head)
                proofs+=format.head
            }
            if (!api.isCurrent(scope) || !journal.isCurrentOwner(scope.owner)) return AutoProgressHeadRecovery.Unavailable("SESSION_CHANGED")
            val current=proofs.filter { it.version==serverVersion }
            if (current.isEmpty()) return AutoProgressHeadRecovery.Unavailable("CONFIRMED_LOGICAL_HEAD_REQUIRED")
            val identity = current.map { Triple(it.selectionId, it.nativeStreamId, it.content) }.distinct()
            if (identity.size != 1) return AutoProgressHeadRecovery.Unavailable("CONTRADICTORY_LOGICAL_HEAD")
            AutoProgressHeadRecovery.Confirmed(current.minBy { it.proofOperationId.toString() })
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { AutoProgressHeadRecovery.Unavailable("INVALID_HEAD_PROOF") }
    }
    /** Strict v3 evidence retains its own public codec and private outcome envelope. */
    private fun review3Head(scope:LearningCallScope,autoProgressId:UUID,row:LocalPlaybackRequest,operation:LocalReviewOperation):ConfirmedAutoProgressHead?{
        if(operation.status!="acknowledged" || operation.receiptJson!=row.admissionJson || operation.profileId!=row.profileId ||
            operation.privacyEpoch!=row.privacyEpoch || operation.ownerRevision!=row.ownerRevision || operation.payloadHash!=row.requestHash ||
            LearningJson.hash(operation.payloadJson)!=row.requestHash)return null
        val request=codecs.decode("review3","ReviewNextRequest3",operation.payloadJson,WireReview3ReviewNextRequest3.serializer())
        if(request.streamId!=autoProgressId.toString())return null
        val admission=codecs.decode("review3","NextAdmissionReceipt3",requireNotNull(row.admissionJson),WireReview3NextAdmissionReceipt3.serializer())
        val envelope=codecs.parse("review3",requireNotNull(row.outcomeJson)).jsonObject
        require(envelope.keys==setOf("local_review_contract_version","outcome_projection","pending_target") && envelope["local_review_contract_version"]==JsonPrimitive(3))
        envelope.getValue("pending_target").takeUnless { it==JsonNull }?.let { codecs.validateDefinition("review3","PendingTarget3",it) }
        val outcome=codecs.decode("review3","NextOutcome3",envelope.getValue("outcome_projection").toString(),WireReview3NextOutcome3.serializer())
        require(outcome.version==row.outcomeVersion)
        if(outcome.state !in setOf("selected","resumed"))return null
        val selected=requireNotNull(outcome.selection);val checkpoint=requireNotNull(outcome.checkpoint);val context=requireNotNull(outcome.context)
        require(admission.operationId==row.operationId && admission.requestHash==row.requestHash && request.operationId==row.operationId &&
            admission.intentKind==request.intentKind && request.profileContext.profileId==scope.owner.profileId.toString() &&
            request.profileContext.privacyEpoch==scope.owner.privacyEpoch && context.profileId==scope.owner.profileId.toString() &&
            context.learningEpoch==scope.owner.privacyEpoch && context.autoProgressRef?.autoProgressId==request.streamId &&
            context.autoProgressRef?.version==outcome.streamVersion && context.selectionId==selected.selectionId &&
            checkpoint.payload.selectionId==selected.selectionId && checkpoint.payload.contentRef==selected.contentRef &&
            checkpoint.payload.streamId==selected.streamId && checkpoint.recordId==checkpoint.payload.checkpointId)
        if(outcome.state=="selected")require(selected.streamId==request.streamId)
        val ref=codecs.encode("review3","Source6ContentRef6",selected.contentRef,WireReview3Source6ContentRef6.serializer())
        return ConfirmedAutoProgressHead(autoProgressId,outcome.streamVersion,UUID.fromString(selected.selectionId),UUID.fromString(selected.streamId),
            codecs.decodeSource6Content(ref),UUID.fromString(row.operationId))
    }
    private companion object { const val FORMAT_PROOF="confirmed_auto_format_head" }
    private data class FormatEvidence(val operationId:String,val sourceSelectionId:String,val streamId:String,
        val selectionId:String,val content:WireSource6ContentRef6,val requestHash:String,val receiptHash:String,
        val legacyRequestJson:String?=null,val legacyReceiptJson:String?=null)
    private data class FormatProof(val evidence:FormatEvidence,val head:ConfirmedAutoProgressHead)

    private suspend fun acknowledgedFormat(scope:LearningCallScope,id:String):FormatEvidence{
        val row=journal.operation(id) ?: return acknowledgedLegacyFormat(scope,id)
        require(row.profileId==scope.owner.profileId.toString() && row.privacyEpoch==scope.owner.privacyEpoch && row.ownerRevision==scope.owner.revision &&
            row.kind=="source6_format" && row.status=="acknowledged" && LearningJson.hash(row.payloadJson)==row.payloadHash)
        val request=codecs.decodeSource6("PersonalFormatChangeRequest",row.payloadJson,WireSource6PersonalFormatChangeRequest.serializer())
        val value=codecs.decodeSource6("PersonalFormatChangeResponse",requireNotNull(row.receiptJson),WireSource6PersonalFormatChangeResponse.serializer())
        require(request.operationId==id && value.operationId==id && request.profileContext.profileId==row.profileId &&
            request.profileContext.privacyEpoch==row.privacyEpoch && value.profileContext==request.profileContext && row.targetId==request.sourceSelectionId)
        val selection=value.selection;val checkpoint=value.checkpoint
        require(request.expectedStreamVersion<Long.MAX_VALUE && value.streamId==request.streamId && value.streamVersion==request.expectedStreamVersion+1 &&
            selection.streamId==request.streamId && selection.previousSelectionId==request.expectedHeadSelectionId &&
            selection.formatChangeSource?.selectionId==request.sourceSelectionId && selection.formatChangeSource?.checkpointId==request.sourceCheckpointId &&
            checkpoint.version==1L && checkpoint.recordId==request.checkpointId && checkpoint.payload.checkpointId==request.checkpointId &&
            checkpoint.payload.selectionId==selection.selectionId && checkpoint.payload.streamId==selection.streamId && checkpoint.payload.contentRef==selection.contentRef)
        val mode=when(val ref=selection.contentRef){is WireSource6LegacyContentRef6->ref.legacyRef.listeningMode;is WireSource6PreparedContentRef6->ref.listeningMode}
        require(mode==request.targetListeningMode)
        return FormatEvidence(id,request.sourceSelectionId,value.streamId,selection.selectionId,selection.contentRef,row.payloadHash,LearningJson.hash(requireNotNull(row.receiptJson)))
    }
    private suspend fun acknowledgedLegacyFormat(scope:LearningCallScope,id:String):FormatEvidence{
        val dao=requireNotNull(legacy)
        val requestRow=requireNotNull(dao.record(scope.owner.profileId.toString(),scope.owner.privacyEpoch,stableRecordId("request:$id").toString()))
        val receiptRow=requireNotNull(dao.record(scope.owner.profileId.toString(),scope.owner.privacyEpoch,stableRecordId("receipt:$id").toString()))
        for(row in listOf(requestRow,receiptRow))require(row.profileId==scope.owner.profileId.toString() && row.privacyEpoch==scope.owner.privacyEpoch && row.payloadSchemaVersion==5 && row.version==1L)
        require(requestRow.kind=="format_change_request" && receiptRow.kind=="format_change_receipt")
        // These are original schema5 DTOs, parsed strictly; no v6 request/ACK is fabricated.
        val request=learningJson.decodeFromJsonElement(WirePersonalFormatChangeRequest.serializer(),codecs.parse("source6",requestRow.payloadJson))
        val value=learningJson.decodeFromJsonElement(WirePersonalFormatChangeResponse.serializer(),codecs.parse("source6",receiptRow.payloadJson))
        require(request.schemaVersion=="5" && value.schemaVersion=="5" && request.operationId==id && value.operationId==id &&
            request.profileContext.profileId==scope.owner.profileId.toString() && request.profileContext.privacyEpoch==scope.owner.privacyEpoch && value.profileContext==request.profileContext)
        for(uuid in listOf(id,request.streamId,request.expectedHeadSelectionId,request.sourceSelectionId,request.sourceCheckpointId,request.checkpointId,
            value.streamId,value.selection.selectionId,value.selection.streamId,value.checkpoint.recordId))require(UUID.fromString(uuid).toString()==uuid.lowercase())
        val selection=value.selection;val checkpoint=value.checkpoint;val content=selection.contentRef
        require(request.expectedStreamVersion>=1 && request.expectedStreamVersion<Long.MAX_VALUE && request.expectedCheckpointVersion>=1 &&
            value.streamId==request.streamId && value.streamVersion==request.expectedStreamVersion+1 && selection.streamId==value.streamId &&
            selection.previousSelectionId==request.expectedHeadSelectionId && selection.selectionReason=="explicit_format_change" &&
            selection.formatChangeSource?.selectionId==request.sourceSelectionId && selection.formatChangeSource?.checkpointId==request.sourceCheckpointId &&
            checkpoint.version==1L && checkpoint.recordId==request.checkpointId && checkpoint.payload.checkpointId==request.checkpointId &&
            checkpoint.payload.selectionId==selection.selectionId && checkpoint.payload.streamId==selection.streamId && checkpoint.payload.contentRef==content &&
            checkpoint.payload.phase=="main" && checkpoint.payload.positionMs==0L && checkpoint.payload.activeRunId==null && content.listeningMode==request.targetListeningMode)
        val ref=WireSource6LegacyContentRef5(content.articleId,content.bundleId,content.sourceSnapshotId,content.listeningMode,content.listeningScriptId)
        codecs.validateDefinition("source6","LegacyContentRef5",learningJson.encodeToJsonElement(WireSource6LegacyContentRef5.serializer(),ref))
        return FormatEvidence(id,request.sourceSelectionId,value.streamId,selection.selectionId,WireSource6LegacyContentRef6("legacy_bundle",ref),
            LearningJson.hash(requestRow.payloadJson),LearningJson.hash(receiptRow.payloadJson),requestRow.payloadJson,receiptRow.payloadJson)
    }
    private fun formatHead(scope:LearningCallScope,format:FormatEvidence,actual:WireReview2ListeningContextResponse2):ConfirmedAutoProgressHead{
        val context=actual.context;val progress=requireNotNull(context.autoProgressRef)
        require(actual.reviewContractVersion==2L && actual.profileId==scope.owner.profileId.toString() && actual.learningEpoch==scope.owner.privacyEpoch &&
            context.profileId==actual.profileId && context.learningEpoch==actual.learningEpoch && context.selectionId==format.selectionId && progress.version>1)
        return ConfirmedAutoProgressHead(UUID.fromString(progress.autoProgressId),progress.version,UUID.fromString(format.selectionId),
            UUID.fromString(format.streamId),format.content,UUID.fromString(format.operationId))
    }
    private fun requireFollows(previous:ConfirmedAutoProgressHead,request:FormatEvidence,next:ConfirmedAutoProgressHead){
        require(previous.version<Long.MAX_VALUE && next.version==previous.version+1 && previous.autoProgressId==next.autoProgressId &&
            previous.selectionId.toString()==request.sourceSelectionId && previous.nativeStreamId.toString()==request.streamId)
        val sameSource=when(val old=previous.content){
            is WireSource6LegacyContentRef6->(next.content as? WireSource6LegacyContentRef6)?.legacyRef?.let {
                it.articleId==old.legacyRef.articleId && it.bundleId==old.legacyRef.bundleId && it.sourceSnapshotId==old.legacyRef.sourceSnapshotId
            }==true
            is WireSource6PreparedContentRef6->(next.content as? WireSource6PreparedContentRef6)?.let {
                it.articleId==old.articleId && it.sourceSnapshotId==old.sourceSnapshotId && it.sourceHash==old.sourceHash
            }==true
        }
        require(sameSource)
    }
    private suspend fun storedFormat(scope:LearningCallScope,row:LocalSource6Record):FormatProof{
        require(row.profileId==scope.owner.profileId.toString() && row.privacyEpoch==scope.owner.privacyEpoch && row.kind==FORMAT_PROOF &&
            row.version==1L && LearningJson.hash(row.payloadJson)==row.payloadHash)
        val body=learningJson.parseToJsonElement(row.payloadJson).jsonObject
        val keys=setOf("operation_id","owner_revision","request_hash","receipt_hash","context")
        require(body.keys==keys || body.keys==keys+setOf("legacy_request","legacy_receipt"))
        require(body["owner_revision"]==JsonPrimitive(scope.owner.revision) && body["operation_id"]==JsonPrimitive(row.recordId))
        val format=acknowledgedFormat(scope,row.recordId)
        require(body["request_hash"]==JsonPrimitive(format.requestHash) && body["receipt_hash"]==JsonPrimitive(format.receiptHash))
        if(format.legacyRequestJson!=null)require(body["legacy_request"]==JsonPrimitive(format.legacyRequestJson) && body["legacy_receipt"]==JsonPrimitive(format.legacyReceiptJson))
        else require(body.keys==keys)
        val context=codecs.decode("review2","ListeningContextResponse2",body.getValue("context").toString(),WireReview2ListeningContextResponse2.serializer())
        return FormatProof(format,formatHead(scope,format,context))
    }

}
